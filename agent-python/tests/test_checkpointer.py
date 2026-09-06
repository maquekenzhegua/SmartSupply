"""checkpointing 工厂单测：缺省 MemorySaver / Postgres 不可达时诚实降级 / 日志脱敏。

真实 Postgres 路径由部署冒烟覆盖（compose 注入 CHECKPOINT_URI + HITL 挂起→重启→恢复演练），
单测不依赖容器，CI 离线可跑。注意 get_checkpointer 是进程内单例，用例结束必须 aclose() 复位，
避免污染同进程的其它用例。
"""
import asyncio
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from langgraph.checkpoint.memory import MemorySaver

from app import checkpointing, config


def test_safe_uri_masks_password():
    masked = checkpointing._safe_uri("postgresql://dev:super-secret-pw@postgres:5432/smartsupply")
    assert "super-secret-pw" not in masked
    assert "dev:***@postgres:5432/smartsupply" in masked
    # 无密码 URI 原样保留
    plain = "postgresql://postgres:5432/smartsupply"
    assert checkpointing._safe_uri(plain) == plain


def test_default_memory_saver_without_uri(monkeypatch):
    monkeypatch.setattr(config, "CHECKPOINT_URI", "")
    try:
        saver = asyncio.run(checkpointing.get_checkpointer())
        assert isinstance(saver, MemorySaver)
    finally:
        asyncio.run(checkpointing.aclose())


def test_unreachable_postgres_falls_back_honestly(monkeypatch):
    """连接拒绝端口：验证"降级可用 + 显式告警"契约——不启动崩溃、不静默伪装成功。"""
    monkeypatch.setattr(config, "CHECKPOINT_URI", "postgresql://dev:pw@127.0.0.1:1/nope")
    try:
        saver = asyncio.run(checkpointing.get_checkpointer())
        assert isinstance(saver, MemorySaver)
        assert checkpointing._pool is None  # 连接池已释放，未悬挂后台重连任务
    finally:
        asyncio.run(checkpointing.aclose())
