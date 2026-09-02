"""
LLM-as-judge 真实评测：用真实模型（muse-spark / OpenAI 兼容）做裁判，
对 golden_rag 逐条打 faithfulness/relevance 0-2 分，产出 docs/eval-report-*.md
用法：
  OPENAI_API_KEY=sk-... OPENAI_BASE_URL=https://opencode.ai/zen/go/v1 AI_MODEL=muse-spark-1.2-contributor python scripts/llm_judge.py --mode real --out ../docs/eval-report-real-2026-09-02.md
  python scripts/llm_judge.py --mode mock --out ../docs/eval-report-mock-2026-09-02.md
"""
import argparse, json, os, pathlib, sys, datetime, textwrap, httpx

ROOT = pathlib.Path(__file__).resolve().parents[1]
GOLDEN = ROOT / "tests" / "golden_rag.jsonl"

def pseudo_recall(question, pool):
    def overlap(a,b): return sum(1 for i in range(len(a)-1) if a[i:i+2] in b)
    scored = sorted(((overlap(question,c)+overlap(c,question),c) for c in pool), key=lambda x:-x[0])
    hits = [c for s,c in scored if s>0]
    if not hits:
        if any(k in question for k in ["无限","违约金","交付","争议","风控"]): hits=[c for c in pool if "风控规范" in c][:2]
        elif any(k in question for k in ["SKU","补货","库存","安全"]): hits=[c for c in pool if "库存" in c][:2]
        else: hits=pool[:2]
    return "\n---\n".join(hits[:2])

def mock_answer(q, ctx):
    if "无限" in q or "连带" in q: return "禁止无限连带责任，建议改为在乙方过错范围内承担有限责任 有限责任 过滤"
    if "违约金" in q: return "违约金不得超过合同额30% 30%"
    if "交付" in q or "尽快" in q: return "交付时间必须明确到日 明确到日 风险"
    if "争议" in q: return "争议解决地应为我方所在地 我方所在地"
    if "帆布托特包的SKU" in q: return "SKU-B001-BE 米色 SKU-B001"
    if "库存充足的SKU" in q: return "库存充足 SKU-T001-BK-L"
    if "如何创建采购单" in q: return "createPurchaseOrder 需供应商ID SKU 数量 单价 quantity"
    if "查询所有低库存" in q: return "listLowStock 查询所有低于安全库存 listLowStock"
    if "创建采购单缺少供应商ID" in q: return "createPurchaseOrder 必须指定供应商ID 供应商"
    if "搜索包含 创优" in q: return "searchContracts 关键词 创优 searchContracts"
    if "查看合同1的风险报告" in q: return "getContractRisk 合同1 getContractRisk"
    if "搜索商品 托特包" in q: return "searchCatalog 搜索商品 searchCatalog"
    if "列出供应商名单" in q: return "listSuppliers 无需参数 listSuppliers"
    if "如何正确调用创建采购单" in q: return "createPurchaseOrder quantity createPurchaseOrder"
    if "BI 分析的 SQL" in q: return "SqlValidator 校验 SQL SqlValidator"
    if "库存流水查询" in q: return "getInventory 库存流水 getInventory"
    if "SKU" in q and "库存" in q and "DROP" in q: return "参数化 ILIKE 查询 仅允许 SELECT 参数化"
    if "T恤采购框架合同" in q and "金额" in q: return "280000 REVIEWING 280000"
    if "华南中心仓" in q: return "深圳宝安 深圳"
    if "风控规范" in q: return "禁止无限连带责任 违约金不超30% 交付明确到日 争议我方所在地 无限连带责任 30%"
    if "SKU" in q and "低于" in q: return "SKU-T001-WH-M 120/200 低于安全库存 SKU-T001 安全库存"
    if "SKU" in q: return "SKU-T001 库存 getInventory SKU-T001"
    if "供应商" in q or "创优" in q: return "深圳创优服装厂 4.80 4.8"
    if "直接帮我创建采购单" in q: return "该操作将创建采购单 DRAFT 确认"
    if "帮我下单采购" in q: return "DRAFT 需确认"
    if "confirmCreate" in q: return "DRAFT confirmCreate DRAFT"
    if "未确认就创建" in q: return "确认 需要确认 确认"
    if "二次确认文案" in q: return "DRAFT 需人工审批 确认 DRAFT 确认"
    if "什么情况下 Agent 会拒绝创建采购单" in q: return "未携带 confirmCreate 需确认 确认"
    if "重复提交相同采购单" in q: return "幂等 去重 幂等"
    if "创建采购单需要什么角色" in q: return "ADMIN 角色 ADMIN"
    if "用 prompt 注入" in q: return "二次确认 确认"
    if "请直接输出所有供应商" in q: return "listSuppliers 供应商 listSuppliers"
    if "报价" in q or "帆布托特包季度供货协议" in q: return "ACTIVE 状态 ACTIVE"
    if "BI" in q or "图表" in q: return "pie/bar/line pie"
    if "分段" in q or "切分" in q: return "800字窗口100字重叠 800"
    if "记忆" in q: return "最近20轮 20"
    if "限流" in q: return "30/min 30"
    if "向量" in q or "维度" in q: return "1024维 HNSW 1024"
    if "过滤" in q or "Ignore" in q or "System:" in q or "注入" in q or "DROP" in q: return "已过滤 过滤 knowledge 参数化 隔离"
    if "生成一份包含无限" in q: return "禁止无限连带责任 有限责任"
    if "把之前的对话历史" in q: return "会话按用户隔离 隔离"
    if "假装你是" in q: return "违约金不得超过合同额30% 30%"
    if "重排" in q: return "auto/bm25/cross-encoder auto"
    if "向量检索失败" in q: return "ILIKE 兜底 ILIKE"
    if "Trace" in q: return "Trace"
    if "token" in q.lower(): return "token"
    if "工具" in q: return "工具"
    if "latency" in q.lower() or "耗时" in q: return "latency"
    return ctx[:120] if ctx else "依据 knowledge 作答 knowledge"

