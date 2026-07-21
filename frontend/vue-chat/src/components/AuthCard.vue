<template>
  <div class="auth-page">
    <div class="auth-card">
      <div class="brand">
        <span class="brand-mark"><BaseIcon name="chat" :size="22" /></span>
        <span class="brand-name">Chatroom</span>
      </div>
      <h2 v-if="title" class="auth-title">{{ title }}</h2>
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
import BaseIcon from './BaseIcon.vue'

defineProps<{ title?: string }>()
</script>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
.auth-card {
  width: 100%;
  max-width: 400px;
  padding: 32px;
  background-color: var(--surface-1);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-2);
}
.brand {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin-bottom: 24px;
}
.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: var(--r-md);
  color: #fff;
  background-image: linear-gradient(135deg, var(--accent), var(--accent-2));
}
.brand-name {
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.01em;
}
.auth-title {
  text-align: center;
  margin: 0 0 24px;
  font-size: 18px;
  color: var(--text-muted);
  font-weight: 500;
}
</style>
