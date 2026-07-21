<template>
  <div class="chatroom-container">
    <!-- Top bar -->
    <div class="top-bar">
      <input
        v-model="searchRoomId"
        @keyup.enter="handleSearchRoom"
        type="text"
        class="search-input"
        placeholder="Search for a chatroom (type chatroom ID and hit enter)..."
      />
      <div class="user-info">
        <span class="username">{{ username }}</span>
        <button class="logout-button" @click="logout">Logout</button>
      </div>
    </div>

    <!-- Main area -->
    <div class="main-content">
      <!-- Left-side chatroom list -->
      <div class="sidebar">
        <div
          v-for="room in chatrooms"
          :key="room.id"
          class="chatroom-item"
          :class="{ active: selectedRoom && selectedRoom.id === room.id }"
          @click="selectRoom(room)"
          @contextmenu.prevent="openContextMenu($event, room)"
        >
          <span class="room-name">{{ room.name }}</span>
          <span class="room-type">{{ room.isPrivate ? 'Private' : 'Public' }}</span>
          <span v-if="room.unread > 0" class="unread">{{ room.unread }}</span>

        </div>
        <div class="chatroom-item create-room" @click="showCreateModal = true">
          + Create New Chatroom
        </div>
      </div>

      <!-- Right-side chat window -->
      <div class="chat-window">
        <div v-if="!selectedRoom" class="placeholder-text">
          Select a chatroom to start chatting.
        </div>
        <div v-else class="chat-content">
          <h3>{{ selectedRoom.name }}</h3>
          <div class="messages" ref="messageContainer">
            <div
              v-if="selectedRoom && !noMoreMessages[selectedRoom.id]"
              class="history-loader"
              @click="loadHistory(selectedRoom.id)"
            >
              Review more history messages
            </div>
            <div
              v-else-if="selectedRoom && noMoreMessages[selectedRoom.id]"
              class="history-loader no-more"
            >
              No more history messages
            </div>

            <div
              v-for="(msg, index) in messages"
              :key="index"
              :class="['message-container', msg.sender === username ? 'own' : 'other']"
            >
              <div class="sender">{{ msg.sender }}</div>
              <div class="message-bubble">{{ msg.text }}</div>
            </div>
          </div>

          <div class="input-area">
            <input
              v-model="newMessage"
              class="message-input"
              type="text"
              placeholder="Please enter your message..."
              @keyup.enter="sendMessage"
            />
            <button class="send-button" @click="sendMessage">Send</button>
          </div>
        </div>
      </div>
    </div>

    <!-- Create Chatroom Popup -->
    <div class="modal-overlay" v-if="showCreateModal">
      <div class="modal-content">
        <h3>Create your new chatroom</h3>
        
        <!-- chatroom elements -->
        <template v-if="!createSuccessMessage">
          <input v-model="newRoomName" placeholder="Chatroom name" class="modal-input" />
          <select v-model="newRoomPrivacy" class="modal-select">
            <option value="public">Public</option>
            <option value="private">Private</option>
          </select>
          <div class="modal-buttons">
            <button @click="createRoomConfirm">Create</button>
            <button @click="showCreateModal = false">Exit</button>
          </div>
        </template>

        <!-- successful tip -->
        <template v-else>
          <p class="success-message">{{ createSuccessMessage }}</p>
          <!-- <p class="reminder">Please save your chatroom ID, which is the only way your members can find your chatroom.</p> -->
          <div class="modal-buttons">
            <button @click="closeCreateModal">Got it!</button>
          </div>
        </template>
      </div>
    </div>

    <!-- Search Chatroom Result Popup -->
    <div class="modal-overlay" v-if="showSearchModal">
      <div class="modal-content">
        <!-- successful -->
        <div v-if="foundRoom">
          <h3>We've found your chatroom</h3>
          <p><strong>Name:</strong> {{ foundRoom.name }}</p>
          <p>Would you like to join in?</p>
          <div class="modal-buttons">
            <button @click="joinChatroom(foundRoom.room_id)">Join</button>
            <button @click="showSearchModal = false">Exit</button>
          </div>
        </div>

        <!-- fail -->
        <div v-else>
          <p>{{ searchError }}</p>
          <div class="modal-buttons">
            <button @click="showSearchModal = false">OK</button>
          </div>
        </div>
      </div>
    </div>

    <!-- exit chatroom -->
    <div v-if="showExitConfirm" class="modal-overlay">
      <div class="modal-content">
        <h3>Exit this chatroom?</h3>
        <p>Are you sure you want to leave <strong>{{ exitRoomToConfirm?.name }}</strong>?</p>
        <div class="modal-buttons">
          <button @click="confirmExitChatroom">Confirm</button>
          <button @click="showExitConfirm = false">Cancel</button>
        </div>
      </div>
    </div>

    <!-- Right-click Menu -->
    <ul
      v-if="contextMenuVisible"
      class="context-menu"
      :style="{ top: `${contextMenuPosition.y}px`, left: `${contextMenuPosition.x}px` }"
      @click="handleContextMenuClick"
    >
      <li @click="handleExitClick">Exit Chatroom</li>
    </ul>



  </div>
