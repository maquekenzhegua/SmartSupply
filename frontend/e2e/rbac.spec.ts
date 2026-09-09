import { test, expect } from '@playwright/test'

// RBAC 与 401 自愈的浏览器级回归（java-direct + AI_MOCK 即可运行）：
//   ops（只读角色）→ admin 治理页被前端守卫挡回仪表盘（后端 @PreAuthorize 由 SecurityRbacTest 覆盖）
//   过期/伪造 token → 后端 401（JwtAuthFilter 修复）→ 统一拦截器跳登录页
// 断言全部基于用户可见行为（URL 跳转），不 probe 内部状态。

test('ops 只读角色访问 admin 治理页被挡回仪表盘', async ({ page }) => {
  await page.goto('/login')
  await page.getByPlaceholder('用户名').fill('ops')
  await page.getByPlaceholder('密码').fill('ops123')
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).not.toHaveURL(/\/login/)

  // 前端守卫：meta.requiresAdmin + role!=='ADMIN' → 重定向 /dashboard（可见行为 = URL 变化）
  await page.goto('/admin/runs')
  await expect(page).toHaveURL(/\/dashboard/, { timeout: 10_000 })
  // 数据页仍可正常访问（只读角色可用性不受影响）
  await page.goto('/inventory')
  await expect(page.getByText('SKU-T001-WH-M').first()).toBeVisible({ timeout: 15_000 })
})

test('无效 token 访问数据页被 401 踢回登录页', async ({ page }) => {
  // 模拟 token 过期/被吊销：直接注入伪造 token（JwtAuthFilter 解析失败 → 显式 401 →
  // request.ts 统一拦截器清凭证跳 /login；此前 401 被吞成匿名 403，用户只能看到"无权限"）
  await page.goto('/login')
  await page.evaluate(() => localStorage.setItem('token', 'forged-token'))
  await page.goto('/inventory')
  await expect(page).toHaveURL(/\/login/, { timeout: 15_000 })
})
