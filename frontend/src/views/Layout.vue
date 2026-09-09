<template>
  <el-container class="app-shell">
    <!-- 侧边导航：品牌区 / 分组菜单 / 底部折叠 -->
    <el-aside :width="collapsed ? '64px' : '228px'" class="sidebar">
      <div class="brand" :class="{ collapsed }">
        <div class="brand-logo"><el-icon :size="18"><Opportunity /></el-icon></div>
        <transition name="fade">
          <div v-if="!collapsed" class="brand-text">
            <div class="brand-name">SmartSupply</div>
            <div class="brand-sub">Agent 智能供应链</div>
          </div>
        </transition>
      </div>

      <el-scrollbar class="nav-scroll">
        <el-menu
          class="nav-menu"
          :collapse="collapsed"
          :collapse-transition="false"
          :default-active="route.path"
          router
        >
          <template v-for="group in menuGroups" :key="group.label">
            <li v-if="!collapsed" class="menu-group-title">{{ group.label }}</li>
            <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
              <el-icon><component :is="item.icon" /></el-icon>
              <template #title>{{ item.title }}</template>
            </el-menu-item>
          </template>
        </el-menu>
      </el-scrollbar>

      <div class="sidebar-foot">
        <el-button class="ask-btn" :icon="Promotion" @click="askAgent()">
          <template v-if="!collapsed">Ask Agent</template>
        </el-button>
        <el-button class="collapse-btn" text :icon="collapsed ? Expand : Fold" @click="collapsed = !collapsed" />
      </div>
    </el-aside>

    <el-container class="workspace">
      <!-- 顶栏：折叠/面包屑 · 全局 Ask Agent · 用户菜单 -->
      <el-header class="topbar" :style="{ paddingLeft: '20px' }">
        <div class="crumb">
          <span class="crumb-root">SmartSupply</span>
          <el-icon class="crumb-sep" :size="12"><ArrowRight /></el-icon>
          <span class="crumb-current">{{ currentTitle }}</span>
        </div>

        <div class="spacer"></div>

        <el-input
          v-model="globalQ"
          class="ask-input"
          placeholder="问问 Agent：如“华南退货最高的品类？”"
          clearable
          @keyup.enter="askAgent()"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
          <template #append>
            <el-button @click="askAgent()">提问</el-button>
          </template>
        </el-input>

        <div class="spacer"></div>

        <el-dropdown trigger="click" @command="onUserCommand">
          <div class="user-chip">
            <div class="avatar">{{ avatarText }}</div>
            <div class="user-meta">
              <div class="user-name">{{ auth.username || '未登录' }}</div>
              <div class="user-role">{{ auth.role || 'USER' }}</div>
            </div>
            <el-icon class="user-caret" :size="12"><ArrowDown /></el-icon>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="agent"><el-icon><ChatDotRound /></el-icon>Ask Agent</el-dropdown-item>
              <el-dropdown-item divided command="logout"><el-icon><SwitchButton /></el-icon>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>

      <el-main class="app-main">
        <router-view v-slot="{ Component }">
          <transition name="page" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { Promotion, Fold, Expand } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const globalQ = ref('')
const collapsed = ref(false)

interface MenuItem { path: string; title: string; icon: string }
interface MenuGroup { label: string; items: MenuItem[] }

// 菜单分组：按业务域归类，降低平铺项的扫视成本；治理组仅 ADMIN 可见
const menuGroups = computed<MenuGroup[]>(() => {
  const groups: MenuGroup[] = [
    { label: '总览', items: [{ path: '/dashboard', title: '仪表盘', icon: 'Odometer' }] },
    {
      label: '商品与库存',
      items: [
        { path: '/products', title: '商品管理', icon: 'Goods' },
        { path: '/skus', title: 'SKU 管理', icon: 'PriceTag' },
        { path: '/warehouses', title: '仓库管理', icon: 'House' },
        { path: '/inventory', title: '库存管理', icon: 'Box' },
        { path: '/inventory-flows', title: '库存流水', icon: 'List' },
      ],
    },
    {
      label: '供应商与采购',
      items: [
        { path: '/suppliers', title: '供应商', icon: 'OfficeBuilding' },
        { path: '/contracts', title: '合同管理', icon: 'Tickets' },
        { path: '/purchase', title: '采购单', icon: 'ShoppingCart' },
      ],
    },
    {
      label: '智能助手',
      items: [
        { path: '/knowledge', title: '知识库', icon: 'Collection' },
        { path: '/agent', title: 'Agent 工作台', icon: 'ChatDotRound' },
      ],
    },
  ]
  if (auth.role === 'ADMIN') {
    groups.push({
      label: '治理后台',
      items: [
        { path: '/admin/runs', title: 'Runs', icon: 'Monitor' },
        { path: '/admin/costs', title: '成本', icon: 'Coin' },
        { path: '/admin/prompts', title: 'Prompts', icon: 'EditPen' },
        { path: '/admin/eval', title: '评测', icon: 'TrendCharts' },
      ],
    })
  }
  return groups
})