</template>


<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
//import axios from 'axios'
import api from '@/utils/http'
const apiBase = import.meta.env.VITE_API_BASE_URL || '';
const base_wsUrl = import.meta.env.VITE_WS_BASE_URL || `${location.origin.replace(/^http/, 'ws')}/ws`
// const socketMap: Record<string, WebSocket> = {}
const socketReadyMap: Record<string, Promise<void>> = {}
const socketReadyResolvers: Record<string, () => void> = {}
// ========================== Utility function ==============================
function handleContextMenuClick() {
  // left empty to avoid TypeScript errors.
}
// Add chatroom to sidebar
const addChatroomToSidebar = (room: { id: string, name: string, isPrivate: boolean }) => {
  if (!chatrooms.value.some(r => r.id === room.id)) {
    chatrooms.value.push({
      id: room.id,
      name: room.name,
      isPrivate: room.isPrivate,
      unread: 0,
    })
  }
}


const scrollToBottom = () => {
  nextTick(() => {
    setTimeout(() => {
      const el = messageContainer.value
      if (el) {
        el.scrollTop = el.scrollHeight - el.clientHeight
        console.log(
          'Scroll to bottom scrollTop:',
          el.scrollTop,
          'scrollHeight:',
          el.scrollHeight,
          'clientHeight:',
          el.clientHeight
        )
      }
    }, 50)
  })
}

const isAtBottom = () => {
  const el = messageContainer.value
  if (!el) return false
  return el.scrollTop + el.clientHeight >= el.scrollHeight - 10 // tolerance 10px
}





// ========================== Load chatrooms after login ==========================

onMounted(async () => {
  console.log('Chatroom.vue mounted')
  try {
    //const res = await axios.get(`${apiBase}/chatrooms/user/${username}`)
    console.log('token in Chatroom.vue', localStorage.getItem('token'))
    console.log('Plan to send a request to fetch the chatroom list')
    //const res = await axios.get(`/api/chatrooms/user/${username}`)
    const res = await api.get(`/chatrooms/user/${username}`)

    const rooms = res.data.rooms || []
    rooms.forEach((room: any) => {
      const roomId = room.room_id || room.id //Prefer using room.room_id
      if (!roomId || typeof roomId !== 'string') {
        console.warn('Skip invalid room:', room)
        return
      }

      const normalizedRoom = {
        id: roomId,
        name: room.name,
        isPrivate: room.isPrivate,
      }

      addChatroomToSidebar(normalizedRoom)
      messageMap.value[roomId] = []
      connectWebSocket(roomId)
    })
  } catch (err) {
    console.error('Load chatroom failed:', err)
  }
})


//search chatroom to join
const searchRoomId = ref('')
const showSearchModal = ref(false)
const foundRoom = ref<{ room_id: string; name: string } | null>(null)
const searchError = ref('')

