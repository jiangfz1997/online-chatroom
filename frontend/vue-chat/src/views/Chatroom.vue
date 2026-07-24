<template>
  <div class="chatroom">
    <!-- Top bar -->
    <header class="top-bar">
      <div class="search">
        <BaseIcon name="search" :size="16" />
        <input
          v-model="searchRoomId"
          @keyup.enter="handleSearchRoom"
          type="text"
          placeholder="Find a room by ID and hit enter…"
        />
      </div>
      <div class="user-area">
        <button class="user-chip" @click="router.push('/profile')" title="Edit profile">
          <Avatar :seed="me.avatarSeed" :name="me.displayName" :size="30" />
          <span class="chip-name">{{ me.displayName }}</span>
        </button>
        <button class="icon-btn" @click="logout" title="Logout">
          <BaseIcon name="logout" :size="18" />
        </button>
      </div>
    </header>

    <div class="main">
      <!-- Sidebar -->
      <aside class="sidebar">
        <div class="sidebar-scroll">
          <button
            v-for="room in chatrooms"
            :key="room.id"
            class="room-item"
            :class="{ active: selectedRoom && selectedRoom.id === room.id }"
            @click="selectRoom(room)"
            @contextmenu.prevent="openContextMenu($event, room)"
          >
            <Avatar :seed="room.id" :name="room.name" :size="38" />
            <div class="room-meta">
              <span class="room-name">{{ room.name }}</span>
              <span class="room-type">{{ room.isPrivate ? 'Private' : 'Public' }}</span>
            </div>
            <span v-if="room.unread > 0" class="unread">{{ room.unread }}</span>
          </button>
        </div>
        <button class="create-room btn btn-ghost" @click="showCreateModal = true">
          <BaseIcon name="plus" :size="16" /> New room
        </button>
      </aside>

      <!-- Chat window -->
      <section class="chat">
        <div v-if="!selectedRoom" class="empty">
          <span class="empty-mark"><BaseIcon name="chat" :size="40" /></span>
          <p>Select a room to start chatting.</p>
        </div>

        <template v-else>
          <div class="chat-header">
            <Avatar :seed="selectedRoom.id" :name="selectedRoom.name" :size="34" />
            <div class="chat-header-meta">
              <span class="chat-title">{{ selectedRoom.name }}</span>
              <span class="chat-sub">{{ selectedRoom.isPrivate ? 'Private room' : 'Public room' }}</span>
            </div>
            <button class="icon-btn" @click="showRoomInfo = true" title="Room info">
              <BaseIcon name="info" :size="18" />
            </button>
          </div>

          <div class="messages" ref="messageContainer">
            <div
              v-if="selectedRoom && !noMoreMessages[selectedRoom.id]"
              class="history-loader"
              @click="loadHistory(selectedRoom.id)"
            >
              Load older messages
            </div>
            <div
              v-else-if="selectedRoom && noMoreMessages[selectedRoom.id]"
              class="history-loader no-more"
            >
              No more history
            </div>

            <template v-for="item in renderItems" :key="item.key">
              <div v-if="item.dateLabel" class="date-sep"><span>{{ item.dateLabel }}</span></div>
              <div class="msg-row" :class="{ own: item.own, grouped: !item.showHeader }">
                <div class="msg-avatar">
                  <Avatar
                    v-if="item.showHeader"
                    :seed="senderInfo(item.msg.sender).avatarSeed"
                    :name="senderInfo(item.msg.sender).displayName"
                    :size="36"
                  />
                </div>
                <div class="msg-body">
                  <div v-if="item.showHeader" class="msg-head">
                    <span class="msg-name">{{ senderInfo(item.msg.sender).displayName }}</span>
                    <span class="msg-time">{{ formatTime(item.msg.timestamp) }}</span>
                  </div>
                  <div
                    class="msg-bubble"
                    :class="{ pending: item.msg.status === 'pending', failed: item.msg.status === 'failed' }"
                    :title="item.msg.status === 'failed' ? 'Click to resend' : undefined"
                    @click="item.msg.status === 'failed' && selectedRoom && retryMessage(selectedRoom.id, item.msg.id)"
                  >{{ item.msg.text }}</div>
                  <span v-if="item.msg.status === 'failed'" class="msg-status failed">Failed to send · tap to retry</span>
                </div>
              </div>
            </template>
          </div>

          <div class="composer">
            <input
              v-model="newMessage"
              type="text"
              placeholder="Type a message…"
              @keyup.enter="sendMessage"
            />
            <button class="btn btn-primary send" @click="sendMessage" title="Send">
              <BaseIcon name="send" :size="18" />
            </button>
          </div>
        </template>
      </section>
    </div>

    <!-- Create room modal -->
    <div class="modal-overlay" v-if="showCreateModal" @click.self="closeCreateModal">
      <div class="modal">
        <h3>Create a room</h3>
        <template v-if="!createSuccessMessage">
          <input v-model="newRoomName" placeholder="Room name" />
          <textarea v-model="newRoomDescription" rows="2" placeholder="Description (optional)" />
          <select v-model="newRoomPrivacy">
            <option value="public">Public</option>
            <option value="private">Private</option>
          </select>
          <div class="modal-actions">
            <button class="btn btn-ghost" @click="closeCreateModal">Cancel</button>
            <button class="btn btn-primary" @click="createRoomConfirm">Create</button>
          </div>
        </template>
        <template v-else>
          <p class="success-message">{{ createSuccessMessage }}</p>
          <div class="modal-actions">
            <button class="btn btn-primary" @click="closeCreateModal">Got it</button>
          </div>
        </template>
      </div>
    </div>

    <!-- Search result modal -->
    <div class="modal-overlay" v-if="showSearchModal" @click.self="showSearchModal = false">
      <div class="modal">
        <template v-if="foundRoom">
          <h3>Room found</h3>
          <p class="modal-line"><strong>{{ foundRoom.name }}</strong></p>
          <p class="modal-sub">Would you like to join?</p>
          <div class="modal-actions">
            <button class="btn btn-ghost" @click="showSearchModal = false">Cancel</button>
            <button class="btn btn-primary" @click="joinChatroom(foundRoom.room_id)">Join</button>
          </div>
        </template>
        <template v-else>
          <p class="modal-line">{{ searchError }}</p>
          <div class="modal-actions">
            <button class="btn btn-primary" @click="showSearchModal = false">OK</button>
          </div>
        </template>
      </div>
    </div>

    <!-- Exit confirm modal -->
    <div class="modal-overlay" v-if="showExitConfirm" @click.self="showExitConfirm = false">
      <div class="modal">
        <h3>Leave room?</h3>
        <p class="modal-sub">Are you sure you want to leave <strong>{{ exitRoomToConfirm?.name }}</strong>?</p>
        <div class="modal-actions">
          <button class="btn btn-ghost" @click="showExitConfirm = false">Cancel</button>
          <button class="btn btn-danger" @click="confirmExitChatroom">Leave</button>
        </div>
      </div>
    </div>

    <!-- Right-click menu -->
    <ul
      v-if="contextMenuVisible"
      class="context-menu"
      :style="{ top: `${contextMenuPosition.y}px`, left: `${contextMenuPosition.x}px` }"
      @click="handleContextMenuClick"
    >
      <li @click="handleExitClick">Leave room</li>
    </ul>

    <!-- Room info drawer -->
    <Transition name="drawer">
      <RoomInfoPanel
        v-if="showRoomInfo && selectedRoom"
        :room-id="selectedRoom.id"
        @close="showRoomInfo = false"
      />
    </Transition>
  </div>
