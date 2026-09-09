"""Windows 本地启动器：以 Selector 事件循环运行边车。

为什么存在：psycopg_pool 的异步连接池不兼容 Windows 默认的 ProactorEventLoop
（"Psycopg cannot use the 'ProactorEventLoop' to run in async mode"），而 uvicorn
在 Windows 上会强制 Proactor 策略（loop="asyncio" 时）。Checkpointer（interrupt
挂起状态持久化）因此会静默降级为进程内 MemorySaver——功能仍可用，但重启即丢挂起的
写确认。本启动器在事件循环创建前设置 WindowsSelectorEventLoopPolicy，并以 loop="none"
禁止 uvicorn 覆盖策略，让 CHECKPOINT_URI 的 Postgres 持久化真正生效。

Linux/macOS/Docker 不受影响（默认 selector），直接 `uvicorn app.main:app` 即可；
Windows 本地跑通 Postgres checkpointer 请用：python run_sidecar.py
"""
import asyncio
import sys

if sys.platform == "win32":
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

import uvicorn


async def _serve() -> None:
    config = uvicorn.Config("app.main:app", host="0.0.0.0", port=8001, loop="none")
    await uvicorn.Server(config).serve()


if __name__ == "__main__":
    asyncio.run(_serve())
