# TLS/HTTPS 接入指引

背景：JWT 走 `Authorization` 头明文传输，无 TLS 时可被链路窃听。默认编排只监听 80，
TLS 提供三种接入方式，按部署环境选择其一。

## 方式 A：nginx 容器直接终结（本仓库提供模板）

```bash
mkdir -p deploy/certs
# 自签演示证书（正式环境请用 certbot/商业证书）
openssl req -x509 -nodes -newkey rsa:2048 -days 365 \
  -keyout deploy/certs/privkey.pem -out deploy/certs/fullchain.pem \
  -subj "/CN=smartsupply.example.com"

docker compose -f docker-compose.prod.yml -f docker-compose.prod.tls.yml up -d
```

- 模板：`deploy/nginx/default-ssl.conf`（80→443 跳转、TLS1.2+、SSE 反代参数与 80 站点一致）
- 覆盖文件：`docker-compose.prod.tls.yml`（挂载证书与站点配置，追加 443 端口）
- 记得把真实域名写进 `CORS_ALLOWED_ORIGINS`（.env）

## 方式 B：certbot 自动签发（Let's Encrypt）

在方式 A 基础上：

1. DNS 将域名指向本机，80 端口可公网访问（模板已放行 `/.well-known/acme-challenge/`）。
2. 用 certbot 官方镜像以 webroot 方式签发：

```bash
docker run --rm -v ./deploy/certs:/etc/letsencrypt -v ./deploy/certs/www:/var/www/certbot \
  certbot/certbot certonly --webroot -w /var/www/certbot -d smartsupply.example.com \
  --agree-tos -m you@example.com --no-eff-email
```

3. 证书目录结构对齐（`fullchain.pem`/`privkey.pem` 软链到 `live/<域名>/` 下的实际文件），
   续期后 `docker compose restart frontend`。

## 方式 C：云 LB / Ingress 终结 TLS，回源 80

云上部署（SLB/CLB/ALB、k8s Ingress + cert-manager）时，TLS 在 LB 终结，回源本编排的 80。
此时不需要方式 A 的覆盖文件，但必须：

1. LB 健康检查指向 `/actuator/health`（经 nginx 需放行该路径，默认未代理——建议 LB 直接
   探 frontend 容器的 80 `/`，或为 nginx 增加受控的 health 路由）。
2. 设置 `CORS_ALLOWED_ORIGINS` 为真实 https 域名。
3. `X-Forwarded-For` 由 LB 设置真实客户端 IP（限流信任链依赖它）。

## 验证

```bash
curl -vk https://<host>/            # 应返回前端 index.html，证书链正常
curl -vk https://<host>/api/auth/login -X POST -H 'Content-Type: application/json' \
  -d '{"username":"x","password":"x"}'   # 应返回 200 信封 code=401（而非 TLS/403 错误）
```