</template>


<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import api from '@/utils/http'
import Avatar from '@/components/Avatar.vue'
import BaseIcon from '@/components/BaseIcon.vue'
import RoomInfoPanel from '@/components/RoomInfoPanel.vue'
import { toast } from '@/composables/useToast'

const route = useRoute()
const router = useRouter()
const apiBase = import.meta.env.VITE_API_BASE_URL || ''
const base_wsUrl = import.meta.env.VITE_WS_BASE_URL || `${location.origin.replace(/^http/, 'ws')}/ws`
const socketReadyMap: Record<string, Promise<void>> = {}
const socketReadyResolvers: Record<string, () => void> = {}

const username = localStorage.getItem('username') || 'Unknown user'

// Current user's own profile (for the top-bar chip). Falls back to the username.
const me = ref<{ displayName: string; avatarSeed: string }>({
  displayName: username,
  avatarSeed: username,
})

// roomId → (username → profile), used to render sender name + avatar on messages.
const memberMap = ref<Record<string, Record<string, { displayName: string; avatarSeed: string }>>>({})

// ========================== Utility functions ==============================
function handleContextMenuClick() {
  // left empty to avoid TypeScript errors.
}

const addChatroomToSidebar = (room: { id: string; name: string; isPrivate: boolean }) => {
  if (!chatrooms.value.some(r => r.id === room.id)) {
    chatrooms.value.push({ id: room.id, name: room.name, isPrivate: room.isPrivate, unread: 0 })
  }
}

