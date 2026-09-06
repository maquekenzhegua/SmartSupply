"""HITL 挂起→进程重启→恢复 演练：PostgresCheckpointer 真验收。

目的：证明 interrupt 挂起的写闸门状态持久化在 Postgres 中——挂起进程完全退出后，
新进程凭 CHECKPOINT_URI + thread_id 仍能恢复执行。这是 MemorySaver（进程内）做不到的。

用法（分两次进程执行，模拟重启）：
  python scripts/hitl_restart_drill.py suspend              # 进程1：规划写操作 → interrupt 挂起 → 退出
  python scripts/hitl_restart_drill.py resume --approve     # 进程2：批准 → 真实创建采购单（需 Java 在线）
  python scripts/hitl_restart_drill.py resume --reject      # 进程2：拒绝 → 如实取消，不产生任何变更

说明：
- 规划器打桩（固定规划 create_purchase_order）：本演练验证的是 checkpointer 持久化与
  恢复链路本身，不依赖 LLM 的随机性；写操作走真实 Java 接口（ADMIN 幂等闸门全在）。
- 未配置 CHECKPOINT_URI 时本演练必然失败（no_pending_interrupt）——这正是对比
  MemorySaver 与 Postgres 持久化的验收标准。
"""
import argparse
import asyncio
import os
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

THREAD_FILE = ROOT / ".hitl_drill_thread"
WRITE_ARGS = {"supplier_id": 1, "sku_code": "SKU-T001-WH-M", "quantity": 100, "unit_price": 9.9}


def _load_env() -> None:
    env_path = ROOT.parent / ".env"
    if not env_path.exists():
        return
    for line in env_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        if key.strip() and key.strip() not in os.environ:
            os.environ[key.strip()] = value.strip().strip('"')


async def _suspend() -> int:
    from app import graph as G

    # 规划器打桩：无论 LLM 如何，固定规划一次写调用（其余路径全真实）
    async def fake_chat(messages, tools=None):
        if tools and not any("已有工具结果" in (m.get("content") or "") for m in messages[-1:]):
            return {"text": None,
                    "tool_calls": [{"name": "create_purchase_order", "arguments": dict(WRITE_ARGS)}],
                    "provider": "drill-stub"}
        return {"text": "[drill] 采购单已创建", "tool_calls": None, "provider": "drill-stub"}

    G.chat = fake_chat
    result = await G.run_reasoning_with_trace(
        [{"role": "user", "content": "下采购单"}], agent_type="general", session_id="hitl-drill")
    tid = result.get("thread_id") or ""
    if not (result.get("interrupted") and tid):
        print(f"FAIL: 未按预期挂起: interrupted={result.get('interrupted')}")
        return 1
    THREAD_FILE.write_text(tid, encoding="utf-8")
    print(f"已挂起等待人工批准。thread_id={tid}（进程退出后凭 Postgres checkpoint 恢复）")
    print(f"待批准动作: {result.get('confirm')}")
    return 0


async def _resume(approve: bool) -> int:
    from app import graph as G

    tid = THREAD_FILE.read_text(encoding="utf-8").strip()
    if not tid:
        print("FAIL: 找不到挂起的 thread_id（先执行 suspend）")
        return 1
    result = await G.resume_reasoning(tid, {"approved": approve}, agent_type="general",
                                      session_id="hitl-drill")
    if result.get("degraded"):
        print(f"FAIL: 恢复失败（挂起状态丢失？）: {result.get('degrade_reason')}")
        return 2
    writes = [t for t in (result.get("tool_results") or []) if t.get("tool") == "create_purchase_order"]
    print(f"恢复成功。回复: {result.get('reply', '')[:120]}")
    print(f"写操作结果: {writes}")
    return 0 if writes else 1


if __name__ == "__main__":
    if sys.platform == "win32":
        # Windows 默认 ProactorEventLoop 与 psycopg 异步模式不兼容（同 gen_langfuse_traces）
        import asyncio
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("suspend", help="发起写意图并挂起后退出")
    r = sub.add_parser("resume", help="恢复挂起的写闸门")
    g = r.add_mutually_exclusive_group(required=True)
    g.add_argument("--approve", action="store_true", help="批准执行（真实调用 Java）")
    g.add_argument("--reject", action="store_true", help="拒绝执行")
    args = parser.parse_args()
    _load_env()
    sys.exit(asyncio.run(_suspend() if args.cmd == "suspend" else _resume(args.approve)))
