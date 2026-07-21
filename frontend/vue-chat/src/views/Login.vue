<template>
    <div class="login-container">
      <h2>Login</h2>
      <form @submit.prevent="handleLogin" class="login-form">
        <div class="form-group">
          <label for="username">Username</label>
          <input
            id="username"
            v-model="username"
            type="text"
            placeholder="Please enter your username"
          />
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
            <button type="button" @click="togglePassword" class="toggle-btn">
              {{ showPassword ? 'Hide' : 'Show' }}
            </button>
          </div>
        </div>
  
        <p v-if="errorMessage" class="error-text">{{ errorMessage }}</p>
  
        <button type="submit" :disabled="!isFormValid" class="login-button">
          Login
        </button>
  
        <p class="link-text">
          Don't have an account yet?<a @click.prevent="goToRegister" href="#">Sign up</a>
        </p>
      </form>
    </div>
  </template>
  
  <script setup lang="ts">
  import { ref, computed } from 'vue'
  import { useRouter } from 'vue-router'
  //import axios from 'axios'
  import api from '@/utils/http'
  const apiBase = import.meta.env.VITE_API_BASE_URL || '';
  
  const router = useRouter()
  
  
  const username = ref('')
  const password = ref('')
  const showPassword = ref(false)
  const errorMessage = ref('')
  
  // hide/show password
  const togglePassword = () => {
    showPassword.value = !showPassword.value
  }
  
  // Username must not be empty, and password must be at least 6 characters long.
  const isFormValid = computed(() => {
    return username.value.trim() !== '' && password.value.length >= 6
  })


  // handle login
  const handleLogin = async () => {
    try {
      //const res = await axios.post('http://host.docker.internal:8080/login', {
      console.log("login request" + apiBase)
      // const res = await axios.post(`${apiBase}/login`, {
      // //const res = await axios.post('/api/login', {
      //   username: username.value,
      //   password: password.value
      // })
      const res = await api.post('/login', {
        username: username.value,
        password: password.value
      })

      // set username and token in localStorage
      localStorage.setItem('username', res.data.username)
      localStorage.setItem('token', res.data.token)
      // login successful
      console.log('login successful：', res.data)
      errorMessage.value = ''
      router.push('/chatroom')
    } catch (err: any) {
      if (err.response?.data?.error) {
        errorMessage.value = err.response.data.error
      } else {
        errorMessage.value = 'Login failed. Please try again later.'
      }
    }
  }
  
  // sign up
  const goToRegister = () => {
    router.push('/register')
  }
  </script>
  
  <style scoped>
  .login-container {
    max-width: 400px;
    margin: 80px auto;
    padding: 30px;
    border: 1px solid #ddd;
    border-radius: 8px;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.05);
    text-align: center;
    font-family: Arial, sans-serif;
  }
  
  .login-form {
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
    margin-top: -10px;
    text-align: left;
  }
  
  .login-button {
    padding: 10px;
    background-color: #42b983;
    color: white;
    font-size: 16px;
    border: none;
    border-radius: 4px;
    cursor: pointer;
    transition: background 0.3s;
  }
  
  .login-button:disabled {
    background-color: #ccc;
    cursor: not-allowed;
  }
  
  .link-text {
    margin-top: 10px;
    font-size: 14px;
  }
  
  .link-text a {
    color: #007bff;
    text-decoration: underline;
    cursor: pointer;
  }
  </style>
  