// handle searchroom
const handleSearchRoom = async () => {
  if (!searchRoomId.value.trim()) return

  try {
    const response = await api.get(`${apiBase}/chatrooms/${searchRoomId.value.trim()}`)
    foundRoom.value = response.data
    searchError.value = ''
    showSearchModal.value = true // show popup
  } catch (err) {
    foundRoom.value = null
    searchError.value = 'The chatroom does not exist. Please check your chatroom ID.'
    showSearchModal.value = true
  }
}
//join chatroom
const joinChatroom = async (roomId: string) => {
  try {
    await api.post(`${apiBase}/chatrooms/join`, {
      username,
      chatroom_id: roomId
    })

    addChatroomToSidebar({
      id: roomId,
      name: foundRoom.value?.name || 'Unknown',
      isPrivate: false,
    })
    messageMap.value[roomId] = []
    showSearchModal.value = false
    searchRoomId.value = ''
    selectRoom(chatrooms.value.find(room => room.id === roomId)!)
  } catch (err) {
    console.error('join chatroom failed：', err)
    searchError.value = 'Failed to join the chatroom. Please try again later.'
    foundRoom.value = null
  }
}


const username = localStorage.getItem('username') || 'Unknown user'
//const socket = ref<WebSocket | null>(null)
// const sockets = ref<{ [key: string]: WebSocket }>({})
const sockets = ref<Record<string, WebSocket>>({}) 

const chatrooms = ref<{ id: string; name: string; isPrivate: boolean; unread: number }[]>([])
// const forcePort = ref<number | null>(null)

//choose a chatroom to start chatting
const selectedRoom = ref<null | typeof chatrooms.value[0]>(null)
//const messages = ref<{ sender: string; text: string }[]>([])
const newMessage = ref('')
//const messageMap = ref<Record<string, { sender: string; text: string }[]>>({})
const messageMap = ref<Record<string, { sender: string; text: string; timestamp?: string }[]>>({}) 
const messages = computed(() =>
  selectedRoom.value ? messageMap.value[selectedRoom.value.id] || [] : []
)

// Establish WebSocket connection

const connectWebSocket = async (roomId: string) => {
    if (sockets.value[roomId]) return;

    const res = await api.get(`${apiBase}/chatrooms/${roomId}/enter`, {
        params: { username }
    });
    if (res.status !== 200) {
        console.error('Failed to retrieve WebSocket URL:', res.statusText);
        return;
    }
    if (!res.data || !res.data.ws_url) {
        console.error('Invalid WebSocket URL:', res.data);
        return;
    }
    // console.log('retrieve WebSocket URL:', res.data.ws_url);
    // const wsUrl = res.data.ws_url;
    // console.log("ws url:", `ws://10.0.0.23:${forcePort.value}/ws/${roomId}?username=${username}`)
    // const wsUrl = `ws://10.0.0.23:${forcePort.value}/ws/${roomId}?username=${username}`
    // const wsUrl = `ws://3.135.215.221/ws/${roomId}?username=${username}`
    console.log("base_wsUrl:", base_wsUrl)
    var final_wsurl = `${base_wsUrl}/${roomId}?username=${username}&token=${localStorage.getItem('token')}`
    console.log("ws url:", final_wsurl)
    const socket = new WebSocket(final_wsurl);
    sockets.value[roomId] = socket;

    socketReadyMap[roomId] = new Promise<void>((resolve) => {
        socketReadyResolvers[roomId] = resolve;
    });

    socket.onopen = () => {
        console.log('WebSocket connected');
        socketReadyResolvers[roomId]();
        if (!messageMap.value[roomId]) {
            messageMap.value[roomId] = [];
        }
    };

    socket.onmessage = (event) => {
        try {
            const msg = JSON.parse(event.data);
            console.log('Receive Websocket message:', msg);

            if (!messageMap.value[roomId]) messageMap.value[roomId] = [];

            // Handle based on message type
            switch (msg.type) {
                case "message":
                    // Real-time message, normalize and store into messageMap
                    const normalizedMsg = {
                        sender: msg.sender,
                        text: msg.text,
                        timestamp: msg.sentAt || msg.timestamp,
                        roomId: msg.roomID || msg.room_id,
                    };
                    messageMap.value[roomId].push(normalizedMsg);
                    if (isAtBottom()) {
                        scrollToBottom();
                    }
                    break;

                case "history_result":
                    // Historical messages are handled by fetchHistoryViaWebSocket, ignore them here.
                    console.log("Received history_result, delegate to fetchHistoryViaWebSocket for processing.");
                    break;

                default:
                    console.warn("Unknown message type:", msg.type);
            }
        } catch (err) {
            console.error('Failed to parse message：', err);
        }
    };

    socket.onerror = (error) => {
        console.error('WebSocket error:', error);
        alert('WebSocket error occurred. Please check your connection.');
    };

    socket.onclose = () => {
        console.log('WebSocket closed');
        delete sockets.value[roomId];
    };
};

