<template>
  <router-view />
  <BackendOfflineNotice />
  <ToastHost />
</template>
<script setup lang="ts">
import BackendOfflineNotice from '@/components/BackendOfflineNotice.vue'
import ToastHost from '@/components/ToastHost.vue'
</script>
