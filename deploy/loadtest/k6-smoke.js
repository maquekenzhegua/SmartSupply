// SmartSupply 负载冒烟：读链路 + 写链路（库存调整/采购单）双场景。
//
// 跑法（无需本机安装 k6，用官方镜像；Windows/Mac 下容器内经 host.docker.internal 访问宿主机后端）：
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < deploy/loadtest/k6-smoke.js
//   SCENARIO=writes 同上（写链路：库存原子调整 + DRAFT 采购单创建）
//
// 口径说明（诚实压测）：
// - 目标是业务读写接口的 PG 往返性能，故后端以 SMARTSUPPLY_RATELIMIT_ENABLED=false 启动——
//   限流开启时 30/min 的用户桶会把压力主动塑形为 429 拒绝，测的是限流器不是数据库。
// - AI_MOCK=true：Agent 对话端点不依赖外部模型，压的是编排/记忆/台账链路。
// - thresholds 里有 P95 延迟断言：此前只有错误率门禁，性能基线只活在文档里不可执行。
import http from 'k6/http'
import { check, group } from 'k6'

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080'
const SCENARIO = __ENV.SCENARIO || 'reads'

// 读链路基线（docs/test-report.md 历史：50 并发 P95≈77ms）——阈值给足余量防 CI 抖动误报
const READ_P95_MS = 800
// 写链路含事务与台账写入，放宽一档
const WRITE_P95_MS = 1200

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
    writes: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '15s', target: 20 },
        { duration: '30s', target: 20 },
      ],
      gracefulRampDown: '5s',
      env: { WRITE_MODE: '1' },
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'], // 错误率 <1%
    [`http_req_duration{scenario:${SCENARIO}}`]: [`p(95)<${SCENARIO === 'writes' ? WRITE_P95_MS : READ_P95_MS}`],
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
  const isWrite = __ENV.WRITE_MODE === '1'

  if (!isWrite) {
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
    return
  }

  // ---- 写链路：真实落库（原子库存调整 + DRAFT 采购单），验证事务路径的可扩展性 ----
  group('库存原子调整', () => {
    const r = http.post(`${BASE}/api/inventory/adjust`,
      JSON.stringify({ skuId: 1, warehouseId: 1, changeQty: 1, reason: `k6-${__VU}` }),
      { headers: Object.assign({ 'Content-Type': 'application/json' }, H.headers) })
    check(r, { 'adjust 200': (x) => x.status === 200 && x.json('code') === 200 })
  })

  group('创建 DRAFT 采购单', () => {
    const r = http.post(`${BASE}/api/purchase-orders-extra`,
      JSON.stringify({
        supplierId: 1, remark: 'k6 压测',
        items: [{ skuId: 1, quantity: 2, unitPrice: 9.9 }],
      }),
      { headers: Object.assign({ 'Content-Type': 'application/json' }, H.headers) })
    check(r, { 'po create 200': (x) => x.status === 200 && x.json('code') === 200 })
  })
}
