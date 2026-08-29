@echo off
REM 启动 Python LangGraph 边车，复用 D:\conda_envs\ai-backend
REM Java 侧需设置 AGENT_PYTHON_ENABLED=true
set AGENT_PYTHON_URL=http://127.0.0.1:8001
D:\conda_envs\ai-backend\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8001 --reload