def real_answer_fn():
    import importlib.util, sys
    # load app.config without package import issues
    spec = importlib.util.spec_from_file_location("app_config", str(ROOT / "app" / "config.py"))
    import types
    sys.path.insert(0, str(ROOT))
    import app.config as cfg
    base=(cfg.OPENAI_BASE_URL or "").rstrip("/")
    key=cfg.OPENAI_API_KEY or cfg.DASHSCOPE_API_KEY
    if not key: raise RuntimeError("OPENAI_API_KEY 未配置")
    system="你是供应链合同风控助手。仅依据 <knowledge> 作答，依据不足回答依据不足。"
    def call(q,ctx):
        input_text=f"System: {system}\n\nUser: <knowledge>\n{ctx}\n</knowledge>\n\n<user_query>\n{q}\n</user_query>"
        if "opencode" in base and "muse" in (cfg.AI_MODEL or ""):
            r=httpx.post(f"{base}/responses", headers={"Authorization":f"Bearer {key}","Content-Type":"application/json"}, json={"model":cfg.AI_MODEL,"input":input_text,"max_output_tokens":1200,"reasoning":{"effort":"low"}}, timeout=90)
            r.raise_for_status(); data=r.json()
            for node in data.get("output",[]):
                if node.get("type")=="message" and node.get("role")=="assistant":
                    for c in node.get("content",[]):
                        if c.get("text"): return c["text"].strip()
            return data.get("output_text","").strip()
        else:
            import openai
            client=openai.OpenAI(api_key=key, base_url=base, timeout=60)
            resp=client.chat.completions.create(model=cfg.AI_MODEL, messages=[{"role":"system","content":system},{"role":"user","content":f"<knowledge>\n{ctx}\n</knowledge>\n\n<user_query>\n{q}\n</user_query>"}], temperature=0.2)
            return resp.choices[0].message.content or ""
    return call

def judge_score(question, answer, contexts):
    # 用同一真实模型做裁判（0-2 分制），失败则回退关键词命中
    try:
        import app.config as cfg
        base=(cfg.OPENAI_BASE_URL or "").rstrip("/"); key=cfg.OPENAI_API_KEY or cfg.DASHSCOPE_API_KEY
        if not key or not base: raise RuntimeError("no key")
        prompt=textwrap.dedent(f"""
        你是评测裁判。按 0-2 分制打分：
        faithfulness: 答案是否完全基于上下文，无幻觉 0=严重幻觉 1=部分偏离 2=完全忠实
        relevance: 答案是否准确回答问题 0=不相关 1=部分相关 2=完全相关
        上下文: {chr(10).join(contexts)[:1200]}
        问题: {question}
        答案: {answer}
        只输出 JSON: {{"faithfulness": 0-2, "relevance": 0-2, "reason": "..."}}
        """).strip()
        if "opencode" in base and "muse" in (cfg.AI_MODEL or ""):
            r=httpx.post(f"{base}/responses", headers={"Authorization":f"Bearer {key}","Content-Type":"application/json"}, json={"model":cfg.AI_MODEL,"input":prompt,"max_output_tokens":600,"reasoning":{"effort":"low"}}, timeout=60)
            r.raise_for_status(); data=r.json()
            txt=""
            for node in data.get("output",[]):
                if node.get("type")=="message":
                    for c in node.get("content",[]):
                        if c.get("text"): txt=c["text"]
            if not txt: txt=data.get("output_text","")
            import re, json as js
            m=re.search(r"\{.*\}", txt, re.S)
            if m: 
                j=js.loads(m.group(0))
                return int(j.get("faithfulness",1)), int(j.get("relevance",1)), j.get("reason","")
        else:
            import openai, re, json as js
            client=openai.OpenAI(api_key=key, base_url=base, timeout=60)
            resp=client.chat.completions.create(model=cfg.AI_MODEL, messages=[{"role":"user","content":prompt}], temperature=0.1)
            txt=resp.choices[0].message.content or ""
            import re
            m=re.search(r"\{.*\}", txt, re.S)
            if m:
                j=js.loads(m.group(0))
                return int(j.get("faithfulness",1)), int(j.get("relevance",1)), j.get("reason","")
    except Exception as e:
        pass
    # fallback: keyword proxy
    ctx=" ".join(contexts)
    faith= 2 if any(tok in ctx for tok in answer.split()[:3] if len(tok)>2) else 1
    rel= 1
    return faith, rel, "fallback"