// select room
const selectRoom = async (room: typeof chatrooms.value[0]) => {
  console.log('select room:', room)

  selectedRoom.value = room
  console.log("123123123:", messageMap.value[room.id]);
  if (!messageMap.value[room.id]) messageMap.value[room.id] = []//initialize
  room.unread = 0
  connectWebSocket(room.id)

  await socketReadyMap[room.id]
  console.log('WebSocket state：', sockets.value[room.id]?.readyState)
  if (sockets.value[room.id]?.readyState === WebSocket.OPEN) {
    console.log('WebSocket connected')
  } else {
    console.log('WebSocket not connected')
  }
  // loadHistory(room.id) //load history
  scrollToBottom()
}

// send message
const sendMessage = () => {
  if (!newMessage.value.trim() || !selectedRoom.value) return
  const roomId = selectedRoom.value.id
  const socket = sockets.value[roomId] 
  if (socket?.readyState === WebSocket.OPEN) {
    const msg = { type:"message", sender: username, text: newMessage.value.trim() }
    socket.send(JSON.stringify(msg))
    newMessage.value = ''
    
    scrollToBottom()
    
  }
}

// Disconnect before the page is closed
onBeforeUnmount(() => {
  // if (socket.value) {
  //   socket.value.close()
  // }
  Object.values(sockets.value).forEach(s => s.close()) 
})

const logout = () => {
  alert('You have logged out. See you next time!')
  localStorage.removeItem('username')
  localStorage.removeItem('token') 
  location.href = '/'
}

//create chatroom
const showCreateModal = ref(false)
const newRoomName = ref('')
const newRoomPrivacy = ref<'public' | 'private'>('public')
const createSuccessMessage = ref('')

const createRoomConfirm = async () => {
  if (!newRoomName.value.trim()) {
    createSuccessMessage.value = 'Chatroom name cannot be empty'
    return
  }

  try {
    const response = await api.post(`${apiBase}/chatrooms`, {
      name: newRoomName.value.trim(),
      is_private: newRoomPrivacy.value === 'private',
      created_by: username
    })

    const roomId = response.data.room_id
    createSuccessMessage.value = `Create successfully! Your chatroom ID is ${roomId}.\nPlease save your chatroom ID, which is the only way your members can find your chatroom.`
    // Add to chatroom list
    const newRoom = {
      id: roomId,
      name: newRoomName.value.trim(),
      isPrivate: newRoomPrivacy.value === 'private',
      unread: 0
    }
    //chatrooms.value.push(newRoom)
    addChatroomToSidebar(newRoom)
    messageMap.value[roomId] = []
    // select this chatroom
    selectedRoom.value = newRoom
    connectWebSocket(newRoom.id)

  } catch (error) {
    createSuccessMessage.value = 'Creation failed. Please try again later.'
    console.error('Creation failed:', error)
  }
}
//取消创建
const closeCreateModal = () => {
  showCreateModal.value = false
  createSuccessMessage.value = ''
  newRoomName.value = ''
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
  console.log("last timestamp before:", lastTimestamp)
  console.log("before fetching old", messageMap.value[roomId])

  try {
    const older = await fetchHistoryViaWebSocket(roomId, lastTimestamp, pageSize);
    console.log('load history：', older);
    console.log("before loading old", messageMap.value[roomId])
    if (!Array.isArray(older) || older.length === 0) {
        noMoreMessages.value[roomId] = true;
        return;
    }

    // Normalize message format
    const normalizedOlder = older.map(msg => ({
        sender: msg.sender || msg.Sender, // Compatible with DynamoDB and real-time message
        text: msg.text || msg.Text,
        timestamp: msg.timestamp || msg.sentAt,
        roomId: msg.room_id || msg.roomID,
    })).filter(msg => typeof msg.text === "string" && typeof msg.sender === "string");

    messageMap.value[roomId] = [
        ...normalizedOlder.reverse(),
        ...(messageMap.value[roomId] || []),
    ];
    console.log("update messageMap:", messageMap.value[roomId]);
} catch (e) {
    console.error('load history failed', e);
} finally {
    loadingHistory.value = false;
}
}

