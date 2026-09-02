# agent-python — LangGraph 深度推理边车

大厂主流架构：**Java 做业务编排 + Python LangGraph 做深度推理**。

- Java 负责：鉴权、事务、Tool 执行（查库存/建采购单/搜合同）、Redis 记忆、RAG 向量库。
- Python 负责：复杂规划与反思、多步推理、状态图编排，必要时回调 Java 的 Tool API。

Python 环境需自行创建（建议 `conda create -n ai-backend python=3.11`），已依赖 langchain 1.3.14 / langgraph 1.2.10 / fastapi 0.139.2 / dashscope/openai 等），见 `requirements.txt`。

## 启动
```bash
# 激活 env（Git Bash 用 conda）
conda activate ai-backend  # 或你创建的环境名
# 或直接用该 env 的 python
python -m uvicorn app.main:app --host 0.0.0.0 --port 8001 --reload
```

## 配置
`app/config.py` 从环境变量读：
- `OPENAI_API_KEY / DASHSCOPE_API_KEY / OPENAI_BASE_URL / AI_MODEL` 兼容 Java 侧配置
- `JAVA_API_BASE=http://localhost:8080` 用于回调 Java 的 Tool（带 JWT 可选）
- 无 Key 时 `LLM_MODE=mock` 走确定性 Mock 推理，保证可演示

## 接口
- `POST /api/reason` 深度推理（LangGraph 状态图）
- `POST /api/rag/recall` 召回测试
- `GET /health`

Java 侧通过 `POST http://localhost:8001/api/reason` 委托深度推理，`AgentController` 在 `agentType=deep` 时转发。
