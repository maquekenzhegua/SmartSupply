// SmartSupply 读链路负载冒烟：给出"可证明成熟"的延迟数字（P95 写进 docs/test-report.md）。
//
// 跑法（无需本机安装 k6，用官方镜像；Windows/Mac 下容器内经 host.docker.internal 访问宿主机后端）：
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < deploy/loadtest/k6-smoke.js
//
// 口径说明（诚实压测）：
// - 目标是业务读接口的 PG 往返性能，故后端以 SMARTSUPPLY_RATELIMIT_ENABLED=false 启动——
//   限流开启时 30/min 的用户桶会把压力主动塑形为 429 拒绝，测的是限流器不是数据库。
// - AI_MOCK=true：Agent 对话端点不依赖外部模型，压的是编排/记忆/台账链路。
import http from 'k6/http'
import { check, group } from 'k6'

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080'

export const options = {
  scenarios: {
    reads: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '20s', target: 50 }, // 爬坡到 50 并发
        { duration: '40s', target: 50 }, // 稳态 60s
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'], // 错误率 <1%
  },
}

export function setup() {
  const res = http.post(`${BASE}/api/auth/login`,
    JSON.stringify({ username: 'admin', password: 'admin123' }),
    { headers: { 'Content-Type': 'application/json' } })
  check(res, { 'login ok': (r) => r.status === 200 && r.json('data.token') !== undefined })
  return { token: res.json('data.token') }
}

export default function (data) {
  const H = { headers: { Authorization: `Bearer ${data.token}` } }

  group('库存列表(分页)', () => {
    const r = http.get(`${BASE}/api/inventory?page=1&size=20`, H)
    check(r, { 'list 200': (x) => x.status === 200 })
  })

  group('低库存查询', () => {
    const r = http.get(`${BASE}/api/inventory/low-stock`, H)
    check(r, { 'low-stock 200': (x) => x.status === 200 })
  })

  group('供应商列表', () => {
    const r = http.get(`${BASE}/api/suppliers`, H)
    check(r, { 'suppliers 200': (x) => x.status === 200 })
  })

  group('Agent 对话(mock 编排链路)', () => {
    const r = http.post(`${BASE}/api/agent/chat`,
      JSON.stringify({ message: '哪些SKU低于安全库存？', agentType: 'general', sessionId: `k6-${__VU}` }),
      { headers: Object.assign({ 'Content-Type': 'application/json' }, H.headers) })
    check(r, { 'chat 200': (x) => x.status === 200 })
  })
}
