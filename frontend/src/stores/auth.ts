import { defineStore } from 'pinia'
import request from '@/utils/request'

export const useAuthStore = defineStore('auth', {
  state: () => ({ token: localStorage.getItem('token') || '', username: localStorage.getItem('username') || '' }),
  actions: {
    async login(username: string, password: string) {
      const res = await request.post('/auth/login', { username, password })
      const payload = res.data.data || res.data
      this.token = payload.token
      this.username = payload.username || username
      localStorage.setItem('token', this.token)
      localStorage.setItem('username', this.username)
    },
    logout() {
      this.token = ''; this.username = ''
      localStorage.removeItem('token'); localStorage.removeItem('username')
    },
  },
})
