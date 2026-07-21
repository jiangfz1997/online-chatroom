<template>
  <div class="profile-page">
    <div class="profile-card">
      <button class="back" @click="router.push('/chatroom')">
        <BaseIcon name="chevronLeft" :size="16" /> Back to chat
      </button>

      <div class="preview">
        <Avatar :seed="avatarSeed || username" :name="displayName || username" :size="88" />
        <div class="preview-name">{{ displayName || username }}</div>
        <div class="preview-username">@{{ username }}</div>
      </div>

      <div class="field">
        <label>Avatar style</label>
        <div class="avatar-grid">
          <button
            v-for="seed in seedOptions"
            :key="seed"
            class="avatar-option"
            :class="{ selected: seed === avatarSeed }"
            @click="avatarSeed = seed"
          >
            <Avatar :seed="seed" :name="displayName || username" :size="40" />
          </button>
        </div>
      </div>

      <div class="field">
        <label for="displayName">Display name</label>
        <input id="displayName" v-model="displayName" type="text" placeholder="Your display name" />
      </div>

      <div class="field">
        <label for="bio">Bio</label>
        <textarea id="bio" v-model="bio" rows="3" placeholder="Say something about yourself" />
      </div>

      <button class="btn btn-primary save" :disabled="saving" @click="save">
        {{ saving ? 'Saving…' : 'Save changes' }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import api from '@/utils/http'
import Avatar from '@/components/Avatar.vue'
import BaseIcon from '@/components/BaseIcon.vue'
import { toast } from '@/composables/useToast'

const router = useRouter()

const username = ref(localStorage.getItem('username') || '')
const displayName = ref('')
const bio = ref('')
const avatarSeed = ref('')
const saving = ref(false)

// Deterministic set of avatar styles to choose from (each seed → a distinct gradient).
const seedOptions = computed(() => {
  const base = username.value || 'user'
  return [base, ...Array.from({ length: 7 }, (_, i) => `${base}-${i + 1}`)]
})

onMounted(async () => {
  if (!localStorage.getItem('token')) {
    router.push('/login')
    return
  }
  try {
    const { data } = await api.get('/me')
    username.value = data.username
    displayName.value = data.display_name || data.username
    bio.value = data.bio || ''
    avatarSeed.value = data.avatar_seed || data.username
  } catch (err) {
    toast.error('Failed to load profile')
  }
})

const save = async () => {
  saving.value = true
  try {
    await api.put('/me', {
      display_name: displayName.value.trim() || username.value,
      bio: bio.value,
      avatar_seed: avatarSeed.value,
    })
    toast.success('Profile saved')
  } catch (err) {
    toast.error('Failed to save profile')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.profile-page {
  min-height: 100vh;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 40px 24px;
}
.profile-card {
  width: 100%;
  max-width: 440px;
  padding: 28px;
  background-color: var(--surface-1);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-2);
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.back {
  align-self: flex-start;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: transparent;
  border: none;
  color: var(--text-muted);
  font-size: 13px;
  cursor: pointer;
  padding: 0;
  width: auto;
}
.back:hover {
  color: var(--text);
}
.preview {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.preview-name {
  font-size: 18px;
  font-weight: 600;
}
.preview-username {
  font-size: 13px;
  color: var(--text-muted);
}
.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.field label {
  font-size: 13px;
  color: var(--text-muted);
}
.avatar-grid {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 8px;
}
.avatar-option {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 3px;
  background: transparent;
  border: 2px solid transparent;
  border-radius: var(--r-full);
  cursor: pointer;
  width: auto;
}
.avatar-option.selected {
  border-color: var(--accent);
}
.save {
  margin-top: 4px;
  padding: 12px;
  font-size: 15px;
}
@media (max-width: 480px) {
  .avatar-grid {
    grid-template-columns: repeat(4, 1fr);
  }
}
</style>
