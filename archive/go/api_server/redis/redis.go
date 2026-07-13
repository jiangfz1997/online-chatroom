package redis

import (
	log "chatroom-api/logger"
	"context"
	"github.com/redis/go-redis/v9"
	"os"
	"strconv"
	"time"
)

var Rdb *redis.Client
var ctx = context.Background()

func InitRedis() {
	addr := os.Getenv("REDIS_ADDR")
	password := os.Getenv("REDIS_PASSWORD")
	db, _ := strconv.Atoi(os.Getenv("REDIS_DB"))
	timeoutSec, _ := strconv.Atoi(os.Getenv("REDIS_TIMEOUT"))
	if addr == "" {
		addr = "redis:6379" // K3s 內網地址（Cluster DNS）
		log.Log.Warn("Redis env not set, using default redis:6379")
	} else {
		log.Log.Infof("Redis env REDIS_ADDR: %s", addr)
	}
	Rdb = redis.NewClient(&redis.Options{
		Addr:        addr,
		Password:    password,
		DB:          db,
		DialTimeout: time.Duration(timeoutSec) * time.Second,
	})
	if err := Rdb.Ping(ctx).Err(); err != nil {
		log.Log.Fatalf("Redis connected failed：%v", err)
	} else {
		log.Log.Info("Redis connected successfully")
	}
}
