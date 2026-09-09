"""compare_eval.py 单测：报告解析与相对跌幅判定的数学口径。"""
import importlib.util
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("compare_eval", HERE.parent / "scripts" / "compare_eval.py")
CE = importlib.util.module_from_spec(spec)
spec.loader.exec_module(CE)

CUR_MD = """
- avg_keyword_hit: 0.900
- avg_evidence_recall(证据进top-k全命中): 0.800
- avg_faithfulness(0-2): 1.60
- avg_relevance(0-2): 1.50
"""

BASE_MD = """
- avg_keyword_hit: 0.633
- avg_evidence_recall(证据进top-k): 0.683
- avg_faithfulness(0-2): 1.40
- avg_relevance(0-2): 1.48
"""


def test_parse_metrics_handles_annotated_lines():
    m = CE.parse_metrics(CUR_MD)
    assert abs(m["avg_keyword_hit"] - 0.900) < 1e-9
    assert abs(m["avg_evidence_recall"] - 0.800) < 1e-9
    assert abs(m["avg_faithfulness"] - 1.60) < 1e-9
    assert "avg_tool_f1" not in m


def test_compare_passes_on_improvement():
    cur, base = CE.parse_metrics(CUR_MD), CE.parse_metrics(BASE_MD)
    failed = CE.compare(cur, base, 0.10)
    assert failed == []  # 当前全面优于基线


def test_compare_fails_beyond_tolerance():
    base = {"avg_keyword_hit": 0.80, "avg_faithfulness": 1.60}
    cur = {"avg_keyword_hit": 0.60, "avg_faithfulness": 1.50}  # 25% / 6.25% 跌幅
    failed = CE.compare(cur, base, 0.10)
    assert len(failed) == 1 and "avg_keyword_hit" in failed[0]


def test_compare_skips_missing_baseline_metric():
    base = {"avg_keyword_hit": 0.80}
    cur = {"avg_faithfulness": 1.50}  # 当前报告缺 hit、基线缺 faithfulness
    failed = CE.compare(cur, base, 0.10)
    assert failed == []  # 基线缺的指标跳过；当前缺的仅告警不失败
