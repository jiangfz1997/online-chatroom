import { reactive } from 'vue'

export type ToastType = 'success' | 'error' | 'info'

export interface Toast {
  id: number
  message: string
  type: ToastType
}

const state = reactive<{ items: Toast[] }>({ items: [] })
let nextId = 1

function push(message: string, type: ToastType, duration = 3000) {
  const id = nextId++
  state.items.push({ id, message, type })
  window.setTimeout(() => dismiss(id), duration)
}

function dismiss(id: number) {
  const i = state.items.findIndex(t => t.id === id)
  if (i !== -1) state.items.splice(i, 1)
}

/** Global toast API. Import anywhere: `import { toast } from '@/composables/useToast'`. */
export const toast = {
  success: (msg: string) => push(msg, 'success'),
  error: (msg: string) => push(msg, 'error'),
  info: (msg: string) => push(msg, 'info'),
}

/** Used by ToastHost to render the live toast list. */
export function useToasts() {
  return { toasts: state.items, dismiss }
}
