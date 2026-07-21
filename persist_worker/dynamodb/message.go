package dynamodb

import (
	"context"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	log "persist_worker/logger"
	"time"
)

type Message struct {
	//ID        string `dynamodbav:"id"`
	RoomID    string `json:"room_id" dynamodbav:"room_id"`
	Timestamp string `json:"timestamp" dynamodbav:"timestamp"`
	Sender    string `json:"sender" dynamodbav:"sender"`
	Text      string `json:"text" dynamodbav:"text"`
}

func NewMessage(roomID, sender, timestamps, text string) Message {
	return Message{
		//ID:        uuid.New().String(),
		RoomID:    roomID,
		Sender:    sender,
		Text:      text,
		Timestamp: timestamps,
	}
}

func SaveMessage(msg Message) error {
	log.Log.Infof("Ready to write message | RoomID: %s | Sender: %s | Timestamp: %s", msg.RoomID, msg.Sender, msg.Timestamp)

	item, err := attributevalue.MarshalMap(msg)
	if err != nil {
		log.Log.Errorf("Failed to serilize msg: %v", err)
		return err
	}

	input := &dynamodb.PutItemInput{
		TableName: aws.String("messages"),
		Item:      item,
	}

	start := time.Now()
	_, err = DB.PutItem(context.TODO(), input)
	if err != nil {
		log.Log.Errorf("Failed to write to dynamodb: %v", err)
		return err
	}

	log.Log.Infof("Dynamodb write success | RoomID: %s | time cost: %v", msg.RoomID, time.Since(start))
	return nil
}