function fetchHistoryViaWebSocket(roomId: string, before: string|undefined, limit: number): Promise<any[]> {
    return new Promise((resolve, reject) => {
        const socket = sockets.value[roomId];
        if (!socket) {
            reject(new Error("WebSocket is not connected"));
            return;
        }

        const handler = (event: MessageEvent) => {
            try {
                const data = JSON.parse(event.data);
                console.log("Receive WebSocket message:", data); // raw data
                if (data.type === "history_result" && data.roomID === roomId) {
                    socket.removeEventListener("message", handler);
                    if (Array.isArray(data.messages)) {
                        console.log("handle history message:", data.messages); // after processing
                        resolve(data.messages); // only return messages
                    } else {
                        console.warn("Invalid messages field:", data);
                        resolve([]);
                    }
                }
            } catch (e) {
                console.error("Failed to parse WebSocket message:", e);
                resolve([]);
            }
        };

        socket.addEventListener("message", handler);
        const request = { type: "fetch_history", roomID: roomId, before, limit };
        console.log("before 1111", messageMap.value[roomId])

        socket.send(JSON.stringify(request));
        console.log("before 2222", messageMap.value[roomId])

        console.log("Send fetch_history request:", request);

        setTimeout(() => {
            socket.removeEventListener("message", handler);
            reject(new Error("Fetching historical messages timed out"));
        }, 5000);
    });
}


//exit chatroom
const showExitConfirm = ref(false)
const exitRoomToConfirm = ref<{ id: string; name: string } | null>(null)
const contextMenuPosition = ref({ x: 0, y: 0 })
const contextMenuVisible = ref(false)
const contextMenuRoom = ref<null | typeof chatrooms.value[0]>(null)

// open right-click menu
const openContextMenu = (e: MouseEvent, room: typeof chatrooms.value[0]) => {
  contextMenuVisible.value = true
  contextMenuRoom.value = room
  contextMenuPosition.value = { x: e.clientX, y: e.clientY }
}

// exit click
const handleExitClick = () => {
  exitRoomToConfirm.value = contextMenuRoom.value
  showExitConfirm.value = true
  contextMenuVisible.value = false
}

// Click outside the menu to hide it
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

    // remove chatroom
    chatrooms.value = chatrooms.value.filter(r => r.id !== exitRoomToConfirm.value?.id)

    // delete websocket
    const socket = sockets.value[exitRoomToConfirm.value.id]
    if (socket) {
      socket.close()
      delete sockets.value[exitRoomToConfirm.value.id]
    }

    // delete messagemap
    delete messageMap.value[exitRoomToConfirm.value.id]

    // If it's the currently selected chatroom, deselect it
    if (selectedRoom.value?.id === exitRoomToConfirm.value.id) {
      selectedRoom.value = null
    }

    // close popup
    showExitConfirm.value = false
    exitRoomToConfirm.value = null
  } catch (err) {
    console.error('Failed to exit the chatroom:', err)
    alert('Failed to exit the chatroom. Please try again later.')
  }
}


</script>


<style scoped>
.chatroom-container {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  right: 0;
  display: flex;
  flex-direction: column;
  background-color: #1e1e1e;
  color: white;
}

.top-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 20px;
  border-bottom: 1px solid #444;
  background-color: #2c2c2c;
}

