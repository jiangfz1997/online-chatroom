package middleware

import (
	log "chatroom-api/logger"
	"chatroom-api/utils"
	"github.com/gin-gonic/gin"
	"net/http"
	"strings"
)

// Authentication middleware: Validate JWT Token
func AuthMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" || !strings.HasPrefix(authHeader, "Bearer ") {
			log.Log.Warn("Authentication failed: Missing or incorrectly formatted Authorization header.")
			c.JSON(http.StatusUnauthorized, gin.H{"error": "Missing or invalid Authorization header."})
			c.Abort()
			return
		}

		// token
		tokenString := strings.TrimPrefix(authHeader, "Bearer ")

		// get username
		username, err := utils.ParseToken(tokenString)
		if err != nil {
			log.Log.Warnf("Authentication failed: Token is invalid or expired.err=%v", err)
			c.JSON(http.StatusUnauthorized, gin.H{"error": "Token is invalid or expired."})
			c.Abort()
			return
		}
		log.Log.Infof("Authentication successful:%s", username)
		// Set the username in the context for use by handlers.
		c.Set("username", username)

		c.Next()
	}
}