const scrollToBottom = () => {
  nextTick(() => {
    setTimeout(() => {
      const el = messageContainer.value
      if (el) el.scrollTop = el.scrollHeight - el.clientHeight
    }, 50)
  })
}

const isAtBottom = () => {
  const el = messageContainer.value
  if (!el) return false
  return el.scrollTop + el.clientHeight >= el.scrollHeight - 10 // tolerance 10px
}

// Resolve a sender's display name + avatar seed from the room's member map.
const senderInfo = (sender: string) => {
  const m = selectedRoom.value ? memberMap.value[selectedRoom.value.id]?.[sender] : undefined
  return { displayName: m?.displayName || sender, avatarSeed: m?.avatarSeed || sender }
}

const loadMembers = async (roomId: string) => {
  try {
    const { data } = await api.get(`/chatrooms/${roomId}/members`)
    const map: Record<string, { displayName: string; avatarSeed: string }> = {}
    for (const mem of data.members || []) {
      map[mem.username] = {
        displayName: mem.display_name || mem.username,
        avatarSeed: mem.avatar_seed || mem.username,
      }
    }
    memberMap.value[roomId] = map
  } catch (err) {
    console.error('Load members failed:', err)
  }
}

function formatTime(ts?: string) {
  if (!ts) return ''
  const d = new Date(ts)
  if (isNaN(d.getTime())) return ''
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

function formatDate(d: Date) {
  const today = new Date()
  const yesterday = new Date()
  yesterday.setDate(today.getDate() - 1)
  if (d.toDateString() === today.toDateString()) return 'Today'
  if (d.toDateString() === yesterday.toDateString()) return 'Yesterday'
  return d.toLocaleDateString([], { year: 'numeric', month: 'short', day: 'numeric' })
}

// Build render items: group consecutive messages by same sender / time, and
// insert a date separator when the day changes.
const renderItems = computed(() => {
  const list = messages.value
  const items: {
    key: number
    msg: ChatMessage
    dateLabel: string | null
    showHeader: boolean
    own: boolean
  }[] = []
  let prev: { sender: string; timestamp?: string } | null = null
  for (let i = 0; i < list.length; i++) {
    const m = list[i]
    const d = m.timestamp ? new Date(m.timestamp) : null
    const prevD = prev?.timestamp ? new Date(prev.timestamp) : null
    const validD = d && !isNaN(d.getTime())
    const validPrevD = prevD && !isNaN(prevD.getTime())
    const newDay = !validPrevD || (validD && d!.toDateString() !== prevD!.toDateString())
    const senderChanged = !prev || prev.sender !== m.sender
    const gap = validD && validPrevD ? d!.getTime() - prevD!.getTime() > 5 * 60 * 1000 : true
    items.push({
      key: i,
      msg: m,
      dateLabel: newDay && validD ? formatDate(d!) : null,
      showHeader: newDay || senderChanged || gap,
      own: m.sender === username,
    })
    prev = m
  }
  return items
})

// ========================== Load chatrooms after login ==========================

onMounted(async () => {
  // Load own profile for the top-bar chip.
  try {
    const { data } = await api.get('/me')
    me.value = {
      displayName: data.display_name || username,
      avatarSeed: data.avatar_seed || username,
    }
  } catch (err) {
    console.error('Load profile failed:', err)
  }

  try {
    const res = await api.get(`/chatrooms/user/${username}`)
    const rooms = res.data.rooms || []
    rooms.forEach((room: any) => {
      const roomId = room.room_id || room.id // Prefer room.room_id
      if (!roomId || typeof roomId !== 'string') {
        console.warn('Skip invalid room:', room)
        return
      }
      addChatroomToSidebar({ id: roomId, name: room.name, isPrivate: room.isPrivate })
      messageMap.value[roomId] = []
      connectWebSocket(roomId)
    })

    // Restore the active room from the URL now that the sidebar list is loaded
    // (e.g. after a page refresh, or opening a shared /chatroom/:roomId link).
    syncSelectedRoomFromRoute()
  } catch (err) {
    console.error('Load chatroom failed:', err)
  }
})

// Keep `selectedRoom` in sync with the URL: handles refresh, shared links,
// and browser back/forward between rooms.
const syncSelectedRoomFromRoute = () => {
  const urlRoomId = route.params.roomId as string | undefined
  if (!urlRoomId) {
    selectedRoom.value = null
    return
  }
  const match = chatrooms.value.find(room => room.id === urlRoomId)
  if (match) {
    activateRoom(match)
  } else {
    router.replace('/chatroom')
  }
}

watch(() => route.params.roomId, () => {
  syncSelectedRoomFromRoute()
})

// search chatroom to join
const searchRoomId = ref('')
const showSearchModal = ref(false)
const foundRoom = ref<{ room_id: string; name: string } | null>(null)
const searchError = ref('')

const handleSearchRoom = async () => {
  if (!searchRoomId.value.trim()) return
  try {
    const response = await api.get(`${apiBase}/chatrooms/${searchRoomId.value.trim()}`)
    foundRoom.value = response.data
    searchError.value = ''
    showSearchModal.value = true
  } catch (err) {
    foundRoom.value = null
    searchError.value = 'No such room. Check the room ID (private rooms are invite-only).'
    showSearchModal.value = true
  }
}

const joinChatroom = async (roomId: string) => {
  try {
    await api.post(`${apiBase}/chatrooms/join`, { username, chatroom_id: roomId })
    addChatroomToSidebar({ id: roomId, name: foundRoom.value?.name || 'Unknown', isPrivate: false })
    messageMap.value[roomId] = []
    showSearchModal.value = false
    searchRoomId.value = ''
    selectRoom(chatrooms.value.find(room => room.id === roomId)!)
    toast.success('Joined room')
  } catch (err: any) {
    const status = err.response?.status
    showSearchModal.value = false
    toast.error(status === 403 ? 'This room is private' : 'Failed to join the room')
  }
}

const sockets = ref<Record<string, WebSocket>>({})
const chatrooms = ref<{ id: string; name: string; isPrivate: boolean; unread: number }[]>([])

const selectedRoom = ref<null | typeof chatrooms.value[0]>(null)
const showRoomInfo = ref(false)
const newMessage = ref('')
type ChatMessage = {
  sender: string
  text: string
  timestamp?: string
  id?: string
  // Set only for messages this client itself sent, while waiting on delivery confirmation.
  status?: 'pending' | 'failed'
}
const messageMap = ref<Record<string, ChatMessage[]>>({})
const messages = computed(() =>
  selectedRoom.value ? messageMap.value[selectedRoom.value.id] || [] : []
)

// Outbox for messages awaiting delivery confirmation: roomId -> clientMsgId -> retry state.
// No local broadcast happens server-side anymore (tmp_doc/05 P2) — every message, including
// the sender's own copy, arrives back through the same Kafka round trip everyone else's
// does. If that round trip is slow or the 'ack'/'send_error' response itself gets lost, this
// resends the exact same id (server-side dedup collapses it, so a resend never shows up as a
// second bubble for anyone in the room).
type PendingEntry = { id: string; text: string; timerId: number; retries: number }
const pendingByRoom = ref<Record<string, Record<string, PendingEntry>>>({})
const RESEND_TIMEOUT_MS = 5000
const MAX_RESENDS = 3

function stopResendTimer(roomId: string, id: string | undefined) {
  if (!id) return
  const entry = pendingByRoom.value[roomId]?.[id]
  if (entry) {
    clearTimeout(entry.timerId)
    delete pendingByRoom.value[roomId][id]
  }
}

function markMessageFailed(roomId: string, id: string | undefined) {
  if (!id) return
  const msg = messageMap.value[roomId]?.find(m => m.id === id)
  if (msg) msg.status = 'failed'
}

function sendEnvelope(roomId: string, id: string, text: string, retries: number) {
  const socket = sockets.value[roomId]
  if (!socket || socket.readyState !== WebSocket.OPEN) {
    markMessageFailed(roomId, id)
    return
  }
  socket.send(JSON.stringify({ type: 'message', id, text }))

  const timerId = window.setTimeout(() => {
    if (retries >= MAX_RESENDS) {
      markMessageFailed(roomId, id)
    } else {
      sendEnvelope(roomId, id, text, retries + 1)
    }
  }, RESEND_TIMEOUT_MS)

  if (!pendingByRoom.value[roomId]) pendingByRoom.value[roomId] = {}
  pendingByRoom.value[roomId][id] = { id, text, timerId, retries }
}

// Click a failed bubble to resend it (same id — still idempotent server-side).
const retryMessage = (roomId: string, id: string | undefined) => {
  if (!id) return
  const msg = messageMap.value[roomId]?.find(m => m.id === id)
  if (!msg || msg.status !== 'failed') return
  msg.status = 'pending'
  sendEnvelope(roomId, id, msg.text, 0)
}

// Rooms currently mid-connect. Marked synchronously (before the first await)
// so a second concurrent call for the same room bails out immediately instead
// of racing past the `sockets.value[roomId]` check and opening a duplicate
// socket (which caused messages to appear twice — both sockets receive the
// server's broadcast and each pushes into the same messageMap).
const connectingRooms = new Set<string>()

const connectWebSocket = async (roomId: string) => {
  if (sockets.value[roomId] || connectingRooms.has(roomId)) return
  connectingRooms.add(roomId)

  try {
    const res = await api.get(`${apiBase}/chatrooms/${roomId}/enter`, { params: { username } })
    if (res.status !== 200) {
      console.error('Failed to retrieve WebSocket URL:', res.statusText)
      return
    }
    if (!res.data || !res.data.ws_url) {
      console.error('Invalid WebSocket URL:', res.data)
      return
    }

    const final_wsurl = `${base_wsUrl}/${roomId}?username=${username}&token=${localStorage.getItem('token')}`
    const socket = new WebSocket(final_wsurl)
    sockets.value[roomId] = socket

    socketReadyMap[roomId] = new Promise<void>((resolve) => {
      socketReadyResolvers[roomId] = resolve
    })

    socket.onopen = () => {
      socketReadyResolvers[roomId]()
      if (!messageMap.value[roomId]) messageMap.value[roomId] = []
    }

    socket.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data)
        if (!messageMap.value[roomId]) messageMap.value[roomId] = []

        switch (msg.type) {
          case 'message': {
            const normalizedMsg: ChatMessage = {
              id: msg.id,
              sender: msg.sender,
              text: msg.text,
              timestamp: msg.sentAt || msg.timestamp,
            }
            const list = messageMap.value[roomId]
            const existingIdx = msg.id ? list.findIndex(m => m.id === msg.id) : -1
            if (existingIdx !== -1) {
              // Our own pending/failed placeholder just came back confirmed through the
              // full round trip (server no longer broadcasts locally at send time — see
              // tmp_doc/05 P2) — replace it in place instead of showing a duplicate bubble.
              stopResendTimer(roomId, msg.id)
              list[existingIdx] = normalizedMsg
            } else {
              list.push(normalizedMsg)
            }
            if (isAtBottom()) scrollToBottom()
            break
          }
          case 'ack':
            // Reached Kafka — stop the resend timer. The bubble stays as-is until the
            // 'message' case above replaces it with the fully round-tripped copy.
            stopResendTimer(roomId, msg.id)
            break
          case 'send_error':
            stopResendTimer(roomId, msg.id)
            markMessageFailed(roomId, msg.id)
            break
          case 'history_result':
            // Handled by fetchHistoryViaWebSocket.
            break
          default:
            console.warn('Unknown message type:', msg.type)
        }
      } catch (err) {
        console.error('Failed to parse message:', err)
      }
    }

    socket.onerror = (error) => {
      console.error('WebSocket error:', error)
      toast.error('Connection error. Please check your network.')
    }

    socket.onclose = () => {
      delete sockets.value[roomId]
    }
  } finally {
    connectingRooms.delete(roomId)
  }
}

