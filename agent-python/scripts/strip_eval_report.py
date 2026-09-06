#!/usr/bin/env python3
"""从 llm_judge.py 的完整真实评测报告生成可提交的脱敏汇总版。

完整报告含模型原始答案片段（含企业知识库原文），按 .gitignore 约定不入库；
本脚本剥离所有原文片段，仅保留：
  - 头部元信息（模型/样本数/召回策略/聚合指标）
  - 每题数值表（must_hit / evid / faith / rel，去掉"答案片段"列）
  - 失败样本清单（仅问题与数值，不含答案）
用法：python scripts/strip_eval_report.py <full-report.md> <summary-out.md> [--model mimo-v2.5]
"""
import argparse
import datetime
import pathlib
import re


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("src", type=pathlib.Path)
    ap.add_argument("dst", type=pathlib.Path)
    ap.add_argument("--model", default="")
    args = ap.parse_args()

    text = args.src.read_text(encoding="utf-8")

    # 头部指标段：从 "# Eval Report" 到第一个空行分隔的说明段
    header_lines = []
    table_rows = []
    fails = []
    in_fails = False
    for line in text.splitlines():
        if line.startswith("| # |"):
            continue  # 表头
        if line.startswith("|---"):
            continue
        m = re.match(r"\| (\d+) \| (.+) \| ([0-9.]+) \| (\d) \| (\d) \| (\d) \|", line)
        if m:
            table_rows.append((m.group(1), m.group(2), m.group(3), m.group(4), m.group(5), m.group(6)))
            continue
        if line.startswith("## 失败样本"):
            in_fails = True
            continue
        if in_fails:
            # 只接受严格的失败样本格式，丢弃模型多行答案的续行（防止原始答案混入汇总）
            fm = re.match(r"^- (.+?) => hit=([0-9.]+) evid=(\d) fh=(\d) rel=(\d)(?: \| .*)?$", line)
            if fm:
                fails.append(f"{fm.group(1)} (hit={fm.group(2)} evid={fm.group(3)} fh={fm.group(4)} rel={fm.group(5)})")
            continue
        if not in_fails:
            header_lines.append(line)

    # 头部清洗：去掉原始报告中的标题日期行，重建
    metrics = {}
    for key, pat in {
        "model": r"- 模型: (.+)",
        "n": r"- 样本数: (\d+)",
        "recall": r"- 召回策略: (.+)",
        "hit": r"- avg_keyword_hit: ([0-9.]+)",
        "evidence": r"- avg_evidence_recall(?:\(证据进top-k\))?: ([0-9.]+)",
        "faithfulness": r"- avg_faithfulness(?:\(0-2\))?: ([0-9.]+)",
        "relevance": r"- avg_relevance(?:\(0-2\))?: ([0-9.]+)",
    }.items():
        m = re.search(pat, text)
        if m:
            metrics[key] = m.group(1)

    out = []
    out.append(f"# 真实模型 RAG 评测汇总（脱敏版）")
    out.append("")
    out.append(f"- 生成日期: {datetime.date.today().isoformat()}")
    out.append(f"- 模型: {args.model or metrics.get('model', 'real')}")
    out.append(f"- 样本数: {metrics.get('n', len(table_rows))}")
    out.append(f"- 召回策略: {metrics.get('recall', 'vector')}")
    out.append(f"- avg_keyword_hit: {metrics.get('hit', '-')}")
    out.append(f"- avg_evidence_recall(证据进top-k): {metrics.get('evidence', '-')}")
    out.append(f"- avg_faithfulness(0-2): {metrics.get('faithfulness', '-')}")
    out.append(f"- avg_relevance(0-2): {metrics.get('relevance', '-')}")
    out.append("")
    out.append("> 脱敏说明：完整报告含模型原始输出与知识库原文，按仓库安全约定不入库"
               "（见 .gitignore）；本汇总仅保留聚合指标与逐题数值，供评审与面试展示。"
               "复现方式见 docs/test-report.md「真实模型评测」一节。")
    out.append("")
    out.append("## 逐题数值")
    out.append("")
    out.append("| # | 问题 | must_hit | evid | faith | rel |")
    out.append("|---|---|---|---|---|---|")
    for idx, q, hit, ev, fh, rel in table_rows:
        out.append(f"| {idx} | {q} | {hit} | {ev} | {fh} | {rel} |")
    out.append("")
    full_hits = sum(1 for _, _, h, _, _, _ in table_rows if h == "1.00")
    out.append(f"- 满分题（must_hit=1.00）：{full_hits}/{len(table_rows)}")
    out.append("")
    if fails:
        out.append(f"## 失败/部分失败样本（{len(fails)}）")
        out.append("")
        for f in fails:
            out.append(f"- {f}")
        out.append("")
    args.dst.write_text("\n".join(out) + "\n", encoding="utf-8", newline="")
    print(f"wrote {args.dst} rows={len(table_rows)} fails={len(fails)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
