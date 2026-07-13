package main

import (
	"github.com/joho/godotenv"
	"persist_worker/dynamodb"
	"persist_worker/logger"
	"persist_worker/persist"
)

func main() {
	logger.InitLogger()
	log := logger.Log
	log.Info("Starting persist Worker")
	err := godotenv.Load()
	if err != nil {
		log.Info("No .ENV file found, using default values")
	}
	log.Info("Loading DynamoDB")
	dynamodb.InitDB()
	log.Info("DynamoDB loaded")
	persist.StartRedisToDBSyncLoop()
}