// activate a room in the UI (connect socket, reset unread, scroll) — does NOT
// touch the URL, so it's safe to call when syncing from a route change too.
const activateRoom = async (room: typeof chatrooms.value[0]) => {
  selectedRoom.value = room
  if (!messageMap.value[room.id]) messageMap.value[room.id] = []
  room.unread = 0
  loadMembers(room.id)
  connectWebSocket(room.id)

  await socketReadyMap[room.id]
  scrollToBottom()
}

// select room — called from user interaction (sidebar click, join, create).
// Updates the URL so refresh/back-forward/shared links restore the room.
const selectRoom = (room: typeof chatrooms.value[0]) => {
  if (route.params.roomId !== room.id) {
    router.push(`/chatroom/${room.id}`)
  }
  activateRoom(room)
}

const sendMessage = () => {
  if (!newMessage.value.trim() || !selectedRoom.value) return
  const roomId = selectedRoom.value.id
  const socket = sockets.value[roomId]
  if (socket?.readyState !== WebSocket.OPEN) return

  const id = crypto.randomUUID()
  const text = newMessage.value.trim()
  newMessage.value = ''

  // Optimistic pending bubble — the confirmed copy (same id) replaces this once it comes
  // back through the full Kafka round trip (see the 'message' case in connectWebSocket).
  if (!messageMap.value[roomId]) messageMap.value[roomId] = []
  messageMap.value[roomId].push({ id, sender: username, text, timestamp: new Date().toISOString(), status: 'pending' })
  scrollToBottom()

  sendEnvelope(roomId, id, text, 0)
}

