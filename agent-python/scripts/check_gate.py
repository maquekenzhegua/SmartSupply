#!/usr/bin/env python3
"""评测报告阈值门禁：解析 llm_judge.py / eval_trajectory.py 产出的 eval-report-*.md，
对关键指标做数值比较。

此前 CI 用 `grep -q "avg_keyword_hit"` 做门禁——只检查字符串存在，结构上不可能失败，
属于形式门禁。本脚本把门禁落到数值：任何指标缺失或低于阈值都以非零码退出。

用法：
  python scripts/check_gate.py <report.md> [--min-hit 0.6] [--min-evidence 0.0] [--min-faithfulness 0.0]
                                [--min-tool-f1 0.0]   # trajectory 评测报告用
"""
import argparse
import re
import sys
import pathlib

METRIC_PATTERNS = {
    "hit": r"avg_keyword_hit:\s*([0-9.]+)",
    "evidence": r"avg_evidence_recall(?:\([^)]*\))?:\s*([0-9.]+)",
    "faithfulness": r"avg_faithfulness(?:\(0-2\))?:\s*([0-9.]+)",
    "relevance": r"avg_relevance(?:\(0-2\))?:\s*([0-9.]+)",
    "tool_f1": r"avg_tool_f1:\s*([0-9.]+)",
}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("report", type=pathlib.Path)
    ap.add_argument("--min-hit", type=float, default=0.0)
    ap.add_argument("--min-evidence", type=float, default=0.0)
    ap.add_argument("--min-faithfulness", type=float, default=0.0)
    ap.add_argument("--min-relevance", type=float, default=0.0)
    ap.add_argument("--min-tool-f1", type=float, default=0.0)
    args = ap.parse_args()

    if not args.report.exists():
        print(f"GATE FAIL: 报告不存在 {args.report}", file=sys.stderr)
        return 2
    text = args.report.read_text(encoding="utf-8")

    values: dict[str, float] = {}
    for name, pattern in METRIC_PATTERNS.items():
        m = re.search(pattern, text)
        if m:
            values[name] = float(m.group(1))

    gates = [
        ("hit", args.min_hit),
        ("evidence", args.min_evidence),
        ("faithfulness", args.min_faithfulness),
        ("relevance", args.min_relevance),
        ("tool_f1", args.min_tool_f1),
    ]
    failed = []
    for name, minimum in gates:
        if minimum <= 0.0:
            continue  # 阈值未启用
        if name not in values:
            failed.append(f"{name}: 指标缺失（报告不含可解析数值）")
        elif values[name] < minimum:
            failed.append(f"{name}: {values[name]:.3f} < 阈值 {minimum:.3f}")

    print(f"[gate] parsed metrics: { {k: round(v, 3) for k, v in values.items()} }")
    if failed:
        for f in failed:
            print(f"GATE FAIL: {f}", file=sys.stderr)
        return 2
    print("GATE PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
