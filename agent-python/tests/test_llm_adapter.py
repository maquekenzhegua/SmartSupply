"""LLM 适配层单测：重试/退避语义、muse /responses 协议解析、用量真实/估算口径。

此前 openai/muse 适配层整体未测——重试只对瞬态（429/5xx/超时）生效、协议错误立即上抛、
usage 缺失显式标 estimated 这些契约都靠人工回归，一旦回归无人知晓。
离线可跑：全程 monkeypatch，不发真实网络请求。
"""
import asyncio
import json

import pytest

from app import config
from app import llm as L


@pytest.fixture(autouse=True)
def _real_mode(monkeypatch):
    # LLM_MODE 的判定直接读 os.getenv（config.__getattr__），patch config 属性不影响它，
    # 必须像既有测试一样显式 patch LLM_MODE
    monkeypatch.setattr(config, "LLM_MODE", "real")
    monkeypatch.setattr(config, "OPENAI_BASE_URL", "https://opencode.ai/zen/go/v1")
    monkeypatch.setattr(config, "AI_MODEL", "muse-spark-1.2-contributor")
    monkeypatch.setattr(config, "LLM_MAX_ATTEMPTS", 3)
    # 退避不真实睡眠（测试提速），但记录调用次数与间隔序列供断言
    sleeps = []
    real_sleep = asyncio.sleep

    async def fake_sleep(sec):
        sleeps.append(sec)
        await real_sleep(0)

    monkeypatch.setattr(L.asyncio, "sleep", fake_sleep)
    yield sleeps


def _muse_call(monkeypatch, outcomes, attempts):
    """monkeypatch _muse_responses 为依次返回 outcomes / 抛异常的桩，记录调用次数。"""
    seq = list(outcomes)

    async def fake(messages, tools, spec):
        attempts["n"] += 1
        item = seq.pop(0)
        if isinstance(item, Exception):
            raise item
        return item

    monkeypatch.setattr(L, "_muse_responses", fake)


def test_chat_retries_transient_then_succeeds(monkeypatch, _real_mode):
    attempts = {"n": 0}
    ok = {"text": "ok", "tool_calls": None, "provider": "muse"}
    _muse_call(monkeypatch, [
        L.LLMUnavailable("HTTP 429", retryable=True),
        L.LLMUnavailable("timeout", retryable=True),
        ok,
    ], attempts)
    res = asyncio.run(L.chat([{"role": "user", "content": "hi"}]))
    assert res["text"] == "ok"
    assert attempts["n"] == 3          # 前两次瞬态失败重试，第三次成功
    assert len(_real_mode) == 2        # 两次退避
    assert _real_mode[1] >= _real_mode[0] * 1.5  # 指数退避


def test_chat_does_not_retry_protocol_errors(monkeypatch, _real_mode):
    attempts = {"n": 0}
    _muse_call(monkeypatch, [
        L.LLMUnavailable("muse 返回无内容", retryable=False),
    ], attempts)
    with pytest.raises(L.LLMUnavailable):
        asyncio.run(L.chat([{"role": "user", "content": "hi"}]))
    assert attempts["n"] == 1          # 协议错误立即上抛，不烧重试
    assert _real_mode == []


def test_chat_raises_after_attempts_exhausted(monkeypatch, _real_mode):
    attempts = {"n": 0}
    _muse_call(monkeypatch, [L.LLMUnavailable("HTTP 503", retryable=True)] * 3, attempts)
    with pytest.raises(L.LLMUnavailable):
        asyncio.run(L.chat([{"role": "user", "content": "hi"}]))
    assert attempts["n"] == 3


def test_chat_stream_never_retries(monkeypatch, _real_mode):
    """流式不重试：中途失败直接上抛（重放已发 token 会造成重复输出）。"""
    attempts = {"n": 0}

    async def fake_stream(messages, spec, usage_out):
        attempts["n"] += 1
        yield "你"
        raise L.LLMUnavailable("stream broken", retryable=True)

    monkeypatch.setattr(L, "_muse_stream", fake_stream)

    async def consume():
        out = []
        with pytest.raises(L.LLMUnavailable):
            async for delta in L.chat_stream([{"role": "user", "content": "hi"}]):
                out.append(delta)
        return out

    out = asyncio.run(consume())
    assert out == ["你"] and attempts["n"] == 1
    assert _real_mode == []            # 流式失败没有触发任何退避重试


def test_usage_or_estimate_marks_estimated_honestly():
    est = L.usage_or_estimate(None, "你好世界", "answer")
    assert est["source"] == "estimated"
    actual = {"prompt_tokens": 10, "completion_tokens": 5, "source": "actual"}
    assert L.usage_or_estimate(actual) is actual  # 真实回传原样透传，不重算


class _FakeResp:
    status_code = 200

    def raise_for_status(self):
        pass

    def json(self):
        return {
            "output": [
                {"type": "function_call", "name": "search_catalog", "arguments": "{\"keyword\": \"T恤\"}"},
                {"type": "message", "role": "assistant", "content": [{"type": "output_text", "text": "部分"}]},
            ],
            "usage": {"input_tokens": 100, "output_tokens": 20},
        }


class _FakeClient:
    def __init__(self, resp):
        self._resp = resp
        self.last_headers = None

    async def post(self, url, headers=None, json=None):
        self.last_headers = headers
        return self._resp


def test_muse_responses_parses_tool_calls_text_and_usage(monkeypatch):
    fake = _FakeClient(_FakeResp())
    monkeypatch.setattr(L, "_http_client", lambda: fake)
    spec = L._muse_spec("muse-spark-1.2-contributor")
    assert spec, "opencode+muse 模型必须路由到 muse /responses"
    res = asyncio.run(L._muse_responses([{"role": "user", "content": "查T恤"}],
                                        [{"type": "function", "function": {"name": "search_catalog"}}], spec))
    assert res["tool_calls"] == [{"name": "search_catalog", "arguments": {"keyword": "T恤"}}]
    assert res["provider"] == "muse"
    assert res["usage"] == {"prompt_tokens": 100, "completion_tokens": 20, "source": "actual"}
    # opencode 网关会话头必须携带（缺失会被网关 400 拒绝）
    assert fake.last_headers.get("x-opencode-session")


def test_muse_request_omits_authorization_when_no_key(monkeypatch):
    """未配置任何 key 时不得发出 "Bearer None"（此前会以非法头打网关）。"""
    monkeypatch.setattr(config, "OPENAI_API_KEY", "")
    monkeypatch.setattr(config, "DASHSCOPE_API_KEY", "")
    fake = _FakeClient(_FakeResp())
    monkeypatch.setattr(L, "_http_client", lambda: fake)
    spec = L._muse_spec("muse-spark-1.2-contributor")
    res = asyncio.run(L._muse_responses([{"role": "user", "content": "hi"}], None, spec))
    assert "Authorization" not in (fake.last_headers or {})
    assert res["tool_calls"], "响应仍按 muse 协议正常解析（无 key 只影响请求头）"