onBeforeUnmount(() => {
  Object.values(sockets.value).forEach(s => s.close())
})

const logout = async () => {
  try {
    await api.post('/logout')
  } catch (err) {
    // proceed with local cleanup regardless
  }
  localStorage.removeItem('username')
  localStorage.removeItem('token')
  toast.success('Logged out')
  router.push('/login')
}

// create chatroom
const showCreateModal = ref(false)
const newRoomName = ref('')
const newRoomDescription = ref('')
const newRoomPrivacy = ref<'public' | 'private'>('public')
const createSuccessMessage = ref('')

const createRoomConfirm = async () => {
  if (!newRoomName.value.trim()) {
    toast.error('Room name cannot be empty')
    return
  }
  try {
    const response = await api.post(`${apiBase}/chatrooms`, {
      name: newRoomName.value.trim(),
      description: newRoomDescription.value.trim(),
      is_private: newRoomPrivacy.value === 'private',
      created_by: username,
    })

    const roomId = response.data.room_id
    createSuccessMessage.value = `Room created! Its ID is ${roomId} — share it so others can join.`
    const newRoom = {
      id: roomId,
      name: newRoomName.value.trim(),
      isPrivate: newRoomPrivacy.value === 'private',
      unread: 0,
    }
    addChatroomToSidebar(newRoom)
    messageMap.value[roomId] = []
    selectRoom(newRoom)
  } catch (error) {
    toast.error('Creation failed. Please try again later.')
    console.error('Creation failed:', error)
  }
}

