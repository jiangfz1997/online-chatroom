<template>
  <div class="drawer-overlay" @click.self="emit('close')">
    <aside class="drawer">
      <div class="drawer-head">
        <span class="drawer-title">Room info</span>
        <button class="icon-btn" @click="emit('close')" title="Close">
          <BaseIcon name="close" :size="18" />
        </button>
      </div>

      <div class="drawer-body">
        <div class="room-hero">
          <Avatar :seed="roomId" :name="room?.name || ''" :size="72" />
          <div class="room-name">{{ room?.name || '…' }}</div>
          <div class="room-tag">
            <BaseIcon v-if="room?.is_private" name="lock" :size="12" />
            {{ room?.is_private ? 'Private' : 'Public' }} room
          </div>
        </div>

        <p v-if="room?.description" class="room-desc">{{ room.description }}</p>

        <div class="room-facts">
          <div class="fact">
            <span class="fact-label">Created by</span>
            <span class="fact-value">{{ creatorName }}</span>
          </div>
        </div>

        <div class="members">
          <div class="members-head">
            <BaseIcon name="users" :size="15" />
            <span>Members</span>
            <span class="count">{{ members.length }}</span>
          </div>
          <div class="member-list">
            <div v-for="m in members" :key="m.username" class="member">
              <Avatar :seed="m.avatarSeed" :name="m.displayName" :size="34" />
              <div class="member-meta">
                <span class="member-name">{{ m.displayName }}</span>
                <span class="member-username">@{{ m.username }}</span>
              </div>
              <span v-if="m.username === room?.created_by" class="badge">Creator</span>
            </div>
          </div>
        </div>
      </div>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import api from '@/utils/http'
import Avatar from './Avatar.vue'
import BaseIcon from './BaseIcon.vue'

interface RoomDetail {
  name: string
  description?: string
  is_private: boolean
  created_by: string
}
interface Member {
  username: string
  displayName: string
  avatarSeed: string
}

const props = defineProps<{ roomId: string }>()
const emit = defineEmits<{ (e: 'close'): void }>()

const room = ref<RoomDetail | null>(null)
const members = ref<Member[]>([])

const creatorName = computed(() => {
  if (!room.value) return '…'
  const m = members.value.find(x => x.username === room.value!.created_by)
  return m?.displayName || room.value.created_by
})

async function load(roomId: string) {
  room.value = null
  members.value = []
  try {
    const [detail, mem] = await Promise.all([
      api.get(`/chatrooms/${roomId}`),
      api.get(`/chatrooms/${roomId}/members`),
    ])
    room.value = detail.data
    members.value = (mem.data.members || []).map((x: any) => ({
      username: x.username,
      displayName: x.display_name || x.username,
      avatarSeed: x.avatar_seed || x.username,
    }))
  } catch (err) {
    console.error('Load room info failed:', err)
  }
}

watch(() => props.roomId, id => id && load(id), { immediate: true })
</script>

<style scoped>
.drawer-overlay {
  position: fixed;
  inset: 0;
  background-color: rgba(0, 0, 0, 0.4);
  display: flex;
  justify-content: flex-end;
  z-index: 900;
}
.drawer {
  width: 320px;
  max-width: 88vw;
  height: 100%;
  background-color: var(--surface-1);
  border-left: 1px solid var(--border);
  box-shadow: var(--shadow-2);
  display: flex;
  flex-direction: column;
}
.drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid var(--border);
}
.drawer-title {
  font-size: 15px;
  font-weight: 600;
}
.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  background: transparent;
  border: none;
  border-radius: var(--r-sm);
  color: var(--text-muted);
  cursor: pointer;
}
.icon-btn:hover {
  background-color: var(--surface-2);
  color: var(--text);
}
.drawer-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px 16px;
}
.room-hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  text-align: center;
}
.room-name {
  font-size: 18px;
  font-weight: 600;
}
.room-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--text-muted);
}
.room-desc {
  margin: 18px 0 0;
  font-size: 14px;
  line-height: 1.5;
  color: var(--text-muted);
  text-align: center;
}
.room-facts {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid var(--border);
}
.fact {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
}
.fact-label {
  color: var(--text-faint);
}
.members {
  margin-top: 20px;
}
.members-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-muted);
  margin-bottom: 10px;
}
.members-head .count {
  margin-left: auto;
  background-color: var(--surface-2);
  border-radius: var(--r-full);
  padding: 1px 8px;
  font-size: 12px;
}
.member-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.member {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px;
  border-radius: var(--r-md);
}
.member:hover {
  background-color: var(--surface-2);
}
.member-meta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.member-name {
  font-size: 14px;
  font-weight: 500;
}
.member-username {
  font-size: 12px;
  color: var(--text-faint);
}
.badge {
  font-size: 11px;
  color: var(--accent);
  background-color: var(--accent-soft);
  padding: 2px 8px;
  border-radius: var(--r-full);
}
</style>
