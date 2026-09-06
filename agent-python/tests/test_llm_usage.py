"""token 用量观测补丁单测：真实回传优先、估算显式标注、Langfuse usage_details 映射。

背景：真实 trace 里 generation 的 usage 为空——provider 网关不回传 usage 且
planner/reflector 调用点漏传。修复后：actual 优先，缺失时本地粗估并标注
source=estimated（metadata.token_source 随 generation 落 Langfuse）。
"""
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app import observability as obs
from app.llm import estimate_tokens, usage_or_estimate


def test_estimate_tokens_cjk_vs_ascii():
    # CJK ≈ 1 token/字；ASCII ≈ 4 字符/token；空串为 0
    assert estimate_tokens("") == 0
    assert estimate_tokens("你好世界") == 4
    assert 1 <= estimate_tokens("abcdefgh") <= 4  # 8 个 ASCII 字符约 2 token


def test_usage_or_estimate_prefers_actual():
    actual = {"prompt_tokens": 10, "completion_tokens": 5, "source": "actual"}
    assert usage_or_estimate(actual, "提示词", "回答") is actual  # 原样透传，绝不覆盖


def test_usage_or_estimate_fills_estimated_and_labels_it():
    out = usage_or_estimate(None, "这是一个用于估算的中文提示词", "简短回答")
    assert out["source"] == "estimated"
    assert out["prompt_tokens"] > 0 and out["completion_tokens"] > 0
    # 空字典（provider 流式未回传 usage 时 usage_box 的形态）同样走估算
    out2 = usage_or_estimate({}, "提示", "回答")
    assert out2["source"] == "estimated"


class _RecordingGeneration:
    def __init__(self, **kwargs):
        self.kwargs = kwargs

    def end(self):
        pass


class _StubTrace:
    def __init__(self):
        self.last = None

    def generation(self, **kwargs):
        self.last = _RecordingGeneration(**kwargs)
        return self.last


def test_generation_maps_usage_details_and_token_source():
    t = _StubTrace()
    # 真实链路的 provider usage 恒带 source（llm.py 两种协议都写 source=actual）
    obs.generation(t, "reasoner", "m", {"messages": 2}, "答",
                   usage={"prompt_tokens": 3, "completion_tokens": 5, "source": "actual"})
    assert t.last.kwargs["usage_details"] == {"input": 3, "output": 5}
    assert t.last.kwargs["metadata"] == {"token_source": "actual"}

    obs.generation(t, "reasoner", "m", {"messages": 2}, "答", usage={"prompt_tokens": 7, "completion_tokens": 2, "source": "estimated"})
    assert t.last.kwargs["metadata"] == {"token_source": "estimated"}

    # 无 usage / 无 source 的旧形态：不落 usage_details 也不落来源标注
    obs.generation(t, "reasoner", "m", {"messages": 2}, "答")
    assert t.last.kwargs["usage_details"] is None
    assert t.last.kwargs["metadata"] is None
    obs.generation(t, "reasoner", "m", {"messages": 2}, "答", usage={"prompt_tokens": 3, "completion_tokens": 5})
    assert t.last.kwargs["metadata"] is None
