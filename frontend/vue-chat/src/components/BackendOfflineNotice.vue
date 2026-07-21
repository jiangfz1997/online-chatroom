<template>
  <div v-if="isBackendOffline" class="backend-offline-overlay">
    <div class="backend-offline-card">
      <h2>Service temporarily unavailable</h2>
      <p>
        This is a personal project and the backend is paused outside of
        active hours to save on cost. Please check back later.
      </p>
      <button @click="retry">Retry</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { isBackendOffline } from '@/utils/backendStatus'

const retry = () => {
  // Reload re-runs whatever the current page needs (login check, chatroom
  // list, ws connect) against the backend — simplest reliable recovery path.
  location.reload()
}
</script>

<style scoped>
.backend-offline-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.85);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10000;
}

.backend-offline-card {
  background: #2c2c2c;
  color: white;
  padding: 32px 40px;
  border-radius: 10px;
  max-width: 420px;
  text-align: center;
  box-shadow: 0 0 20px rgba(0, 0, 0, 0.5);
}

.backend-offline-card h2 {
  margin-top: 0;
}

.backend-offline-card p {
  color: #ccc;
  line-height: 1.5;
}

.backend-offline-card button {
  margin-top: 16px;
  padding: 10px 20px;
  background-color: #1890ff;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
}
</style>
