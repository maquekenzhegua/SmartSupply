#!/usr/bin/env python3
"""评测回归对比：当前评测指标 vs 基线（上一次评测），相对跌幅超阈值即非零退出。

此前评测只有"单次数值门禁"（check_gate.py），没有"这次 vs 上次"的自动对比——
模型/检索质量回归要靠人工翻历史报告才发现。本脚本补上时序对比的另一半：
  - 基线来源 A（CI 用）：--baseline-report <md>（如入库的 docs/eval-report-real-summary.md）
  - 基线来源 B（本地/在线闭环用）：--snapshot-url <API> [--source llm-judge-real]
    读取 eval_snapshot 最新一条同源记录
  - 指标相对跌幅 > --max-drop（默认 0.10）→ GATE FAIL 退出码 2

用法：
  python scripts/compare_eval.py --report docs/eval-report-real-ci.md \
      --baseline-report ../docs/eval-report-real-summary.md
  python scripts/compare_eval.py --report docs/eval-report-real-ci.md \
      --snapshot-url http://localhost:8080/api/admin/agent/eval/snapshots --source llm-judge-real
"""
import argparse
import json
import os
import pathlib
import re
import sys

# 关注指标：名称 → 报告行正则（兼容带括号注释的行格式）
METRIC_PATTERNS = {
    "avg_keyword_hit": r"avg_keyword_hit:\s*([0-9.]+)",
    "avg_evidence_recall": r"avg_evidence_recall(?:\([^)]*\))?:\s*([0-9.]+)",
    "avg_faithfulness": r"avg_faithfulness(?:\([^)]*\))?:\s*([0-9.]+)",
    "avg_relevance": r"avg_relevance(?:\([^)]*\))?:\s*([0-9.]+)",
    "avg_tool_f1": r"avg_tool_f1:\s*([0-9.]+)",
}


def parse_metrics(md_text: str) -> dict:
    out = {}
    for name, pat in METRIC_PATTERNS.items():
        m = re.search(pat, md_text)
        if m:
            out[name] = float(m.group(1))
    return out


def fetch_baseline_from_api(url: str, source: str) -> dict:
    import httpx
    headers = {}
    token = os.getenv("EVAL_SNAPSHOT_JWT", "")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    r = httpx.get(url, params={"limit": 50}, headers=headers, timeout=10)
    r.raise_for_status()
    body = r.json()
    rows = body.get("data") if isinstance(body, dict) else body
    for row in rows or []:
        if str(row.get("source", "")) == source:
            metrics = row.get("metrics")
            if isinstance(metrics, str):
                try:
                    metrics = json.loads(metrics)
                except Exception:
                    metrics = {}
            if isinstance(metrics, dict):
                return {k: float(v) for k, v in metrics.items()
                        if isinstance(v, (int, float)) and (k in METRIC_PATTERNS or k in ("hit", "n", "judge_valid"))}
    return {}


def compare(current: dict, baseline: dict, max_drop: float) -> list:
    """返回失败项说明列表；基线缺失的指标跳过（信息性），当前缺失的指标告警。"""
    failed = []
    for name, base_v in baseline.items():
        if name not in ("avg_keyword_hit", "avg_evidence_recall", "avg_faithfulness",
                        "avg_relevance", "avg_tool_f1"):
            continue
        cur_v = current.get(name)
        if cur_v is None:
            print(f"[compare] WARN 当前报告缺失指标 {name}（基线 {base_v:.3f}）")
            continue
        if base_v <= 0:
            continue
        drop = (base_v - cur_v) / base_v
        if drop > max_drop:
            failed.append(f"{name}: {cur_v:.3f} 较基线 {base_v:.3f} 相对下跌 {drop:.1%}（阈值 {max_drop:.0%}）")
        else:
            print(f"[compare] {name}: {cur_v:.3f} vs 基线 {base_v:.3f}（{drop:+.1%}）OK")
    return failed


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", type=pathlib.Path, required=True, help="当前评测报告 md")
    ap.add_argument("--baseline-report", type=pathlib.Path, default=None,
                    help="基线报告 md（CI 用入库的脱敏汇总版）")
    ap.add_argument("--snapshot-url", default=os.getenv("EVAL_SNAPSHOT_URL", ""),
                    help="eval_snapshot 查询地址（在线闭环）")
    ap.add_argument("--source", default="llm-judge-real", help="快照 source 过滤")
    ap.add_argument("--max-drop", type=float, default=0.10, help="相对跌幅阈值，默认 0.10")
    args = ap.parse_args()

    if not args.report.exists():
        print(f"GATE FAIL: 当前报告不存在 {args.report}", file=sys.stderr)
        return 2
    current = parse_metrics(args.report.read_text(encoding="utf-8"))
    if not current:
        print(f"GATE FAIL: 当前报告无可解析指标 {args.report}", file=sys.stderr)
        return 2

    baseline = {}
    if args.baseline_report and args.baseline_report.exists():
        baseline = parse_metrics(args.baseline_report.read_text(encoding="utf-8"))
    elif args.snapshot_url:
        try:
            baseline = fetch_baseline_from_api(args.snapshot_url, args.source)
        except Exception as e:
            print(f"[compare] WARN 快照基线获取失败（不阻断）: {e}", file=sys.stderr)
    else:
        print("[compare] 未提供基线（--baseline-report / --snapshot-url），跳过对比")
        return 0

    if not baseline:
        print("[compare] 基线无可解析指标（首次运行或旧格式），跳过对比")
        return 0

    failed = compare(current, baseline, args.max_drop)
    if failed:
        for f in failed:
            print(f"GATE FAIL: {f}", file=sys.stderr)
        return 2
    print("COMPARE PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