const closeCreateModal = () => {
  showCreateModal.value = false
  createSuccessMessage.value = ''
  newRoomName.value = ''
  newRoomDescription.value = ''
  newRoomPrivacy.value = 'public'
}

const messageContainer = ref<HTMLElement | null>(null)
const noMoreMessages = ref<Record<string, boolean>>({})
const loadingHistory = ref(false)
const pageSize = 20

const loadHistory = async (roomId: string) => {
  if (loadingHistory.value || noMoreMessages.value[roomId]) return
  loadingHistory.value = true

  const existing = messageMap.value[roomId] || []
  const lastTimestamp = existing.length > 0 ? existing[0].timestamp : ''

  try {
    const older = await fetchHistoryViaWebSocket(roomId, lastTimestamp, pageSize)
    if (!Array.isArray(older) || older.length === 0) {
      noMoreMessages.value[roomId] = true
      return
    }

    const normalizedOlder = older
      .map(msg => ({
        sender: msg.sender || msg.Sender, // Compatible with DynamoDB and real-time message
        text: msg.text || msg.Text,
        timestamp: msg.timestamp || msg.sentAt,
        roomId: msg.room_id || msg.roomID,
      }))
      .filter(msg => typeof msg.text === 'string' && typeof msg.sender === 'string')

    messageMap.value[roomId] = [...normalizedOlder.reverse(), ...(messageMap.value[roomId] || [])]
  } catch (e) {
    console.error('load history failed', e)
  } finally {
    loadingHistory.value = false
  }
}

