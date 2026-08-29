import os

JAVA_API_BASE = os.getenv("JAVA_API_BASE", "http://localhost:8080")
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
DASHSCOPE_API_KEY = os.getenv("DASHSCOPE_API_KEY", "")
OPENAI_BASE_URL = os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1")
AI_MODEL = os.getenv("AI_MODEL", "gpt-4o-mini")
LLM_MODE = "mock" if not (OPENAI_API_KEY or DASHSCOPE_API_KEY) else "real"
# 复用 Java 侧的 JWT 配置时，可选传透
JAVA_JWT_TOKEN = os.getenv("JAVA_JWT_TOKEN", "")
