# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| main    | :white_check_mark: |

## Reporting a Vulnerability

If you discover a security vulnerability, please do NOT open a public issue.
Instead, email the maintainer or use GitHub's private vulnerability reporting.

We aim to respond within 72 hours.

## Secrets Hygiene

- `.env` is git-ignored. Copy `.env.example` to `.env` and fill in real credentials locally.
- Never commit API keys, JWT secrets, or database passwords. Use environment variables or a secret manager in production.
- If a secret is accidentally committed, rotate it immediately (invalidate the old key) and force-push a cleaned history or open a new repo.

## Demo Credentials

The default login `admin / admin123` is for local demo and interview reproduction only.
Do not expose it on a public instance. Override via environment variable or database.

## 密钥卫生

- 本地 `.env` 含真实形 API Key（明文）。它已被 .gitignore 排除且从未入 git；但请勿截图/粘贴分享。
- 演示用的 OPENAI_API_KEY / Langfuse key 建议按季度轮换：轮换后只需更新 .env 并重启对应组件。
- 生产环境一律经环境变量注入（compose `:?` 语法缺省即启动失败），不落盘进仓库。
