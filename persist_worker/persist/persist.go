package persist

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/redis/go-redis/v9"
	"os"
	"persist_worker/dynamodb"
	log "persist_worker/logger"
	"strconv"
	"time"
)

var ctx = context.Background()

var Rdb = redis.NewClient(&redis.Options{
	Addr:     os.Getenv("REDIS_ADDR"), // e.g. "redis:6379"
	Password: "",                      // no password set
	DB:       0,
})

var persistTickerInterval time.Duration

func init() {
	val := os.Getenv("PERSISTTICKER")
	if val == "" {
		persistTickerInterval = 30 * time.Second
	} else {
		n, err := strconv.Atoi(val)
		if err != nil {
			log.Log.Warnf("⚠️ Cannot get PERSISTTICKER=%s，default 30s", val)
			n = 30
		}
		persistTickerInterval = time.Duration(n) * time.Second
	}

	log.Log.Infof("🕒 Process interval: %v", persistTickerInterval)
}

func StartRedisToDBSyncLoop() {

	ticker := time.NewTicker(persistTickerInterval)
	log.Log.Infof("🌀 Persist work activated，process every %v second", persistTickerInterval)
	for range ticker.C {
		syncAllRooms()
	}
}

func syncAllRooms() {
	roomIDs := getAllRoomIDs()
	for _, roomID := range roomIDs {
		syncRoomMessages(roomID)
	}
}

func syncRoomMessages(roomID string) {
	key := "room:" + roomID + ":to_persist"
	for i := 0; i < 100; i++ {
		msg, err := Rdb.LPop(ctx, key).Result()
		if errors.Is(err, redis.Nil) {
			//log.Log.Infof("✅ room [%s] msg list empty", roomID)
			break
		}
		if err != nil {
			log.Log.Warnf("Redis LPOP error: %v", err)
			break
		}
		saveToDatabase(roomID, msg)
	}
}

func saveToDatabase(roomID string, rawMsg string) {
	var data struct {
		Sender    string `json:"sender"`
		Text      string `json:"text"`
		RoomID    string `json:"roomID"`
		TimeStamp string `json:"sentAt"`
	}
	if err := json.Unmarshal([]byte(rawMsg), &data); err != nil {
		log.Log.Errorf("⚠️ JSON parse failed: %v", err)
		return
	}

	msg := dynamodb.NewMessage(data.RoomID, data.Sender, data.TimeStamp, data.Text)
	if err := dynamodb.SaveMessage(msg); err != nil {
		log.Log.Errorf("DynamoDB saved failed: %v", err)
	} else {
		log.Log.Infof("DynamoDB saved success: [%s] %s", data.Sender, data.Text)
	}
}

func getAllRoomIDs() []string {
	roomIDs, err := Rdb.SMembers(ctx, "rooms:active").Result()
	if err != nil {
		log.Log.Errorf("Can not get active room list: %v", err)
		return []string{}
	}
	return roomIDs
}
