// src/router/index.ts
console.log('router/index.ts load successfully')
import { createRouter, createWebHistory } from 'vue-router'
import Home from '@/views/Home.vue'
import Login from '@/views/Login.vue'
import Register from '@/views/Register.vue'
import Chatroom from '@/views/Chatroom.vue'


const routes = [
  {
    path: '/',
    name: 'Home',
    component: Home
  },
  { path: '/login', name: 'Login', component: Login },
  {
    path: '/register',
    name: 'Register',
    component: Register
  },
  {
    path: '/chatroom',
    name: 'Chatroom',
    component: Chatroom
  }
  
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router

console.log('router map：', routes)