function fetchHistoryViaWebSocket(roomId: string, before: string | undefined, limit: number): Promise<any[]> {
  return new Promise((resolve, reject) => {
    const socket = sockets.value[roomId]
    if (!socket) {
      reject(new Error('WebSocket is not connected'))
      return
    }

    const handler = (event: MessageEvent) => {
      try {
        const data = JSON.parse(event.data)
        if (data.type === 'history_result' && data.roomID === roomId) {
          socket.removeEventListener('message', handler)
          resolve(Array.isArray(data.messages) ? data.messages : [])
        }
      } catch (e) {
        console.error('Failed to parse WebSocket message:', e)
        resolve([])
      }
    }

    socket.addEventListener('message', handler)
    const request = { type: 'fetch_history', roomID: roomId, before, limit }
    socket.send(JSON.stringify(request))

    setTimeout(() => {
      socket.removeEventListener('message', handler)
      reject(new Error('Fetching historical messages timed out'))
    }, 5000)
  })
}

// exit chatroom
const showExitConfirm = ref(false)
const exitRoomToConfirm = ref<{ id: string; name: string } | null>(null)
const contextMenuPosition = ref({ x: 0, y: 0 })
const contextMenuVisible = ref(false)
const contextMenuRoom = ref<null | typeof chatrooms.value[0]>(null)

const openContextMenu = (e: MouseEvent, room: typeof chatrooms.value[0]) => {
  contextMenuVisible.value = true
  contextMenuRoom.value = room
  contextMenuPosition.value = { x: e.clientX, y: e.clientY }
}

const handleExitClick = () => {
  exitRoomToConfirm.value = contextMenuRoom.value
  showExitConfirm.value = true
  contextMenuVisible.value = false
}

document.addEventListener('click', () => {
  contextMenuVisible.value = false
})

const confirmExitChatroom = async () => {
  if (!exitRoomToConfirm.value) return
  try {
    await api.post(`${apiBase}/chatrooms/exit`, {
      username,
      chatroom_id: exitRoomToConfirm.value.id,
    })

    chatrooms.value = chatrooms.value.filter(r => r.id !== exitRoomToConfirm.value?.id)

    const socket = sockets.value[exitRoomToConfirm.value.id]
    if (socket) {
      socket.close()
      delete sockets.value[exitRoomToConfirm.value.id]
    }
    delete messageMap.value[exitRoomToConfirm.value.id]

    if (selectedRoom.value?.id === exitRoomToConfirm.value.id) {
      selectedRoom.value = null
      router.push('/chatroom')
    }

    showExitConfirm.value = false
    exitRoomToConfirm.value = null
    toast.success('Left the room')
  } catch (err) {
    console.error('Failed to exit the chatroom:', err)
    toast.error('Failed to leave the room')
  }
}
</script>


<style scoped>
.chatroom {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  background-color: var(--bg);
  color: var(--text);
}

/* top bar */
.top-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 10px 16px;
  background-color: var(--surface-1);
  border-bottom: 1px solid var(--border);
}
.search {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  max-width: 380px;
  padding: 0 12px;
  background-color: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--r-full);
  color: var(--text-faint);
}
.search input {
  border: none;
  background: transparent;
  padding: 9px 0;
}
.search input:focus {
  box-shadow: none;
}
.user-area {
  display: flex;
  align-items: center;
  gap: 8px;
}
.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 10px 4px 4px;
  background-color: transparent;
  border: 1px solid transparent;
  border-radius: var(--r-full);
  cursor: pointer;
  color: var(--text);
  width: auto;
}
.user-chip:hover {
  background-color: var(--surface-2);
}
.chip-name {
  font-size: 14px;
  font-weight: 500;
}
.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
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

/* layout */
.main {
  display: flex;
  flex: 1;
  overflow: hidden;
}

