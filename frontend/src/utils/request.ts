import axios from 'axios'
import { ElMessage } from 'element-plus'

const request = axios.create({ baseURL: '/api', timeout: 30000 })

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

request.interceptors.response.use(
  (res) => {
    const data = res.data
    if (data && typeof data.code === 'number' && data.code !== 200) {
      ElMessage.error(data.msg || '请求失败')
      return Promise.reject(new Error(data.msg))
    }
    return res
  },
  (err) => {
    const status = err.response?.status
    const msg = err.response?.data?.msg || err.message || '网络错误'
    if (status === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('username')
      if (location.pathname !== '/login') location.href = '/login'
    } else if (status === 429) {
      ElMessage.error('操作过于频繁，请稍后再试')
    } else if (status === 403) {
      ElMessage.error('无权限或登录已失效')
    } else {
      ElMessage.error(msg)
    }
    return Promise.reject(err)
  }
)

export default request
