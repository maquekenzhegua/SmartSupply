#!/bin/bash
# Langfuse 自部署所需独立数据库（仅首次初始化 postgres 卷时执行）。
# 已存在的旧卷不重跑 init：手动执行 psql 后再启动 langfuse 服务即可。
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
SELECT 'CREATE DATABASE langfuse' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'langfuse')\gexec
EOSQL
