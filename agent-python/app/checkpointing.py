"""checkpointer 工厂：缺省进程内 MemorySaver；配置 CHECKPOINT_URI 时持久化到 Postgres。

为何持久化：interrupt 挂起的 run 依赖 checkpoint 存活。MemorySaver 随进程消亡，
边车重启/多实例部署后，待人工批准的写闸门状态会丢失（调用方恢复时报
no_pending_interrupt）。生产/compose 部署配 CHECKPOINT_URI 指向 Postgres 即可，
checkpoint_* 表由 setup() 幂等建表，与业务库共用实例互不干扰。

降级口径与 IdempotencyService 一致：Postgres 不可达时降级为进程内 MemorySaver
（可用性优先），但必须大声告警——此时重启会丢挂起状态，不能再静默。
自愈：降级不再是终身性的——带冷却的后台重试（默认 60s），Postgres 恢复可达后
自动切回持久化实现并递增 generation（graph 层据此重建已编译图）；
/health 经 status() 上报实际实现而非仅配置。
"""
import asyncio
import logging
import re
import time
from typing import Any, Tuple, Optional

from langgraph.checkpoint.memory import MemorySaver

from . import config

log = logging.getLogger(__name__)

_saver: Any = None
_pool: Any = None  # 持有连接池引用，shutdown 时释放
_degraded: bool = False   # 当前是否处于降级兜底（MemorySaver 替身）
_last_attempt: float = 0.0
_retry_cooldown_s: float = 60.0
_gen: int = 0             # 后端实现切换代数（graph 层据此重建已编译图）
_reconnect_lock = asyncio.Lock()


def _safe_uri(uri: str) -> str:
    """日志用：掩去连接串中的密码段。"""
    return re.sub(r"(://[^:/@]+:)[^@]+(@)", r"\1***\2", uri or "")


def generation() -> int:
    """当前 checkpointer 实现代数：图编译时绑定实现，实现切换必须重建图。"""
    return _gen


def status() -> dict:
    """/health 用：上报实际实现与降级状态（此前只报配置不报实际，运维无法发现降级）。"""
    return {"uri_configured": bool(config.CHECKPOINT_URI),
            "backend": "postgres" if (_saver is not None and not _degraded and config.CHECKPOINT_URI) else "memory",
            "degraded": _degraded}


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
                    "边车重启后挂起的写确认将丢失；%ss 后自动重试", e, _retry_cooldown_s)
        return MemorySaver(), None


async def get_checkpointer():
    """进程内单例；首次调用决定实现，但降级不再终身——带冷却自愈重试。

    冷却期内继续用降级实现（防止 PG 持续不可达时每个请求都撞一次连接超时）；
    冷却结束后的下一个请求尝试重建，成功即切回持久化并递增 generation。
    """
    global _saver, _pool, _degraded, _last_attempt, _gen
    uri = config.CHECKPOINT_URI
    if not uri:
        if _saver is None:
            _saver = MemorySaver()
            _gen = 1
        return _saver
    if _saver is not None and not _degraded:
        return _saver
    if _saver is not None and _degraded and (time.monotonic() - _last_attempt) < _retry_cooldown_s:
        return _saver
    async with _reconnect_lock:  # 双检：并发请求只允许一个重建者
        if _saver is not None and not _degraded:
            return _saver
        if _saver is not None and _degraded and (time.monotonic() - _last_attempt) < _retry_cooldown_s:
            return _saver
        _last_attempt = time.monotonic()
        saver, pool = await _build(uri)
        was_degraded = _saver is not None and _degraded
        _saver, _pool = saver, (pool if pool is not None else _pool)
        _degraded = pool is None
        _gen += 1
        if was_degraded and not _degraded:
            log.info("checkpointer: 自愈成功，已从 MemorySaver 切回 Postgres 持久化（gen=%s）", _gen)
    return _saver


async def aclose() -> None:
    """优雅关闭连接池（FastAPI shutdown 调用）；测试切换配置时也可重置单例。"""
    global _saver, _pool, _degraded, _gen
    if _pool is not None:
        try:
            await _pool.close()
        except Exception:
            pass
    _pool = None
    _saver = None
    _degraded = False
    _last_attempt = 0.0
    _gen = 0
