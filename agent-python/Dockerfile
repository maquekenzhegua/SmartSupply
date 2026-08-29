FROM python:3.11-slim
WORKDIR /app
ENV PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
# 冒烟：校验关键依赖可导入，构建期即暴露缺依赖问题
RUN python -c "import fastapi, uvicorn, pydantic, httpx, openai, langgraph; print('deps ok')"
COPY app ./app
EXPOSE 8001
CMD ["uvicorn","app.main:app","--host","0.0.0.0","--port","8001"]
