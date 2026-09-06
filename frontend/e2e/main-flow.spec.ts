import { test, expect } from '@playwright/test'

// SmartSupply 浏览器级主链路回归（java-direct + AI_MOCK 即可运行，无外部依赖）：
//   登录 → 库存业务页 → Agent 对话写意图 → 写闸门二次确认 → 确认回路完成
// 断言全部基于用户可见行为（按钮/文案/页面跳转），不 probe 内部状态。

test('登录 → 库存页种子数据可见', async ({ page }) => {
  await page.goto('/login')
  await page.getByPlaceholder('用户名').fill('admin')
  await page.getByPlaceholder('密码').fill('admin123')
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).not.toHaveURL(/\/login/)

  await page.goto('/inventory')
  await expect(page.getByText('SKU-T001-WH-M').first()).toBeVisible({ timeout: 15_000 })
})

test('Agent 写意图 → 写闸门确认 UX 闭环', async ({ page }) => {
  await page.goto('/login')
  await page.getByPlaceholder('用户名').fill('admin')
  await page.getByPlaceholder('密码').fill('admin123')
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).not.toHaveURL(/\/login/)

  await page.goto('/agent')
  // 关闭"流式"开关走非流式：vite dev 代理对"毫秒级完成"的 SSE 闸门快路径有缓冲怪癖
  // （吞首事件到超时，dev-only 工具链问题），非流式路径完全确定；写闸门逻辑两路共用
  await page.locator('.input-row .el-switch').first().click()
  // el-input 的内层 input（.el-input__inner）唯一命中聊天输入框；el-select 的输入框不含此类名
  const input = page.locator('.input-row .el-input__inner')
  await input.fill('帮我创建采购单：供应商1，SKU-T001-WH-M，数量100，单价9.9')
  await page.getByRole('button', { name: '发送' }).click()

  // 写意图闸门：java-direct 拦下并给出二次确认（快路径，无需模型）
  const confirmBtn = page.getByRole('button', { name: '确认创建采购单' })
  await expect(confirmBtn).toBeVisible({ timeout: 30_000 })

  // 确认后重新提交，回路完成（发送按钮恢复 = loading 结束，有新回复产出）
  await confirmBtn.click()
  await expect(page.getByRole('button', { name: '发送' })).toBeVisible({ timeout: 30_000 })
  await expect(confirmBtn).toBeHidden()
})
