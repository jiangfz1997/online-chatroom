<template>
    <div class="home-container">
      <h1>Welcome to the chatroom</h1>
      <p class="subtitle">Let's chat freely and enjoy the conversation together!</p>
      <div class="button-group">
        <button @click="goToLogin">Login</button>
        <button @click="goToRegister">Sign Up</button>
      </div>
    </div>
  </template>
  
  <script setup lang="ts">
  import { useRouter } from 'vue-router' // import useRouter
  const router = useRouter()             // router instance
  
  const goToLogin = () => {
    console.log("login")
    router.push('/login')
  }
  
  const goToRegister = () => {
    console.log("register")
    router.push('/register')
  }
  </script>
  
  <style scoped>
  .home-container {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    min-height: 80vh;
    padding: 20px;
    text-align: center;
  }
  
  .subtitle {
    margin-top: 10px;
    color: #666;
    font-size: 16px;
  }
  
  .button-group {
    margin-top: 40px;
    display: flex;
    gap: 30px;
  }
  
  button {
    padding: 12px 24px;
    font-size: 16px;
    background-color: #42b983;
    color: white;
    border: none;
    border-radius: 6px;
    cursor: pointer;
    transition: background-color 0.3s ease;
  }
  
  button:hover {
    background-color: #36986f;
  }
  </style>
  