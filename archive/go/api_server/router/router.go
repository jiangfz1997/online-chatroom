package router

import (
	"chatroom-api/handlers"
	log "chatroom-api/logger"
	"chatroom-api/middleware"
	"github.com/gin-contrib/cors" // CORS middleware
	"github.com/gin-gonic/gin"
	"time"
)

// SetupRouter
func SetupRouter() *gin.Engine {
	log.Log.Info("Initialize the routing engine.")
	r := gin.Default()

	// CORS middleware
	log.Log.Info("enable CORS")
	r.Use(cors.New(cors.Config{
		AllowOrigins:     []string{"*"},
		AllowMethods:     []string{"GET", "POST", "OPTIONS"},
		AllowHeaders:     []string{"Origin", "Content-Type", "Authorization"},
		AllowCredentials: true,
		MaxAge:           12 * time.Hour,
	}))
	api := r.Group("/api")
	// register API
	log.Log.Info("register public API: /register, /login")
	api.POST("/register", handlers.Register)
	api.POST("/login", handlers.Login)
	api.GET("/health", handlers.HealthCheck)

	log.Log.Info("Register protected API group (requires authentication)")
	auth := api.Group("/")
	auth.Use(middleware.AuthMiddleware())

	auth.POST("/chatrooms", handlers.CreateChatroom)
	auth.POST("/chatrooms/join", handlers.JoinChatroom)
	auth.POST("/chatrooms/exit", handlers.ExitChatroom)
	auth.GET("/chatrooms/user/:username", handlers.GetUserChatrooms)
	auth.GET("/chatrooms/:roomId", handlers.GetChatroomByRoomID)
	auth.GET("/messages/:roomId", handlers.GetChatroomMessages)
	auth.GET("/chatrooms/:roomId/enter", handlers.EnterChatRoom)

	log.Log.Info("All routes have been registered.")
	return r
}
