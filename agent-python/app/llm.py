"""LLM 适配：muse 专属（/responses）+ Mock 兜底。"""
from typing import List, Dict
import os, json
from . import config
import httpx

def _mock_reply(messages: List[Dict[str, str]]) -> str:
    last = messages[-1]["content"] if messages else ""
    lower = last.lower()
    if any(k in lower for k in ["合同", "风控", "风险"]):
        return (
            "[Python Mock] LangGraph 深度推理完成（合同风控分支）：\n"
            "1) 抽取：金额/账期/违约金/交付时间\n"
            "2) RAG 召回：禁止无限连带责任、违约金不超30%\n"
            "3) 风险：检测到‘无限连带责任’属高风险，建议改为‘在乙方过错范围内承担有限责任’\n"
        )
    if any(k in lower for k in ["库存", "补货", "采购", "sku"]):
        return (
            "[Python Mock] 深度推理完成（补货规划分支）：\n"
            "- 调用 listLowStock 发现 SKU-T001-WH-M 低于安全库存\n"
            "- 建议向 深圳创优服装厂 采购500件\n"
        )
    return f"[Python Mock] 已收到：{last}\n这是 Mock 推理，配置真实 muse 后由 {config.AI_MODEL} 驱动。"

async def _muse_responses(messages: List[Dict[str, str]]) -> str:
    # 将 messages 拼为单条 input（Responses 协议）
    parts = []
    for m in messages:
        role = m.get("role","user")
        content = m.get("content","")
        parts.append(f"{role}: {content}")
    input_text = "\n\n".join(parts).strip()
    base = (config.OPENAI_BASE_URL or "https://opencode.ai/zen/go/v1").rstrip("/")
    key = config.OPENAI_API_KEY or config.DASHSCOPE_API_KEY
    model = config.AI_MODEL or "muse-spark-1.2-contributor"
    payload = {"model": model, "input": input_text, "max_output_tokens": 2500, "reasoning": {"effort": "low"}}
    async with httpx.AsyncClient(timeout=90) as client:
        r = await client.post(f"{base}/responses", headers={"Authorization": f"Bearer {key}", "Content-Type":"application/json"}, json=payload)
        r.raise_for_status()
        data = r.json()
        for node in data.get("output", []):
            if node.get("type")=="message" and node.get("role")=="assistant":
                for c in node.get("content", []):
                    if c.get("type")=="output_text" and c.get("text"):
                        return c["text"].strip()
        # 兜底
        if data.get("output_text"):
            return data["output_text"].strip()
        if data.get("status")=="incomplete":
            return "[muse 推理截断，请重试]"
        return "[muse 无输出]"

async def chat(messages: List[Dict[str, str]]) -> str:
    if config.LLM_MODE == "mock":
        return _mock_reply(messages)
    # muse/opencode 走 Responses，否则走 OpenAI chat
    base = (config.OPENAI_BASE_URL or "")
    if "opencode" in base and "muse" in (config.AI_MODEL or ""):
        try:
            return await _muse_responses(messages)
        except Exception as e:
            return f"[muse 调用失败，降级 Mock：{e}]\n" + _mock_reply(messages)
    try:
        import openai  # type: ignore
        client = openai.AsyncOpenAI(api_key=config.OPENAI_API_KEY or config.DASHSCOPE_API_KEY, base_url=config.OPENAI_BASE_URL)
        resp = await client.chat.completions.create(model=config.AI_MODEL, messages=messages, temperature=0.3)
        return resp.choices[0].message.content or ""
    except Exception as e:
        return f"[LLM 调用失败，降级为 Mock：{e}]\n" + _mock_reply(messages)