const currentTitle = computed(() => {
  for (const g of menuGroups.value) {
    const item = g.items.find(i => i.path === route.path)
    if (item) return item.title
  }
  return ''
})

const avatarText = computed(() => (auth.username || 'U').slice(0, 1).toUpperCase())

function askAgent() {
  router.push({ path: '/agent', query: globalQ.value ? { q: globalQ.value } : {} })
}

function onUserCommand(cmd: string) {
  if (cmd === 'agent') askAgent()
  if (cmd === 'logout') { auth.logout(); router.push('/login') }
}
</script>

<style scoped>
.app-shell {
  height: 100vh;
  background: var(--bg-page);
}

.sidebar {
  display: flex;
  flex-direction: column;
  background: #ffffff;
  border-right: 1px solid var(--ink-200);
  transition: width 0.2s ease;
  overflow: hidden;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 16px 12px;
  min-height: 60px;
}

.brand.collapsed {
  justify-content: center;
  padding: 16px 8px 12px;
}

.brand-logo {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  flex-shrink: 0;
  border-radius: 10px;
  background: var(--brand-gradient);
  color: #fff;
  box-shadow: 0 4px 10px rgba(79, 70, 229, 0.35);
}

.brand-name {
  font-size: 15.5px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--ink-900);
  white-space: nowrap;
}

.brand-sub {
  font-size: 11px;
  color: var(--ink-400);
  white-space: nowrap;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.nav-scroll {
  flex: 1;
  min-height: 0;
}

.sidebar-foot {
  display: flex;
  gap: 6px;
  padding: 12px;
  border-top: 1px solid var(--ink-100);
}

.ask-btn {
  flex: 1;
  color: #fff;
  background: var(--brand-gradient);
  border: none;
}

.ask-btn:hover,
.ask-btn:focus {
  color: #fff;
  filter: brightness(1.06);
}

.collapse-btn {
  flex-shrink: 0;
}

.workspace {
  min-width: 0;
}

.crumb {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  white-space: nowrap;
}

.crumb-root {
  color: var(--ink-400);
}

.crumb-sep {
  color: var(--ink-300);
}

.crumb-current {
  color: var(--ink-900);
  font-weight: 600;
}

.spacer {
  flex: 1;
}

.ask-input {
  width: 340px;
}

.ask-input :deep(.el-input__wrapper) {
  border-radius: 999px;
  padding-left: 14px;
}

.ask-input :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--brand-500) inset, 0 0 0 3px var(--brand-100);
}

.ask-input :deep(.el-input-group__append) {
  background: var(--brand-600);
  border-color: var(--brand-600);
  color: #fff;
  border-radius: 0 999px 999px 0;
  padding: 0 16px;
}

.ask-input :deep(.el-input-group__append .el-button) {
  color: #fff;
  font-weight: 500;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 5px 10px 5px 5px;
  border-radius: 999px;
  cursor: pointer;
  transition: background-color 0.18s ease;
}

.user-chip:hover {
  background: var(--ink-100);
}

.avatar {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--brand-gradient);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
}

.user-meta {
  line-height: 1.2;
}

.user-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-900);
}

.user-role {
  font-size: 11px;
  color: var(--ink-400);
}

.user-caret {
  color: var(--ink-400);
}

.app-main {
  padding: 20px 24px 28px;
  background: var(--bg-page);
  overflow: auto;
}

/* 折叠态：隐藏分组标题与文字，菜单收窄 */
.sidebar :deep(.nav-menu.el-menu--collapse .el-menu-item),
.sidebar :deep(.nav-menu.el-menu--collapse .el-sub-menu__title) {
  margin: 2px 12px;
  padding: 0 16px;
}
</style>