.search-input {
  flex: 1;
  max-width: 300px;
  padding: 8px;
  font-size: 14px;
  background-color: #3a3a3a;
  color: white;
  border: 1px solid #555;
  border-radius: 4px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.username {
  font-weight: bold;
}

.logout-button {
  background-color: #f56c6c;
  color: white;
  border: none;
  padding: 6px 12px;
  border-radius: 4px;
  cursor: pointer;
}

.main-content {
  display: flex;
  flex: 1;
  overflow: hidden;
}

.sidebar {
  width: 220px;
  border-right: 1px solid #444;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  background-color: #2c2c2c;
}

.chatroom-item {
  padding: 10px;
  border-radius: 4px;
  cursor: pointer;
  background-color: #3a3a3a;
  color: white;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 14px;
}

.chatroom-item:hover {
  background-color: #505050;
}

.chatroom-item.active {
  background-color: #1890ff;
  color: white;
}

.room-name {
  flex: 1;
}

.room-type {
  font-size: 12px;
  color: #ccc;
  margin-left: 6px;
}

.unread {
  background-color: #f56c6c;
  color: white;
  border-radius: 10px;
  padding: 2px 6px;
  font-size: 12px;
  margin-left: 6px;
}

.create-room {
  font-weight: bold;
  color: #1890ff;
  text-align: center;
  border: 1px dashed #1890ff;
}

.chat-window {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
  background-color: #1e1e1e;
  position: relative;
}

.placeholder-text {
  color: #ccc;
  font-size: 16px;
  text-align: center;
  margin-top: 100px;
}

.chat-content h3 {
  margin-bottom: 10px;
  text-align: center;
}

.chat-content {
  flex: 1;
  display: flex; 
  flex-direction: column; 
  overflow: hidden;
  padding: 20px;
  padding-bottom: 80px;
}


.messages {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.message-container {
  display: flex;
  flex-direction: column;
  max-width: 70%;
  word-break: break-word;
}

.message-container.own {
  align-self: flex-end;
  text-align: right;
}

.message-container.other {
  align-self: flex-start;
  text-align: left;
}

.sender {
  font-size: 12px;
  color: #aaa;
  margin-bottom: 4px;
  padding: 0 10px;
}

.message-bubble {
  background-color: #3a3a3a;
  padding: 10px 14px;
  border-radius: 16px;
  font-size: 14px;
  color: white;
  display: inline-block;
  max-width: 100%;
}

.message-container.own .message-bubble {
  background-color: #1890ff;
  color: white;
  border-top-right-radius: 0;
}

.message-container.other .message-bubble {
  background-color: #3a3a3a;
  color: white;
  border-top-left-radius: 0;
}

.input-area {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  display: flex;
  gap: 8px;
  padding: 12px 20px;
  background-color: #1e1e1e;
  border-top: 1px solid #333;
}

.message-input {
  flex: 1;
  padding: 12px;
  font-size: 16px;
  background-color: #2c2c2c;
  border: 1px solid #555;
  border-radius: 4px;
  color: white;
}

.send-button {
  padding: 12px 18px;
  background-color: #1890ff;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}

.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0,0,0,0.6);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 999;
}

.modal-content {
  background-color: #2c2c2c;
  padding: 20px 30px;
  border-radius: 10px;
  width: 320px;
  color: white;
  text-align: center;
  box-shadow: 0 0 10px #000;
}

.modal-input, .modal-select {
  width: 100%;
  margin: 10px 0;
  padding: 10px;
  font-size: 14px;
  background-color: #1e1e1e;
  color: white;
  border: 1px solid #555;
  border-radius: 4px;
}

.modal-buttons {
  display: flex;
  justify-content: center;
  gap: 12px;
  margin-top: 10px;
}

.modal-buttons button {
  padding: 8px 16px;
  border: none;
  cursor: pointer;
  border-radius: 4px;
  background-color: #1890ff;
  color: white;
}

.modal-buttons button:last-child {
  background-color: #f56c6c;
}

.success-message {
  color: white;
  margin-top: 10px;
}

.history-loader {
  text-align: center;
  font-size: 14px;
  color: #1890ff;
  cursor: pointer;
  text-decoration: underline;
  margin-bottom: 12px;
  transition: opacity 0.3s;
}

.history-loader.no-more {
  color: #aaa;
  cursor: default;
  text-decoration: none;
} 

.context-menu {
  position: fixed;
  background-color: #2c2c2c;
  border: 1px solid #444;
  border-radius: 4px;
  padding: 6px 0;
  width: 160px;
  z-index: 9999;
  list-style: none;
}

.context-menu li {
  padding: 8px 16px;
  color: white;
  cursor: pointer;
}

.context-menu li:hover {
  background-color: #3a3a3a;
}


</style>

<style>
html, body {
  margin: 0;
  padding: 0;
  height: 100%;
  background-color: #1e1e1e;
}
</style>
