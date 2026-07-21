<template>
  <div class="toast-host" aria-live="polite" aria-atomic="true">
    <TransitionGroup name="toast">
      <div
        v-for="t in toasts"
        :key="t.id"
        class="toast"
        :class="`toast-${t.type}`"
        @click="dismiss(t.id)"
      >
        <BaseIcon :name="iconFor(t.type)" :size="16" />
        <span>{{ t.message }}</span>
      </div>
    </TransitionGroup>
  </div>
</template>

<script setup lang="ts">
import BaseIcon from './BaseIcon.vue'
import { useToasts, type ToastType } from '@/composables/useToast'

const { toasts, dismiss } = useToasts()

function iconFor(type: ToastType): string {
  return type === 'error' ? 'close' : type === 'success' ? 'user' : 'info'
}
</script>

<style scoped>
.toast-host {
  position: fixed;
  top: 20px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 10000;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  pointer-events: none;
}
.toast {
  display: flex;
  align-items: center;
  gap: 10px;
  max-width: 90vw;
  padding: 12px 16px;
  font-size: 14px;
  color: var(--text);
  background-color: var(--surface-2);
  border: 1px solid var(--border);
  border-left: 3px solid var(--accent);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-2);
  cursor: pointer;
  pointer-events: auto;
}
.toast-success { border-left-color: var(--success); }
.toast-error   { border-left-color: var(--danger); }
.toast-info    { border-left-color: var(--accent); }

.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
