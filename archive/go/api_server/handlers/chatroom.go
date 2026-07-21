package handlers

import (
	"chatroom-api/dynamodb"
	log "chatroom-api/logger"
	"encoding/hex"
	"fmt"
	"github.com/gin-gonic/gin"
	"math/rand"
	"net/http"
	"os"
	"strconv"
	"time"
)

var wsHost string

func init() {
	wsHost = os.Getenv("WS_HOST")
	if wsHost == "" {
		wsHost = "ws://localhost:8081"
	}
}
func generateRoomID() string {
	bytes := make([]byte, 6)
	_, _ = rand.Read(bytes)
	return hex.EncodeToString(bytes)
}

type JoinChatroomRequest struct {
	Username   string `json:"username"`
	ChatroomID string `json:"chatroom_id"`
}

type ExitChatroomRequest struct {
	Username   string `json:"username"`
	ChatroomID string `json:"chatroom_id"`
}

func CreateChatroom(c *gin.Context) {
	log.Log.Info("CreateChatroom")
	var req dynamodb.Chatroom
	if err := c.ShouldBindJSON(&req); err != nil {
		log.Log.Warn("Invalid parameter format (creating chatroom)")
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid parameter format"})
		return
	}
	log.Log.Infof("Verifying if the user exists: %s", req.CreatedBy)

	_, err := dynamodb.GetUserByUsername(req.CreatedBy)
	if err != nil {
		log.Log.Warnf("User does not exist: %s", req.CreatedBy)
		c.JSON(http.StatusNotFound, gin.H{"error": "User does not exist"})
		return
	}

	roomID := generateRoomID()
	log.Log.Infof("Creating chatroom: room_id=%s, created_by=%s", roomID, req.CreatedBy)
	chatroom := dynamodb.Chatroom{
		RoomID:    roomID,
		Name:      req.Name,
		IsPrivate: req.IsPrivate,
		CreatedBy: req.CreatedBy,
		CreatedAt: time.Now().Format(time.RFC3339),
		Users:     []string{req.CreatedBy}, //creator directly joins
	}

	if err := dynamodb.CreateChatroom(chatroom); err != nil {
		log.Log.Errorf("create chatroom failed: %v", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "create chatroom failed"})
		return
	}
	log.Log.Infof("create chatroom succesfully: room_id=%s", roomID)
	c.JSON(http.StatusOK, gin.H{
		"message":   "create chatroom succesfully",
		"room_id":   roomID,
		"name":      chatroom.Name,
		"isPrivate": chatroom.IsPrivate,
	})
}

func JoinChatroom(c *gin.Context) {
	var req JoinChatroomRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		log.Log.Warn("Invalid parameter format (joining chatroom)")
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid parameter format"})
		return
	}
	log.Log.Infof("user tring to join chatroom: %s -> %s", req.Username, req.ChatroomID)
	//user status check
	_, err := dynamodb.GetUserByUsername(req.Username)
	if err != nil {
		log.Log.Warnf("user not exist: %s", req.Username)
		c.JSON(http.StatusNotFound, gin.H{"error": "user not exist"})
		return
	}

	// chatroom status check
	_, err = dynamodb.GetChatroom(req.ChatroomID)
	if err != nil {
		log.Log.Warnf("chatroom not exist: %s", req.Username)
		c.JSON(http.StatusNotFound, gin.H{"error": "chatroom not exist"})
		return
	}

	// join in
	err = dynamodb.AddUserToChatroom(req.Username, req.ChatroomID)
	if err != nil {
		log.Log.Errorf("join failed: %v", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "join failed"})
		return
	}
	log.Log.Infof("user join in chatroom successfully: %s -> %s", req.Username, req.ChatroomID)
	c.JSON(http.StatusOK, gin.H{"message": "join successfully"})
}

