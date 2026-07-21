import axios from 'axios'
import { markBackendOffline, markBackendOnline } from './backendStatus'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
})

api.interceptors.request.use(
  config => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers = config.headers || {}
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  error => Promise.reject(error)
)

// Any completed response — even a 4xx like a bad login — proves the backend
// is actually reachable, so clear the offline flag. Only a missing response
// (network/DNS/CORS failure) or a 502/503/504 gateway error (CloudFront
// couldn't reach the EC2 origin) means the backend itself is unreachable.
api.interceptors.response.use(
  response => {
    markBackendOnline()
    return response
  },
  error => {
    const status = error.response?.status
    if (!error.response || status === 502 || status === 503 || status === 504) {
      markBackendOffline()
    }
    return Promise.reject(error)
  }
)

export default api
