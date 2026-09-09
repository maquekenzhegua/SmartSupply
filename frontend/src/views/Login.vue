<template>
  <div class="login-shell">
    <!-- 左侧品牌区：产品定位与核心能力 -->
    <div class="brand-pane">
      <div class="brand-glow glow-a"></div>
      <div class="brand-glow glow-b"></div>

      <div class="brand-content">
        <div class="brand-mark">
          <div class="brand-logo"><el-icon :size="20"><Opportunity /></el-icon></div>
          <span class="brand-name">SmartSupply</span>
        </div>

        <h1 class="headline">Agent 赋能的<br />智能供应链协同平台</h1>
        <p class="tagline">从供应商、库存、采购到合同的全流程业务闭环，由可信 Agent 驱动深度推理与人工批准。</p>

        <ul class="feature-list">
          <li v-for="f in features" :key="f.title">
            <div class="feature-icon"><el-icon :size="15"><component :is="f.icon" /></el-icon></div>
            <div>
              <div class="feature-title">{{ f.title }}</div>
              <div class="feature-desc">{{ f.desc }}</div>
            </div>
          </li>
        </ul>
      </div>

      <div class="brand-foot">无 Key 可完整演示 · 有 Key 一键切换真实模型</div>
    </div>

    <!-- 右侧登录表单 -->
    <div class="form-pane">
      <el-card class="login-card">
        <h2 class="login-title">欢迎回来</h2>
        <p class="login-sub">登录以进入你的供应链工作台</p>

        <el-form @submit.prevent="onLogin">
          <el-form-item>
            <el-input v-model="username" placeholder="用户名" size="large" :prefix-icon="User" autocomplete="username" />
          </el-form-item>
          <el-form-item>
            <el-input v-model="password" type="password" placeholder="密码" size="large" :prefix-icon="Lock" show-password autocomplete="current-password" />
          </el-form-item>
          <el-button class="btn-brand login-btn" native-type="submit" :loading="loading" size="large">
            登录
          </el-button>
        </el-form>

        <el-alert class="demo-tip" type="info" :closable="false" show-icon>
          <template #title>演示账号：admin / admin123</template>
        </el-alert>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { User, Lock } from '@element-plus/icons-vue'

const router = useRouter()
const auth = useAuthStore()
const username = ref('admin')
const password = ref('admin123')
const loading = ref(false)

const features = [
  { icon: 'Box', title: '业务闭环', desc: '供应商 · 商品 · 库存 · 采购 · 合同 · BI' },
  { icon: 'ChatDotRound', title: '可信 Agent', desc: '工具调用留痕，写操作人工批准（HITL）' },
  { icon: 'TrendCharts', title: '深度推理', desc: 'LangGraph 规划-取证-反思，答案可溯源' },
]

async function onLogin() {
  loading.value = true
  try { await auth.login(username.value, password.value); router.push('/') }
  finally { loading.value = false }
}
</script>

<style scoped>
.login-shell {
  display: grid;
  grid-template-columns: minmax(480px, 1.1fr) minmax(420px, 1fr);
  min-height: 100vh;
  background: var(--bg-page);
}

/* ------- 左侧品牌区 ------- */
.brand-pane {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 48px 56px;
  overflow: hidden;
  color: #fff;
  background: var(--brand-gradient);
}

.brand-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(10px);
  pointer-events: none;
}

.glow-a {
  width: 420px;
  height: 420px;
  right: -120px;
  top: -140px;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.16) 0%, transparent 70%);
}

.glow-b {
  width: 520px;
  height: 520px;
  left: -160px;
  bottom: -220px;
  background: radial-gradient(circle, rgba(14, 165, 233, 0.35) 0%, transparent 70%);
}

.brand-content {
  position: relative;
  margin: auto 0;
  max-width: 520px;
}

.brand-mark {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 40px;
}

.brand-logo {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.16);
  border: 1px solid rgba(255, 255, 255, 0.25);
  backdrop-filter: blur(4px);
}

.brand-name {
  font-size: 19px;
  font-weight: 700;
  letter-spacing: 0.01em;
}

.headline {
  margin: 0 0 14px;
  font-size: 34px;
  line-height: 1.3;
  font-weight: 700;
  letter-spacing: -0.02em;
}

.tagline {
  margin: 0 0 36px;
  font-size: 14.5px;
  line-height: 1.8;
  color: rgba(255, 255, 255, 0.78);
}

.feature-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 18px;
}

.feature-list li {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.feature-icon {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.14);
  border: 1px solid rgba(255, 255, 255, 0.22);
}

.feature-title {
  font-size: 14px;
  font-weight: 600;
}

.feature-desc {
  margin-top: 2px;
  font-size: 12.5px;
  color: rgba(255, 255, 255, 0.66);
}

.brand-foot {
  position: relative;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.55);
}

/* ------- 右侧表单区 ------- */
.form-pane {
  display: grid;
  place-items: center;
  padding: 32px;
}

.login-card {
  width: 100%;
  max-width: 400px;
  padding: 8px 4px;
}

.login-title {
  margin: 0 0 6px;
  font-size: 22px;
}

.login-sub {
  margin: 0 0 24px;
  font-size: 13.5px;
  color: var(--ink-500);
}

.login-btn {
  width: 100%;
  margin-top: 4px;
}

.demo-tip {
  margin-top: 18px;
  border-radius: var(--radius-md);
}

/* 窄屏降级：隐藏品牌区，仅保留表单 */
@media (max-width: 900px) {
  .login-shell {
    grid-template-columns: 1fr;
  }

  .brand-pane {
    display: none;
  }
}
</style>
