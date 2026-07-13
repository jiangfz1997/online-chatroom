<template>
    <div class="register-container">
      <h2>Sign Up</h2>
      <form @submit.prevent="handleRegister" class="register-form">
        <div class="form-group">
          <label for="username">Username</label>
          <input
            id="username"
            v-model="username"
            @blur="checkUsername"
            type="text"
            placeholder="Please enter your username"
          />
          <p v-if="usernameTaken" class="error-text">This username is already taken</p>
        </div>
  
        <div class="form-group">
          <label for="password">Password</label>
          <div class="password-wrapper">
            <input
              id="password"
              v-model="password"
              :type="showPassword ? 'text' : 'password'"
              placeholder="Please enter your password"
            />
            <button type="button" class="toggle-btn" @click="togglePassword">
              {{ showPassword ? 'Hide' : 'Show' }}
            </button>
          </div>
          <p v-if="!isPasswordValid" class="error-text">
            Password must be 6–18 characters long and contain only letters, numbers, and common symbols.
          </p>
        </div>
  
        <div class="form-group">
          <label for="confirm">Confirm your password</label>
          <input
            id="confirm"
            v-model="confirmPassword"
            :type="showPassword ? 'text' : 'password'"
            placeholder="Please enter your password again"
          />
          <p v-if="passwordsMismatch" class="error-text">The two password entries do not match</p>
        </div>
  
        <p v-if="successMessage" class="success-text">{{ successMessage }}</p>
  
        <button type="submit" :disabled="!canRegister" class="register-button">
          Sign Up
        </button>
  
        <p class="link-text">
          Already have an account?<a @click.prevent="goToLogin" href="#">Login</a>
        </p>
      </form>
    </div>
  </template>
  
  <script setup lang="ts">
  // import axios from 'axios'
  import api from '@/utils/http'
  import { ref, computed } from 'vue'
  import { useRouter } from 'vue-router'
  const apiBase = import.meta.env.VITE_API_BASE_URL || '';

  const router = useRouter()
  
  const username = ref('')
  const password = ref('')
  const confirmPassword = ref('')
  const showPassword = ref(false)
  const usernameTaken = ref(false)
  const successMessage = ref('')
  
  // existing usernames
  const existingUsers = ['aaa', 'bob']
  
  const checkUsername = () => {
    usernameTaken.value = existingUsers.includes(username.value.trim())
  }
  
  // password rule
  const isPasswordValid = computed(() =>
    /^[A-Za-z0-9!@#$%^&*()_\-+=\[\]{}|\\:;"'<>,.?/~`]{6,18}$/.test(password.value)
  )
  
  const passwordsMismatch = computed(() => {
    return confirmPassword.value !== password.value
  })
  
  const canRegister = computed(() => {
    return (
      username.value.trim() !== '' &&
      !usernameTaken.value &&
      isPasswordValid.value &&
      !passwordsMismatch.value
    )
  })
  
  const togglePassword = () => {
    showPassword.value = !showPassword.value
  }
  
  const handleRegister = async () => {
  try {
    const res = await api.post(`${apiBase}/register`, {
      username: username.value,
      password: password.value,
    })

    // Registration success message
    successMessage.value = res.data.message || 'Registration successful! Redirecting to the login page...'

    // redirect after 2s
    setTimeout(() => {
      router.push('/login')
    }, 2000)
  } catch (err: any) {
    successMessage.value = ''

    // backend error response
    if (err.response?.data?.error) {
      alert(err.response.data.error)
    } else {
      alert('Request failed. Please try again later.')
    }
  }
}

  
  const goToLogin = () => {
    router.push('/login')
  }
  </script>
  
  <style scoped>
  .register-container {
    max-width: 450px;
    margin: 80px auto;
    padding: 30px;
    border: 1px solid #ddd;
    border-radius: 8px;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.05);
    text-align: center;
    font-family: Arial, sans-serif;
  }
  
  .register-form {
    display: flex;
    flex-direction: column;
    gap: 20px;
  }
  
  .form-group {
    text-align: left;
  }
  
  input {
    width: 100%;
    padding: 10px;
    font-size: 16px;
    box-sizing: border-box;
    border: 1px solid #ccc;
    border-radius: 4px;
  }
  
  .password-wrapper {
    position: relative;
  }
  
  .toggle-btn {
    position: absolute;
    right: 10px;
    top: 50%;
    transform: translateY(-50%);
    background: transparent;
    border: none;
    color: #007bff;
    font-size: 14px;
    cursor: pointer;
    padding: 0;
  }
  
  .error-text {
    color: red;
    font-size: 14px;
    margin-top: 5px;
  }
  
  .success-text {
    color: green;
    font-size: 15px;
    margin: -10px 0 10px;
  }
  
  .register-button {
    padding: 10px;
    background-color: #42b983;
    color: white;
    font-size: 16px;
    border: none;
    border-radius: 4px;
    cursor: pointer;
  }
  
  .register-button:disabled {
    background-color: #ccc;
    cursor: not-allowed;
  }
  
  .link-text {
    margin-top: 10px;
    font-size: 14px;
    text-align: center;
  }
  
  .link-text a {
    color: #007bff;
    text-decoration: underline;
    cursor: pointer;
  }
  </style>
  