"""checkpointer 工厂：缺省进程内 MemorySaver；配置 CHECKPOINT_URI 时持久化到 Postgres。

为何持久化：interrupt 挂起的 run 依赖 checkpoint 存活。MemorySaver 随进程消亡，
边车重启/多实例部署后，待人工批准的写闸门状态会丢失（调用方恢复时报
no_pending_interrupt）。生产/compose 部署配 CHECKPOINT_URI 指向 Postgres 即可，
checkpoint_* 表由 setup() 幂等建表，与业务库共用实例互不干扰。

降级口径与 IdempotencyService 一致：Postgres 不可达时降级为进程内 MemorySaver
（可用性优先），但必须大声告警——此时重启会丢挂起状态，不能再静默。
"""
import asyncio
import logging
import re
from typing import Any, Tuple, Optional

from langgraph.checkpoint.memory import MemorySaver

from . import config

log = logging.getLogger(__name__)

_saver: Any = None
_pool: Any = None  # 持有连接池引用，shutdown 时释放


def _safe_uri(uri: str) -> str:
    """日志用：掩去连接串中的密码段。"""
    return re.sub(r"(://[^:/@]+:)[^@]+(@)", r"\1***\2", uri or "")


async def _build(uri: str) -> Tuple[Any, Optional[Any]]:
    """按 URI 构建一次 checkpointer，返回 (saver, pool)。不可达时诚实降级 MemorySaver。

    AsyncPostgresSaver 内部 asyncio.Lock 绑定构造时的 event loop，因此构建必须发生在
    运行中的 loop 内（首个请求/首个用例的协程里），不能在模块 import 时进行。
    """
    from psycopg.rows import dict_row
    from psycopg_pool import AsyncConnectionPool
    from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver

    # 连接参数与官方 from_conn_string 对齐：autocommit 必需（setup() 迁移含
    # CREATE INDEX CONCURRENTLY，不能在事务块内跑）；prepare_threshold=0 启用预编译；
    # dict_row 是 saver 查询期望的行工厂。手工建池时漏掉这些只会得到难排查的迁移报错。
    pool = AsyncConnectionPool(
        conninfo=uri, min_size=1, max_size=5, open=False,
        kwargs={"autocommit": True, "prepare_threshold": 0, "row_factory": dict_row})
    try:
        await asyncio.wait_for(pool.open(wait=True, timeout=3.0), timeout=6.0)
        saver = AsyncPostgresSaver(pool)
        # 幂等：首次建 checkpoint_* 表并跑内置迁移，后续启动 no-op
        await asyncio.wait_for(saver.setup(), timeout=30.0)
        log.info("checkpointer: Postgres 持久化已启用 (%s)", _safe_uri(uri))
        return saver, pool
    except Exception as e:
        try:
            await pool.close()
        except Exception:
            pass
        log.warning("checkpointer: Postgres 不可用（%s），降级为进程内 MemorySaver —— "
                    "边车重启后挂起的写确认将丢失", e)
        return MemorySaver(), None


async def get_checkpointer():
    """进程内单例；首次调用决定整个生命周期的实现（单 uvicorn worker 单 loop 假设）。"""
    global _saver, _pool
    if _saver is not None:
        return _saver
    uri = config.CHECKPOINT_URI
    if not uri:
        _saver = MemorySaver()
        return _saver
    _saver, _pool = await _build(uri)
    return _saver


async def aclose() -> None:
    """优雅关闭连接池（FastAPI shutdown 调用）；测试切换配置时也可重置单例。"""
    global _saver, _pool
    if _pool is not None:
        try:
            await _pool.close()
        except Exception:
            pass
    _pool = None
    _saver = None
