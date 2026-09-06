"""Langfuse 冒烟脚本：本地起 langfuse 后一键生成演示 trace。

前置：
  docker compose --profile obs up -d langfuse   # 无头初始化 org/project/key（幂等）
  .env 含 LANGFUSE_PUBLIC_KEY/SECRET_KEY/HOST（headless 模式为固定演示 key）

用法：
  python scripts/gen_langfuse_traces.py          # mock 模式：离线可跑，6 条 trace
  python scripts/gen_langfuse_traces.py --real   # 真实 LLM：读 .env 的 OPENAI_*，含写闸门挂起 trace

产出：Langfuse UI（http://localhost:3000）可见每次运行的完整 trace
（planner/工具/reasoner 埋点 + token 用量 + 延迟）；写意图问题在 --real 下会挂起等人工批准。
"""
import argparse
import asyncio
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

MOCK_QUESTIONS = [
    "查一下 SKU-T001-WH-M 的库存情况",
    "有哪些低库存的 SKU 需要补货？",
    "供应商 SUP-001 的基本信息和供货范围",
    "总结一下最近的库存流水",
    "帮我查一下合规管理制度里对留库天数的要求",
    "对比一下仓库 WH-M 和 WH-N 的库存结构",
]


def _load_env() -> None:
    """轻量读取 .env（不覆盖已有环境变量），免去 docker 依赖，本地裸跑边车时同样生效。"""
    env_path = ROOT.parent / ".env"
    if not env_path.exists():
        return
    for line in env_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key, value = key.strip(), value.strip().strip('"')
        if key and key not in os.environ:
            os.environ[key] = value


async def main(real: bool) -> int:
    from app import config
    from app.graph import run_reasoning_with_trace
    from app import observability as obs

    if not obs.enabled():
        print("Langfuse 未启用：请确认 .env 中 LANGFUSE_PUBLIC_KEY/SECRET_KEY 已配置且服务已启动")
        return 2
    print(f"Langfuse: {config.LANGFUSE_HOST} | LLM 模式: {config.LLM_MODE} | "
          f"checkpointer: {'Postgres' if config.CHECKPOINT_URI else 'MemorySaver(进程内)'}")

    session_id = "langfuse-smoke"
    for q in MOCK_QUESTIONS:
        result = await run_reasoning_with_trace(
            [{"role": "user", "content": q}], agent_type="general", session_id=session_id)
        tools = [t.get("tool") for t in (result.get("tool_results") or [])]
        print(f"- {q[:24]:<26} degraded={result.get('degraded')} tools={tools} "
              f"interrupted={result.get('interrupted')}")

    if real and config.LLM_MODE == "real":
        real_questions = [
            ("通用", [{"role": "user", "content": "用一句话说明这个系统是做什么的"}]),
            ("写意图", [{"role": "user", "content":
                        "帮我给供应商 1 下一个采购单：SKU-T001-WH-M，数量 100，单价 9.9"}]),
        ]
        for tag, msgs in real_questions:
            result = await run_reasoning_with_trace(msgs, agent_type="general",
                                                    session_id=f"{session_id}-real")
            print(f"- [real/{tag}] interrupted={result.get('interrupted')} "
                  f"thread_id={result.get('thread_id')} degraded={result.get('degraded')}")
    elif real:
        print("（--real 需要 OPENAI_API_KEY/DASHSCOPE_API_KEY，当前为 mock 模式，已跳过真实调用）")

    obs.flush()
    print(f"完成。打开 {config.LANGFUSE_HOST} → 项目 smartsupply-agent → Traces 查看")
    return 0


if __name__ == "__main__":
    import os
    # Windows 默认 ProactorEventLoop 与 psycopg 异步模式不兼容，必须用 Selector
    # （uvicorn 在 Windows 上同样如此处理；Linux/macOS 无此要求）
    if sys.platform == "win32":
        import asyncio
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--real", action="store_true", help="额外发起真实 LLM 调用（含写闸门演示）")
    args = parser.parse_args()
    _load_env()
    sys.exit(asyncio.run(main(args.real)))
