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
      ],
    },
  ],
})

router.beforeEach((to) => {
  const token = localStorage.getItem('token')
  if (to.path !== '/login' && !token) return '/login'
  if (to.path === '/login' && token) return '/'
})

export default router