/* sidebar */
.sidebar {
  width: 260px;
  display: flex;
  flex-direction: column;
  background-color: var(--surface-1);
  border-right: 1px solid var(--border);
}
.sidebar-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.room-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px;
  background-color: transparent;
  border: none;
  border-radius: var(--r-md);
  cursor: pointer;
  color: var(--text);
  text-align: left;
  width: 100%;
}
.room-item:hover {
  background-color: var(--surface-2);
}
.room-item.active {
  background-color: var(--accent-soft);
}
.room-meta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.room-name {
  font-size: 14px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.room-type {
  font-size: 12px;
  color: var(--text-faint);
}
.unread {
  background-color: var(--accent);
  color: #fff;
  border-radius: var(--r-full);
  padding: 1px 7px;
  font-size: 11px;
  font-weight: 600;
}
.create-room {
  margin: 8px;
  justify-content: center;
}

/* chat window */
.chat {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background-color: var(--bg);
}
.empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  color: var(--text-faint);
}
.empty-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 72px;
  height: 72px;
  border-radius: var(--r-lg);
  background-color: var(--surface-1);
  color: var(--text-muted);
}
.chat-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 20px;
  border-bottom: 1px solid var(--border);
  background-color: var(--surface-1);
}
.chat-header-meta {
  display: flex;
  flex-direction: column;
}
.chat-title {
  font-size: 15px;
  font-weight: 600;
}
.chat-sub {
  font-size: 12px;
  color: var(--text-faint);
}
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.date-sep {
  text-align: center;
  margin: 16px 0 10px;
}
.date-sep span {
  font-size: 12px;
  color: var(--text-faint);
  background-color: var(--surface-2);
  padding: 3px 10px;
  border-radius: var(--r-full);
}
.msg-row {
  display: flex;
  gap: 10px;
  padding: 1px 0;
  max-width: 78%;
}
.msg-row.grouped {
  margin-top: 0;
}
.msg-row:not(.grouped) {
  margin-top: 10px;
}
.msg-row.own {
  flex-direction: row-reverse;
  align-self: flex-end;
}
.msg-avatar {
  width: 36px;
  flex-shrink: 0;
}
.msg-body {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.msg-row.own .msg-body {
  align-items: flex-end;
}
.msg-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.msg-row.own .msg-head {
  flex-direction: row-reverse;
}
.msg-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
}
.msg-time {
  font-size: 11px;
  color: var(--text-faint);
}
.msg-bubble {
  padding: 8px 12px;
  border-radius: var(--r-md);
  font-size: 14px;
  line-height: 1.4;
  background-color: var(--surface-2);
  color: var(--text);
  word-break: break-word;
  white-space: pre-wrap;
}
.msg-row.own .msg-bubble {
  color: var(--on-accent);
  background-image: linear-gradient(135deg, var(--accent), var(--accent-2));
}
.msg-bubble.pending {
  opacity: 0.6;
}
.msg-bubble.failed {
  opacity: 0.75;
  cursor: pointer;
  outline: 1px solid var(--danger);
}
.msg-status.failed {
  font-size: 11px;
  color: var(--danger);
}
.history-loader {
  text-align: center;
  font-size: 13px;
  color: var(--accent);
  cursor: pointer;
  padding: 6px;
}
.history-loader.no-more {
  color: var(--text-faint);
  cursor: default;
}

/* composer */
.composer {
  display: flex;
  gap: 10px;
  padding: 14px 20px;
  border-top: 1px solid var(--border);
  background-color: var(--surface-1);
}
.composer input {
  flex: 1;
}
.send {
  width: 44px;
  padding: 0;
  flex-shrink: 0;
}

/* modals */
.modal-overlay {
  position: fixed;
  inset: 0;
  background-color: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 999;
}
.modal {
  width: 360px;
  max-width: 90vw;
  padding: 24px;
  background-color: var(--surface-1);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-2);
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.modal h3 {
  margin: 0;
  font-size: 17px;
}
.modal-line {
  margin: 0;
  font-size: 15px;
}
.modal-sub {
  margin: 0;
  font-size: 13px;
  color: var(--text-muted);
}
.success-message {
  margin: 0;
  font-size: 14px;
  color: var(--text);
  line-height: 1.5;
}
.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 4px;
}

/* context menu */
.context-menu {
  position: fixed;
  background-color: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  padding: 4px;
  min-width: 140px;
  z-index: 9999;
  list-style: none;
  margin: 0;
  box-shadow: var(--shadow-2);
}
.context-menu li {
  padding: 8px 12px;
  font-size: 14px;
  color: var(--text);
  cursor: pointer;
  border-radius: var(--r-sm);
}
.context-menu li:hover {
  background-color: var(--danger);
  color: #fff;
}

@media (max-width: 640px) {
  .sidebar {
    width: 76px;
  }
  .room-meta,
  .chip-name,
  .create-room span {
    display: none;
  }
  .msg-row {
    max-width: 90%;
  }
}
</style>

<!-- non-scoped: transition classes must reach the RoomInfoPanel root element -->
<style>
.drawer-enter-active,
.drawer-leave-active {
  transition: opacity 0.2s ease;
}
.drawer-enter-from,
.drawer-leave-to {
  opacity: 0;
}
</style>
