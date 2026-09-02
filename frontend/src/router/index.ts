import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('@/views/Login.vue') },
    {
      path: '/',
      component: () => import('@/views/Layout.vue'),
      redirect: '/dashboard',
      children: [
        { path: 'dashboard', component: () => import('@/views/Dashboard.vue') },
        { path: 'inventory', component: () => import('@/views/Inventory.vue') },
        { path: 'inventory-flows', component: () => import('@/views/InventoryFlows.vue') },
        { path: 'products', component: () => import('@/views/Products.vue') },
        { path: 'skus', component: () => import('@/views/Skus.vue') },
        { path: 'warehouses', component: () => import('@/views/Warehouses.vue') },
        { path: 'suppliers', component: () => import('@/views/Suppliers.vue') },
        { path: 'contracts', component: () => import('@/views/Contracts.vue') },
        { path: 'purchase', component: () => import('@/views/Purchase.vue') },
        { path: 'knowledge', component: () => import('@/views/Knowledge.vue') },
        { path: 'agent', component: () => import('@/views/AgentWorkspace.vue') },
        { path: 'admin/runs', component: () => import('@/views/admin/Runs.vue'), meta: { requiresAdmin: true } },
        { path: 'admin/costs', component: () => import('@/views/admin/Costs.vue'), meta: { requiresAdmin: true } },
        { path: 'admin/prompts', component: () => import('@/views/admin/Prompts.vue'), meta: { requiresAdmin: true } },
        { path: 'admin/eval', component: () => import('@/views/admin/Eval.vue'), meta: { requiresAdmin: true } },
      ],
    },
  ],
})

router.beforeEach((to) => {
  const token = localStorage.getItem('token')
  const role = localStorage.getItem('role')
  if (to.path !== '/login' && !token) return '/login'
  if (to.path === '/login' && token) return '/'
  if ((to as any).meta?.requiresAdmin && role !== 'ADMIN') return '/dashboard'
})

export default router