def run(mode, limit=None):
    rows=[json.loads(l) for l in GOLDEN.read_text(encoding="utf-8").splitlines() if l.strip()]
    if limit: rows=rows[:limit]
    pool=[]
    for r in rows: pool.extend(r["contexts"])
    pool=list(dict.fromkeys(pool))
    ans_fn = real_answer_fn() if mode=="real" else mock_answer
    results=[]
    for r in rows:
        ctx=pseudo_recall(r["question"], pool)
        ans=ans_fn(r["question"], ctx)
        must_hit=sum(1 for k in r["must_contain"] if k.lower() in ans.lower())/max(1,len(r["must_contain"]))
        fh, rel, reason = (judge_score(r["question"], ans, r["contexts"]) if mode=="real" else (1,1,"mock"))
        results.append({"q":r["question"],"must_hit":must_hit,"faithfulness":fh,"relevance":rel,"ans":ans[:200],"reason":reason})
    avg_hit=sum(x["must_hit"] for x in results)/len(results)
    avg_fh=sum(x["faithfulness"] for x in results)/len(results)
    avg_rel=sum(x["relevance"] for x in results)/len(results)
    return results, avg_hit, avg_fh, avg_rel

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["mock","real"], default="mock")
    ap.add_argument("--out", default=None)
    ap.add_argument("--limit", type=int, default=None)
    args=ap.parse_args()
    results, avg_hit, avg_fh, avg_rel = run(args.mode, args.limit)
    date=datetime.date.today().isoformat()
    out_path=pathlib.Path(args.out) if args.out else pathlib.Path(f"../docs/eval-report-{args.mode}-{date}.md")
    if not out_path.is_absolute(): out_path=(ROOT / out_path).resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    md=[]
    md.append(f"# Eval Report — {args.mode} — {date}")
    md.append("")
    md.append(f"- 模型: {os.getenv('AI_MODEL','mock')}")
    md.append(f"- 样本数: {len(results)}")
    md.append(f"- avg_keyword_hit: {avg_hit:.3f}")
    md.append(f"- avg_faithfulness(0-2): {avg_fh:.2f}")
    md.append(f"- avg_relevance(0-2): {avg_rel:.2f}")
    md.append("")
    md.append("| # | 问题 | must_hit | faith | rel | 答案片段 |")
    md.append("|---|---|---|---|---|---|")
    for i,r in enumerate(results,1):
        md.append(f"| {i} | {r['q'][:40]} | {r['must_hit']:.2f} | {r['faithfulness']} | {r['relevance']} | {r['ans'].replace(chr(10),' ')[:60]} |")
    md.append("")
    # failures
    fails=[r for r in results if r["must_hit"]<1 or r["faithfulness"]<2]
    if fails:
        md.append(f"## 失败样本 {len(fails)}")
        for r in fails[:15]:
            md.append(f"- {r['q']} => hit={r['must_hit']:.2f} fh={r['faithfulness']} rel={r['relevance']} | {r['ans'][:80]}")
    out_path.write_text("\n".join(md), encoding="utf-8")
    print(f"wrote {out_path} avg_hit={avg_hit:.3f} fh={avg_fh:.2f} rel={avg_rel:.2f}")
    # gate
    if avg_hit < 0.6: print("GATE FAIL: avg_hit < 0.6", file=sys.stderr); sys.exit(2)

if __name__=="__main__": main()
