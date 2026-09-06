"""配置层：全部通过函数式惰性读取环境变量（PEP 562 模块级 __getattr__）。

此前 import 时固化 LLM_MODE 等常量，导致：测试无法用环境变量切态、运行时改配置不生效。
现在每次访问属性都读最新 env；测试里 monkeypatch.setattr(config, "X", v) 依旧可用
（模块真实属性会遮蔽 __getattr__）。
"""
import os


def _get(name: str, default: str = "") -> str:
    return os.getenv(name, default)


def _getattr_impl(name: str):
    if name == "JAVA_API_BASE":
        return _get("JAVA_API_BASE", "http://localhost:8080")
    if name == "OPENAI_API_KEY":
        return _get("OPENAI_API_KEY", "")
    if name == "DASHSCOPE_API_KEY":
        return _get("DASHSCOPE_API_KEY", "")
    if name == "OPENAI_BASE_URL":
        return _get("OPENAI_BASE_URL", "https://api.openai.com/v1")
    if name == "AI_MODEL":
        return _get("AI_MODEL", "gpt-4o-mini")
    if name == "LLM_MODE":
        # 未配置任何 Key 即为离线 mock 态；每次访问即时判定，env 变更即刻生效
        return "mock" if not (_get("OPENAI_API_KEY") or _get("DASHSCOPE_API_KEY")) else "real"
    # 复用 Java 侧的 JWT 配置时，可选透传（手工指定服务账号 token）
    if name == "JAVA_JWT_TOKEN":
        return _get("JAVA_JWT_TOKEN", "")
    # 兜底服务账号：既无用户 JWT 透传（Java /chat 默认会透传真实用户 token）也未配
    # JAVA_JWT_TOKEN 时，边车用此账号自动登录换取 token；失效自动刷新。仅演示/开发用途。
    if name == "SIDECAR_USERNAME":
        return _get("SIDECAR_USERNAME", "admin")
    if name == "SIDECAR_PASSWORD":
        return _get("SIDECAR_PASSWORD", "admin123")
    # 边车自身 API 鉴权：配置后 /api/reason* 要求 X-Api-Key 匹配（Java↔边车内网共享密钥）
    if name == "SIDECAR_API_KEY":
        return _get("SIDECAR_API_KEY", "")
    # LLM 调用韧性：重试次数与退避基数（秒），仅对超时/429/5xx 生效
    if name == "LLM_MAX_ATTEMPTS":
        return int(_get("LLM_MAX_ATTEMPTS", "3"))
    if name == "LLM_BACKOFF_SECONDS":
        return float(_get("LLM_BACKOFF_SECONDS", "0.8"))
    if name == "LLM_TIMEOUT_SECONDS":
        return float(_get("LLM_TIMEOUT_SECONDS", "240"))
    # Langfuse 可观测性：不配置即整体禁用（observability 返回 no-op，零依赖零开销）
    if name == "LANGFUSE_PUBLIC_KEY":
        return _get("LANGFUSE_PUBLIC_KEY", "")
    if name == "LANGFUSE_SECRET_KEY":
        return _get("LANGFUSE_SECRET_KEY", "")
    if name == "LANGFUSE_HOST":
        return _get("LANGFUSE_HOST", "http://localhost:3000")
    # checkpointer 持久化：为空用进程内 MemorySaver；配置 Postgres URI 后 interrupt 挂起
    # 状态落库，边车重启/多实例仍可恢复写闸门（checkpointing 模块负责建表与降级）
    if name == "CHECKPOINT_URI":
        return _get("CHECKPOINT_URI", "")
    raise AttributeError(f"module 'app.config' has no attribute '{name}'")


def __getattr__(name: str):
    return _getattr_impl(name)
