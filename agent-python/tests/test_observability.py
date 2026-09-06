"""observability（Langfuse 可选埋点）单元测试：未配置 key 时整体 no-op，绝不影响主流程。"""
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app import config
from app import observability as obs


def test_disabled_without_keys(monkeypatch):
    monkeypatch.setattr(config, "LANGFUSE_PUBLIC_KEY", "")
    monkeypatch.setattr(config, "LANGFUSE_SECRET_KEY", "")
    assert obs.enabled() is False
    assert obs.client() is None


def test_noop_trace_surface(monkeypatch):
    """禁用态：trace/span/generation 与真实对象同接口，调用方无需判空，且全程静默。"""
    monkeypatch.setattr(config, "LANGFUSE_PUBLIC_KEY", "")
    monkeypatch.setattr(config, "LANGFUSE_SECRET_KEY", "")
    t = obs.start_trace("tid", "sess", "general", "q")
    s = obs.span(t, "tool:x", input={"a": 1}, output={"ok": True})
    g = obs.generation(t, "reasoner", "mock-model", {"messages": 2}, "answer", usage={"prompt_tokens": 3, "completion_tokens": 5})
    obs.finish_trace(t, output="done", metadata={"interrupted": False})
    assert s is not None and g is not None  # NOOP 对象，调用不抛异常


def test_payload_dump_truncates_and_never_raises():
    class Weird:
        def __repr__(self):
            return "weird"

    out = obs._dump({"x": Weird(), "big": "y" * 10000}, limit=100)
    assert isinstance(out, str) and len(out) <= 100
    ok = obs._dump({"n": 1})
    assert ok == {"n": 1}
