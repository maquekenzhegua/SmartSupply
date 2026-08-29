<template>
  <div style="min-height: 100vh; display: grid; place-items: center; background: #f5f7fa">
    <el-card style="width: 380px">
      <h2 style="margin: 0 0 8px">SmartSupply 登录</h2>
      <p style="color: #909399; margin: 0 0 18px">演示账号 admin / admin123</p>
      <el-form @submit.prevent="onLogin">
        <el-form-item><el-input v-model="username" placeholder="用户名" size="large" /></el-form-item>
        <el-form-item><el-input v-model="password" type="password" placeholder="密码" size="large" /></el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading" style="width: 100%" size="large">登录</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
const router = useRouter()
const auth = useAuthStore()
const username = ref('admin')
const password = ref('admin123')
const loading = ref(false)
async function onLogin() {
  loading.value = true
  try { await auth.login(username.value, password.value); router.push('/') }
  finally { loading.value = false }
}
</script>
