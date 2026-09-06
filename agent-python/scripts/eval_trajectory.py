"""Trajectory 评测：不只评最终答案，还评 agent 的工具调用序列是否合理。

与 llm_judge.py（答案质量：faithfulness/relevance）互补，本脚本回答另一个面试高频问题：
"你怎么知道 agent 调对了工具？" —— 用 golden 期望工具序列对实际执行序列打分。

指标（对每个样本）：
  precision = |actual ∩ acceptable| / |actual|   实际调用里有多少是合理路径内（误调度越少越高）
  recall    = |actual ∩ acceptable| / |expected| 期望的取证需求是否被覆盖（被 acceptable
              等价路径覆盖同样计入；漏调度越少越高）
  f1        = 2PR/(P+R)（空对空=满分；expected 为空但 actual 非空 = 误调度，0 分）
  order_ok  = actual 序列与 expected_tools 严格一致（决策顺序正确的强指标，报告不门禁）

口径说明：
  - expected_tools 是首选路径；acceptable_tools 是同样合理的替代路径集合（如单 SKU 库存
    get_inventory 与全量低库存 list_low_stock 都算合理）。f1 按 acceptable 集合算，
    order_ok 按 expected_tools 严格比较。
  - 写工具（create_purchase_order）不进入评测集：执行前 interrupt 挂起等人工批准，
    评测无法交互确认（见 graph.WRITE_TOOLS 设计）。
  - 工具执行失败（如 CI 无 Java 服务）不影响评分：trajectory 度量的是"调了什么"，
    工具失败仍会以 ok=False 落入 tool_results，工具名照常计入。

用法：
  python scripts/eval_trajectory.py --mode mock --out ../docs/eval-trajectory-mock.md --min-f1 0.9
  OPENAI_API_KEY=... python scripts/eval_trajectory.py --mode real --out ../docs/eval-trajectory-real.md
"""
import argparse, asyncio, datetime, json, os, pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
GOLDEN = ROOT / "tests" / "golden_trajectory.jsonl"
sys.path.insert(0, str(ROOT))


def score_case(expected, acceptable, actual) -> dict:
    """单样本 trajectory 打分。
    precision 分母是实际调用（误调度惩罚）；recall 分母是期望需求（acceptable 等价覆盖计入）。
    expected 为空（寒暄类）：actual 也为空 = 满分；多调了 = 误调度记 0。"""
    acc_set = set(acceptable) or set(expected)
    exp_set = set(expected)
    act_set = set(actual)
    hit = act_set & acc_set
    precision = (len(hit) / len(act_set)) if act_set else 1.0
    if exp_set:
        recall = len(hit) / len(exp_set)
    else:
        recall = 1.0 if not act_set else 0.0
    f1 = (2 * precision * recall / (precision + recall)) if (precision + recall) > 0 else 0.0
    return {"precision": precision, "recall": recall, "f1": f1, "order_ok": list(actual) == list(expected)}


def run(mode: str, limit=None):
    from app import config
    from app.graph import run_reasoning_with_trace
    if mode == "mock":
        config.LLM_MODE = "mock"  # 显式固定，防本地 .env 有 key 时混入真实模型
    rows = [json.loads(l) for l in GOLDEN.read_text(encoding="utf-8").splitlines() if l.strip()]
    if limit:
        rows = rows[:limit]
    results = []
    for r in rows:
        try:
            out = asyncio.run(run_reasoning_with_trace(
                [{"role": "user", "content": r["question"]}], "general", f"eval-traj-{r['id']}"))
            actual = [t.get("tool") for t in out.get("tool_results") or []]
            degraded = bool(out.get("degraded"))
            err = out.get("degrade_reason") or ""
        except Exception as e:
            actual, degraded, err = [], True, f"run_error: {e}"
        s = score_case(r.get("expected_tools") or [], r.get("acceptable_tools") or r.get("expected_tools") or [], actual)
        results.append({"id": r["id"], "q": r["question"], "expected": r.get("expected_tools") or [],
                        "acceptable": r.get("acceptable_tools") or [], "actual": actual,
                        "degraded": degraded, "err": err, **s})
    n = max(1, len(results))
    avg_f1 = sum(x["f1"] for x in results) / n
    avg_p = sum(x["precision"] for x in results) / n
    avg_r = sum(x["recall"] for x in results) / n
    order_rate = sum(1 for x in results if x["order_ok"]) / n
    return results, avg_f1, avg_p, avg_r, order_rate


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["mock", "real"], default="mock")
    ap.add_argument("--out", default=None)
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--min-f1", type=float, default=0.0, help="avg_tool_f1 门禁阈值；<=0 不启用")
    args = ap.parse_args()
    results, avg_f1, avg_p, avg_r, order_rate = run(args.mode, args.limit)
    date = datetime.date.today().isoformat()
    out_path = pathlib.Path(args.out) if args.out else pathlib.Path(f"../docs/eval-trajectory-{args.mode}-{date}.md")
    if not out_path.is_absolute():
        out_path = (ROOT / out_path).resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    md = [f"# Trajectory Eval — {args.mode} — {date}", "",
          f"- 模型: {os.getenv('AI_MODEL', 'mock')}",
          f"- 样本数: {len(results)}",
          f"- avg_tool_f1: {avg_f1:.3f}",
          f"- avg_precision(误调度控制): {avg_p:.3f}",
          f"- avg_recall(取证覆盖): {avg_r:.3f}",
          f"- order_match_rate(严格序列一致): {order_rate:.3f}",
          "", "f1 按 acceptable_tools 集合计（同等合理的替代取证路径不扣分）；order_ok 按 expected_tools 严格比较。", "",
          "| # | 问题 | expected | actual | P | R | f1 | order |",
          "|---|---|---|---|---|---|---|---|"]
    for i, r in enumerate(results, 1):
        md.append(f"| {i} | {r['q'][:32]} | {','.join(r['expected']) or '∅'} | {','.join(r['actual']) or '∅'} "
                  f"| {r['precision']:.2f} | {r['recall']:.2f} | {r['f1']:.2f} | {'✓' if r['order_ok'] else '✗'} |")
    fails = [r for r in results if r["f1"] < 1.0 or r["degraded"]]
    if fails:
        md.append("")
        md.append(f"## 非满分/异常样本 {len(fails)}")
        for r in fails:
            md.append(f"- [{r['id']}] {r['q']} => actual={r['actual']} expected={r['expected']} f1={r['f1']:.2f}"
                      + (f" degraded={r['err']}" if r["degraded"] else ""))
    out_path.write_text("\n".join(md), encoding="utf-8")
    print(f"wrote {out_path} avg_tool_f1={avg_f1:.3f} P={avg_p:.3f} R={avg_r:.3f} order={order_rate:.3f}")
    if args.min_f1 > 0 and avg_f1 < args.min_f1:
        print(f"GATE FAIL: avg_tool_f1 {avg_f1:.3f} < {args.min_f1}", file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
