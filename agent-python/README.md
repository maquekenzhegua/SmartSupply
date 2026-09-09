# agent-python — LangGraph 深度推理边车

FastAPI + LangGraph 的深度推理服务（默认端口 8001），由 Java 编排层在"需要深度推理"时调用。

## API

| 端点 | 说明 |
| --- | --- |
| `GET /health` | 健康与态势：模型路由、预算/死线、checkpointer 实际实现（memory/postgres + 降级状态）、鉴权配置状态 |
| `GET /tools` | 工具清单 |
| `POST /api/reason` | 深度推理（阻塞）：规划→取证→[写闸门 interrupt 挂起]→反思→作答；挂起时返回 `interrupted=true + confirm + threadId`，携 `resume={"approved": bool}` 恢复 |
| `POST /api/reason/stream` | 同上，SSE 事件流（plan/tool/write_tool/reflect/confirm_required/reply_delta/done） |
| `POST /api/chat` | 直连 LLM 单轮（无图） |
| `POST /api/rag/recall` · `/api/rag/rerank` | 召回转发 Java / CPU 重排（双轨：cross-encoder，失败诚实回退词面打分并标 fallback） |
| stdio MCP | `app/mcp_server.py` 把 8 工具按 MCP 开放协议暴露；写工具默认不暴露（MCP 通道无图内 interrupt 人工批准环节） |

鉴权：配置 `SIDECAR_API_KEY` 后全部 `/api/*` 端点要求 `X-Api-Key` 匹配（常数时间比较）；未配置时本地零配置可用并大声告警。

## 运行

Windows 必须用启动器（psycopg 异步池不兼容默认 Proactor 事件循环，直接 uvicorn 会静默降级 MemorySaver）：

```bash
python run_sidecar.py        # http://localhost:8001/health
```

关键环境变量（详见根目录 .env.example）：

- `SIDECAR_API_KEY` / `SIDECAR_USERNAME` / `SIDECAR_PASSWORD`（服务账号 fail-closed，默认空）
- `CHECKPOINT_URI`（Postgres）：interrupt 挂起状态落库，重启/多实例可恢复；Postgres 不可达降级
  MemorySaver 并告警，带 60s 冷却自愈重试，`/health` 可见实际实现
- `MAX_ITERATIONS`（默认 6）/ `RUN_TOKEN_BUDGET`（默认 0=不限）/ `RUN_TIMEOUT_SECONDS`（默认 360）：
  迭代、token 预算与单次运行死线三重上限；SSE 消费方断连即取消图执行
- `AI_MODEL` / `AI_MODEL_FAST`（规划/反思走廉价档）

## 测试与评测

```bash
python -m pytest tests -q -m "not real_llm"     # 离线全量回归
python scripts/llm_judge.py --mode mock --out docs/eval-report-mock.md   # harness 自检
python scripts/llm_judge.py --mode real --out docs/eval-report-real.md   # 真实模型评测（需 key）
python scripts/check_gate.py <report.md> --min-hit 0.6                   # 数值门禁
python scripts/compare_eval.py --report <当前.md> --baseline-report <基线.md>  # 回归对比
```
