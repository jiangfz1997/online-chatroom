import { ref } from 'vue'

// Global flag: true when the backend appears unreachable (e.g. EC2 stopped
// to save cost outside active hours). Toggled by the axios response
// interceptor in http.ts based on network failures / 502-504 gateway errors.
export const isBackendOffline = ref(false)

export function markBackendOffline() {
  isBackendOffline.value = true
}

export function markBackendOnline() {
  isBackendOffline.value = false
}
