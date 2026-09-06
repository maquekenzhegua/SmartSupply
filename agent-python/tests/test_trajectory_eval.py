"""Trajectory 评测单元测试：评分数学 + golden 数据完整性（不依赖网络/Java）。"""
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from scripts.eval_trajectory import score_case  # noqa: E402

GOLDEN = ROOT / "tests" / "golden_trajectory.jsonl"


def _rows():
    return [json.loads(l) for l in GOLDEN.read_text(encoding="utf-8").splitlines() if l.strip()]


def test_score_case_perfect_and_alternative():
    # 完全命中首选路径
    s = score_case(["list_low_stock"], ["list_low_stock", "get_inventory"], ["list_low_stock"])
    assert s["f1"] == 1.0 and s["order_ok"] is True
    # 走了等价替代路径：f1 满分但严格顺序不一致
    s = score_case(["get_inventory"], ["get_inventory", "list_low_stock"], ["list_low_stock"])
    assert s["f1"] == 1.0 and s["order_ok"] is False
    assert s["precision"] == 1.0 and s["recall"] == 1.0


def test_score_case_miss_and_extra():
    # 漏调度：期望的工具没调
    s = score_case(["search_knowledge"], ["search_knowledge"], [])
    assert s["recall"] == 0.0 and s["precision"] == 1.0 and s["f1"] == 0.0
    # 误调度：寒暄却调了工具
    s = score_case([], [], ["list_low_stock"])
    assert s["f1"] == 0.0
    # 寒暄且没调工具 = 满分
    s = score_case([], [], [])
    assert s["f1"] == 1.0 and s["order_ok"] is True
    # 调了路径外的工具：precision 受罚
    s = score_case(["search_contracts"], ["search_contracts"], ["search_contracts", "list_suppliers"])
    assert s["precision"] < 1.0


def test_score_case_empty_expected_empty_actual():
    s = score_case([], [], [])
    assert s["f1"] == 1.0


def test_golden_trajectory_wellformed():
    rows = _rows()
    assert len(rows) >= 10, "golden trajectory 样本应覆盖足够多的问题类型"
    from app.graph import TOOL_REGISTRY
    for r in rows:
        assert r.get("id") and r.get("question")
        assert "expected_tools" in r and "acceptable_tools" in r
        # 期望工具必须是真实注册的工具（含写工具校验：golden 中不得出现写工具——HITL 无法交互确认）
        for t in (r["expected_tools"] + r["acceptable_tools"]):
            assert t in TOOL_REGISTRY, f"{r['id']} 引用了未注册工具 {t}"
            assert t not in ("create_purchase_order",), f"{r['id']} 写工具不应进入 trajectory 评测集"
        # acceptable 必须覆盖 expected（expected 是 acceptable 的子集语义）
        assert set(r["expected_tools"]) <= set(r["acceptable_tools"] or r["expected_tools"]), \
            f"{r['id']} acceptable_tools 应包含 expected_tools"