func ExitChatroom(c *gin.Context) {
	var req ExitChatroomRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		log.Log.Warn("Invalid parameter format (exit chatroom)")
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid parameter format"})
		return
	}
	log.Log.Infof("User requests to leave the chatroom.: %s -> %s", req.Username, req.ChatroomID)
	// user status check
	_, err := dynamodb.GetUserByUsername(req.Username)
	if err != nil {
		log.Log.Warnf("user not exist: %s", req.Username)
		c.JSON(http.StatusNotFound, gin.H{"error": "user not exist"})
		return
	}

	// remove user
	err = dynamodb.RemoveUserFromChatroom(req.Username, req.ChatroomID)
	if err != nil {
		log.Log.Errorf("User failed to leave the chatroom: %v", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "exit failed"})
		return
	}
	log.Log.Infof("User successfully leave the chatroom: %s -> %s", req.Username, req.ChatroomID)
	c.JSON(http.StatusOK, gin.H{"message": "successful exit"})
}
func GetUserChatrooms(c *gin.Context) {
	username := c.Param("username")
	log.Log.Infof("get user chatrooms: %s", username)

	// user status check
	_, err := dynamodb.GetUserByUsername(username)
	if err != nil {
		log.Log.Warnf("user not exist: %s", username)
		c.JSON(http.StatusNotFound, gin.H{"error": "user not exist"})
		return
	}

	chatrooms, err := dynamodb.GetChatroomsByUsername(username)
	if err != nil {
		log.Log.Errorf("Failed to query chatroom list: %v", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	var rooms []map[string]interface{}
	for _, room := range chatrooms {
		rooms = append(rooms, gin.H{
			"id":        room.RoomID,
			"name":      room.Name,
			"isPrivate": room.IsPrivate,
		})
	}
	log.Log.Infof("user %s Total number of chatrooms joined: %d", username, len(chatrooms))
	c.JSON(http.StatusOK, gin.H{"rooms": rooms})
}
func GetChatroomMessages(c *gin.Context) {
	roomID := c.Param("roomId")
	before := c.Query("before")
	limitStr := c.DefaultQuery("limit", "20")
	username := c.Query("username")

	log.Log.Infof("Fetching chat history: user=%s, room=%s, before=%s", username, roomID, before)

	if username == "" {
		log.Log.Warn("Missing username parameter.")
		c.JSON(http.StatusBadRequest, gin.H{"error": "Missing username parameter."})
		return
	}

	if before == "" {
		before = time.Now().Format(time.RFC3339)
	}

	limit, err := strconv.Atoi(limitStr)
	if err != nil || limit <= 0 {
		limit = 20
	}

	messages, err := dynamodb.GetMessagesBefore(roomID, before, limit)
	if err != nil {
		fmt.Println("Failed to query message:", err)
		log.Log.Errorf("Failed to query message: %v", err)
		c.JSON(http.StatusOK, gin.H{"messages": []dynamodb.Message{}})
		return
	}

	if messages == nil {
		messages = []dynamodb.Message{}
	}
	log.Log.Infof("Find %d messages: room=%s", len(messages), roomID)
	c.JSON(http.StatusOK, gin.H{"messages": messages})
}

func EnterChatRoom(c *gin.Context) {
	roomID := c.Param("roomId")
	username := c.Query("username")

	log.Log.Infof("WebSocket request dispatching: user=%s, room=%s", username, roomID)

	if roomID == "" || username == "" {
		log.Log.Warn("Missing roomId or username parameter")
		c.JSON(http.StatusBadRequest, gin.H{"error": "roomId and username are required."})
		return
	}

	wsURL := fmt.Sprintf("%s/ws/%s?username=%s", wsHost, roomID, username)

	c.JSON(http.StatusOK, gin.H{
		"room_id": roomID,
		"ws_url":  wsURL,
	})
}

// For development only
//var wsIndex = 0
//var ports = []int{8081, 8081}
//
//func getNextWsHost() string {
//	port := ports[wsIndex%len(ports)]
//	wsIndex++
//	return fmt.Sprintf("ws://10.0.0.23:%d", port)
//}

func GetChatroomByRoomID(c *gin.Context) {
	roomID := c.Param("roomId")
	log.Log.Infof("Query chatroom details: room_id=%s", roomID)

	chatroom, err := dynamodb.GetChatroom(roomID)
	if err != nil {
		log.Log.Warnf("query chatroom failed: %v", err)
		c.JSON(http.StatusNotFound, gin.H{"error": "chatroom not exist"})
		return
	}
	log.Log.Infof("query successfully: room_id=%s", roomID)
	c.JSON(http.StatusOK, gin.H{
		"id":        chatroom.RoomID,
		"name":      chatroom.Name,
		"isPrivate": chatroom.IsPrivate,
	})
}
