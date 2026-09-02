#!/usr/bin/env bash
# 在虚拟机/云服务器里执行（将 <VM_IP> 替换为你的主机 IP）
# PG 账号：用户 dev / 密码 change-me-strong-password / 库 smartsupply
# Redis：无用户名，仅密码 change-me-strong-password（已设 requirepass）
set -e
echo "=== 1/3 拉 pgvector 镜像（约150MB） ==="
docker pull pgvector/pgvector:pg16 || {
  echo "pull 失败请检查虚拟机是否联网，或改用 docker save/load 从 Windows 传镜像"
  exit 1
}
echo "=== 2/3 启动 PG 向量库（与 MySQL 3306、Redis 6379 共存，端口 5432 不冲突） ==="
docker rm -f smartsupply-postgres 2>/dev/null || true
docker run -d --name smartsupply-postgres \
  --restart unless-stopped \
  -p 5432:5432 \
  -v pgdata:/var/lib/postgresql/data \
  -e POSTGRES_DB=smartsupply \
  -e POSTGRES_USER=dev \
  -e POSTGRES_PASSWORD='change-me-strong-password' \
  pgvector/pgvector:pg16
# 等待就绪（注意转义 @）
for i in {1..30}; do docker exec smartsupply-postgres pg_isready -U dev -d smartsupply && break || sleep 2; done
echo "=== 3/3 初始化表（单一事实源=Flyway 迁移目录，需把宿主机 backend/src/main/resources/db/migration 拷到虚拟机 /tmp/migrations） ==="
if [ -d /tmp/migrations ]; then
  # 按文件名顺序执行（V1、V2、V3...），与后端启动时 Flyway 应用的是同一套脚本
  for f in $(ls /tmp/migrations/*.sql | sort -V); do
    echo "--> 应用 $f"
    docker exec -i smartsupply-postgres psql -U dev -d smartsupply < "$f"
  done
  echo "Flyway 迁移目录已导入"
else
  echo "未找到 /tmp/migrations，请先 scp："
  echo "  scp -r backend/src/main/resources/db/migration user@<VM_IP>:/tmp/migrations"
  echo "然后重跑本脚本第 3 步"
fi
echo "=== 放行防火墙 ==="
if command -v firewall-cmd >/dev/null 2>&1; then
  sudo firewall-cmd --permanent --add-port=5432/tcp 2>/dev/null || true
  sudo firewall-cmd --reload 2>/dev/null || true
fi
if command -v ufw >/dev/null 2>&1; then
  sudo ufw allow 5432/tcp 2>/dev/null || true
fi
echo "=== 验证 ==="
docker exec smartsupply-postgres psql -U dev -d smartsupply -c "CREATE EXTENSION IF NOT EXISTS vector; SELECT * FROM pg_extension WHERE extname='vector';"
echo "完成。本地执行：Test-NetConnection <VM_IP> -Port 5432 应为 True（将 <VM_IP> 替换为实际 IP）"
echo "Redis 自检（VM 内）：redis-cli -a 'change-me-strong-password' ping  应返回 PONG"
