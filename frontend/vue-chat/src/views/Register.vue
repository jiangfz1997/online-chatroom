<template>
  <AuthCard title="Create your account">
    <form @submit.prevent="handleRegister" class="form">
      <div class="field">
        <label for="username">Username</label>
        <input id="username" v-model="username" type="text" placeholder="Choose a username" />
      </div>

      <div class="field">
        <label for="password">Password</label>
        <div class="password-wrapper">
          <input
            id="password"
            v-model="password"
            :type="showPassword ? 'text' : 'password'"
            placeholder="Choose a password"
          />
          <button type="button" class="toggle-btn" @click="showPassword = !showPassword">
            {{ showPassword ? 'Hide' : 'Show' }}
          </button>
        </div>
        <p v-if="password && !isPasswordValid" class="hint error">
          6–18 chars: letters, numbers, and common symbols only.
        </p>
      </div>

      <div class="field">
        <label for="confirm">Confirm password</label>
        <input
          id="confirm"
          v-model="confirmPassword"
          :type="showPassword ? 'text' : 'password'"
          placeholder="Re-enter your password"
        />
        <p v-if="confirmPassword && passwordsMismatch" class="hint error">
          Passwords do not match.
        </p>
      </div>

      <button type="submit" :disabled="!canRegister" class="btn btn-primary submit">Sign Up</button>

      <p class="link-text">
        Already have an account?
        <a @click.prevent="router.push('/login')" href="#">Login</a>
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
const confirmPassword = ref('')
const showPassword = ref(false)

const isPasswordValid = computed(() =>
  /^[A-Za-z0-9!@#$%^&*()_\-+=\[\]{}|\\:;"'<>,.?/~`]{6,18}$/.test(password.value)
)
const passwordsMismatch = computed(() => confirmPassword.value !== password.value)
const canRegister = computed(
  () => username.value.trim() !== '' && isPasswordValid.value && !passwordsMismatch.value
)

const handleRegister = async () => {
  try {
    await api.post('/register', {
      username: username.value,
      password: password.value,
    })
    toast.success('Account created — please log in')
    setTimeout(() => router.push('/login'), 1200)
  } catch (err: any) {
    toast.error(err.response?.data?.error || 'Registration failed. Please try again later.')
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
.hint {
  font-size: 12px;
  margin: 0;
  color: var(--text-faint);
}
.hint.error {
  color: var(--danger);
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
