<template>
  <el-container style="height: 100vh">
    <el-aside width="220px" style="background: #001529">
      <div style="color: #fff; padding: 18px 16px; font-weight: 700; font-size: 16px">SmartSupply</div>
      <div style="color: #8c8c8c; padding: 0 16px 12px; font-size: 12px">Agent 赋能 · 智能供应链</div>
      <el-menu :default-active="route.path" router background-color="#001529" text-color="#a6adb4" active-text-color="#fff">
        <el-menu-item index="/dashboard">仪表盘</el-menu-item>
        <el-menu-item index="/products">商品管理</el-menu-item>
        <el-menu-item index="/skus">SKU 管理</el-menu-item>
        <el-menu-item index="/warehouses">仓库管理</el-menu-item>
        <el-menu-item index="/inventory">库存管理</el-menu-item>
        <el-menu-item index="/inventory-flows">库存流水</el-menu-item>
        <el-menu-item index="/suppliers">供应商</el-menu-item>
        <el-menu-item index="/contracts">合同管理</el-menu-item>
        <el-menu-item index="/purchase">采购单</el-menu-item>
        <el-menu-item index="/knowledge">知识库</el-menu-item>
        <el-menu-item index="/agent">Agent 工作台</el-menu-item>
        <el-sub-menu v-if="auth.role==='ADMIN'" index="admin">
          <template #title>治理后台</template>
          <el-menu-item index="/admin/runs">Runs</el-menu-item>
          <el-menu-item index="/admin/costs">成本</el-menu-item>
          <el-menu-item index="/admin/prompts">Prompts</el-menu-item>
          <el-menu-item index="/admin/eval">评测</el-menu-item>
        </el-sub-menu>
      </el-menu>
      <div style="position: absolute; bottom: 12px; left: 16px; right: 16px; display: flex; gap: 8px">
        <el-button size="small" @click="askAgent">Ask Agent</el-button>
        <el-button size="small" @click="logout">退出</el-button>
      </div>
    </el-aside>
    <el-container>
      <el-header style="display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid #ebeef5">
        <el-input v-model="globalQ" placeholder="全局 Ask Agent：如“华南退货最高的品类？”回车直达 Agent 工作台" style="max-width: 520px" clearable @keyup.enter="askAgent">
          <template #prepend>Ask Agent</template>
        </el-input>
        <span style="color: #909399">{{ auth.username || "未登录" }} · {{ auth.role || "USER" }}</span>
      </el-header>
      <el-main style="background: #f5f7fa; overflow: auto"><router-view /></el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const globalQ = ref('')
function askAgent() {
  router.push({ path: '/agent', query: globalQ.value ? { q: globalQ.value } : {} })
}
function logout() { auth.logout(); router.push('/login') }
</script>
