package handlers

import (
	//"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"chatroom-api/dynamodb"
	log "chatroom-api/logger"
	"chatroom-api/redis"
	"chatroom-api/utils"
	"context"
	"github.com/gin-gonic/gin"
	"net/http"
	"strings"
	"time"
)

var ctx = context.Background()

type RegisterRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

func Register(c *gin.Context) {
	var req RegisterRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		log.Log.Warn("Invalid registration parameter format.")
		c.JSON(http.StatusBadRequest, gin.H{"error": "format error"})
		return
	}
	log.Log.Infof("User registration request: %s", req.Username)

	user := dynamodb.User{
		Username: req.Username,
		Password: req.Password,
	}

	err := dynamodb.CreateUser(user)
	if err != nil {
		log.Log.Warnf("user create failed: %v", err)

		if strings.Contains(err.Error(), "ConditionalCheckFailed") {
			log.Log.Infof("Username already exists: %s", req.Username)
			c.JSON(http.StatusConflict, gin.H{"error": "Username already exists"})
		} else {
			log.Log.Errorf("sign up failed (system err): %v", err)
			c.JSON(http.StatusInternalServerError, gin.H{"error": "sign up failed"})
		}
		return
	}
	log.Log.Infof("sign up successfully: %s", req.Username)
	c.JSON(http.StatusOK, gin.H{"message": "sign up successfully"})
}
func HealthCheck(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"status": "ok"})
}

func Login(c *gin.Context) {
	log.Log.Info("Login Hit!")
	var req dynamodb.User
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "wrong request format"})
		return
	}

	// get user
	user, err := dynamodb.GetUserByUsername(req.Username)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "username not exist"})
		return
	}

	// password
	if user.Password != req.Password {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "wrong password"})
		return
	}

	token, err := utils.GenerateToken(req.Username)
	if err != nil {
		log.Log.Errorf("Token generated failed: %v", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Token generated failed"})
		return
	}
	log.Log.Infof("login success: %s，Token generated", req.Username)

	redis.Rdb.Set(ctx, "token:"+token, req.Username, 24*time.Hour)

	c.JSON(http.StatusOK, gin.H{
		"message":  "login success",
		"username": req.Username,
		"token":    token,
	})

}
