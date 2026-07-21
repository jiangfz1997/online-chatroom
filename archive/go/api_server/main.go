package main

import (
	"chatroom-api/dynamodb"
	"chatroom-api/logger"
	"chatroom-api/redis"
	"chatroom-api/router"
	"github.com/joho/godotenv"
)

func main() {

	logger.InitLogger()
	log := logger.Log
	log.Info("Server startup process initiated.")

	err := godotenv.Load(".env")
	if err != nil {
		log.Warn(".env file not found, using default environment variables.")
	} else {
		log.Info(".env loaded successfully")
	}

	log.Info("Starting database initialization.")
	dynamodb.InitDB()
	log.Info("Database initialization completed.")

	log.Info("Initializing Redis connection")
	redis.InitRedis()
	log.Info("Redis connection initialized")

	if err := dynamodb.CreateAllTables(); err != nil {
		log.Warn("Failed to create DynamoDB tables: %v (ignored)", err)
	}

	r := router.SetupRouter()
	log.Info("Starting HTTP service, listening on :8080.")
	if err := r.Run(":8080"); err != nil {
		log.Fatalf("Service startup failed: %v", err)
	}
}
