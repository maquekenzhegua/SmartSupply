import { defineStore } from 'pinia'
import request from '@/utils/request'

export const useAuthStore = defineStore('auth', {
  state: () => ({ token: localStorage.getItem('token') || '', username: localStorage.getItem('username') || '', role: localStorage.getItem('role') || '' }),
  actions: {
    async login(username: string, password: string) {
      const res = await request.post('/auth/login', { username, password })
      const payload = res.data.data || res.data
      this.token = payload.token
      this.username = payload.username || username
      this.role = payload.role || ''
      localStorage.setItem('token', this.token)
      localStorage.setItem('username', this.username)
      localStorage.setItem('role', this.role)
    },
    get isAdmin(): boolean { return (this as any).role === 'ADMIN' },
    logout() {
      this.token = ''; this.username = ''; this.role=''
      localStorage.removeItem('token'); localStorage.removeItem('username'); localStorage.removeItem('role')
    },
  },
})
