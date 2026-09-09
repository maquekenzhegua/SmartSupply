// ECharts 统一品牌主题：与全局靛蓝色板同源，所有页面图表共用
export const chartPalette = [
  '#4f46e5', // indigo-600 主色
  '#0ea5e9', // sky-500
  '#10b981', // emerald-500
  '#f59e0b', // amber-500
  '#f43f5e', // rose-500
  '#8b5cf6', // violet-500
  '#14b8a6', // teal-500
  '#64748b', // slate-500
]

// 各页面 option 与其合并：统一字体、配色、网格、提示框质感
export function chartBase() {
  return {
    color: chartPalette,
    textStyle: {
      fontFamily:
        "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif",
      color: '#475569',
    },
    tooltip: {
      backgroundColor: '#ffffff',
      borderColor: '#e2e8f0',
      borderWidth: 1,
      padding: [8, 12],
      textStyle: { color: '#0f172a', fontSize: 12.5 },
      extraCssText: 'box-shadow: 0 4px 12px rgba(15,23,42,.08); border-radius: 8px;',
    },
    grid: { left: 8, right: 8, top: 32, bottom: 8, containLabel: true },
  }
}
