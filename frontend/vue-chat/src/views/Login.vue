<template>
  <AuthCard title="Welcome back">
    <form @submit.prevent="handleLogin" class="form">
      <div class="field">
        <label for="username">Username</label>
        <input id="username" v-model="username" type="text" placeholder="Enter your username" />
      </div>

      <div class="field">
        <label for="password">Password</label>
        <div class="password-wrapper">
          <input
            id="password"
            v-model="password"
            :type="showPassword ? 'text' : 'password'"
            placeholder="Enter your password"
          />
          <button type="button" class="toggle-btn" @click="showPassword = !showPassword">
            {{ showPassword ? 'Hide' : 'Show' }}
          </button>
        </div>
      </div>

      <p v-if="errorMessage" class="error-text">{{ errorMessage }}</p>

      <button type="submit" :disabled="!isFormValid" class="btn btn-primary submit">Login</button>

      <p class="link-text">
        Don't have an account yet?
        <a @click.prevent="router.push('/register')" href="#">Sign up</a>
      </p>
    </form>
  </AuthCard>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import api from '@/utils/http'
import AuthCard from '@/components/AuthCard.vue'
import { toast } from '@/composables/useToast'

const router = useRouter()

const username = ref('')
const password = ref('')
const showPassword = ref(false)
const errorMessage = ref('')

const isFormValid = computed(() => username.value.trim() !== '' && password.value.length >= 6)

const handleLogin = async () => {
  try {
    const res = await api.post('/login', {
      username: username.value,
      password: password.value,
    })
    localStorage.setItem('username', res.data.username)
    localStorage.setItem('token', res.data.token)
    errorMessage.value = ''
    toast.success('Logged in')
    router.push('/chatroom')
  } catch (err: any) {
    errorMessage.value = err.response?.data?.error || 'Login failed. Please try again later.'
  }
}
</script>

<style scoped>
.form {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.field label {
  font-size: 13px;
  color: var(--text-muted);
}
.password-wrapper {
  position: relative;
}
.password-wrapper input {
  padding-right: 56px;
}
.toggle-btn {
  position: absolute;
  right: 10px;
  top: 50%;
  transform: translateY(-50%);
  background: transparent;
  border: none;
  color: var(--accent);
  font-size: 13px;
  cursor: pointer;
  padding: 0;
  width: auto;
}
.error-text {
  color: var(--danger);
  font-size: 13px;
  margin: -6px 0 0;
}
.submit {
  margin-top: 4px;
  padding: 12px;
  font-size: 15px;
}
.link-text {
  margin: 4px 0 0;
  text-align: center;
  font-size: 13px;
  color: var(--text-muted);
}
</style>
