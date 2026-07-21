<template>
  <div class="home">
    <div class="brand">
      <span class="brand-mark"><BaseIcon name="chat" :size="34" /></span>
    </div>
    <h1 class="title">Chatroom</h1>
    <p class="subtitle">Real-time rooms — chat freely and enjoy the conversation.</p>
    <div class="actions">
      <button class="btn btn-primary" @click="router.push('/login')">Login</button>
      <button class="btn btn-ghost" @click="router.push('/register')">Sign Up</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import BaseIcon from '@/components/BaseIcon.vue'

const router = useRouter()
</script>

<style scoped>
.home {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 24px;
}
.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  border-radius: var(--r-lg);
  color: #fff;
  background-image: linear-gradient(135deg, var(--accent), var(--accent-2));
  box-shadow: var(--shadow-2);
}
.title {
  margin: 24px 0 8px;
  font-size: 40px;
  letter-spacing: -0.02em;
}
.subtitle {
  margin: 0;
  color: var(--text-muted);
  font-size: 16px;
  max-width: 360px;
}
.actions {
  margin-top: 36px;
  display: flex;
  gap: 14px;
}
.actions .btn {
  min-width: 120px;
  padding: 12px 20px;
  font-size: 15px;
}
</style>
