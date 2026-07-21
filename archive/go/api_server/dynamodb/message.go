package dynamodb

import (
	log "chatroom-api/logger"
	"context"
	"errors"
	"fmt"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

import "time"

var MessageTableName = "messages"

type Message struct {
	RoomID    string `json:"room_id" dynamodbav:"room_id"`
	Timestamp string `json:"timestamp" dynamodbav:"timestamp"`
	Sender    string `json:"sender" dynamodbav:"sender"`
	Text      string `json:"text" dynamodbav:"text"`
}

func NewMessage(roomID, sender, text string) Message {
	return Message{
		RoomID:    roomID,
		Sender:    sender,
		Text:      text,
		Timestamp: time.Now().Format(time.RFC3339),
	}
}

func GetMessagesBefore(roomID, before string, limit int) ([]Message, error) {
	log.Log.Infof("Query historical messages: room=%s, before=%s, limit=%d", roomID, before, limit)
	input := &dynamodb.QueryInput{
		TableName:              aws.String(MessageTableName),
		KeyConditionExpression: aws.String("room_id = :rid AND #ts < :before"),
		ExpressionAttributeNames: map[string]string{
			"#ts": "timestamp",
		},
		ExpressionAttributeValues: map[string]types.AttributeValue{
			":rid":    &types.AttributeValueMemberS{Value: roomID},
			":before": &types.AttributeValueMemberS{Value: before},
		},
		Limit:            aws.Int32(int32(limit)),
		ScanIndexForward: aws.Bool(false), // reverse order
	}

	resp, err := DB.Query(context.TODO(), input)
	if err != nil {
		log.Log.Errorf("query failed: %v", err)
		return nil, err
	}

	var msgs []Message
	err = attributevalue.UnmarshalListOfMaps(resp.Items, &msgs)
	if err != nil {
		log.Log.Errorf("unmarshal failed: %v", err)
		return nil, err
	}

	log.Log.Infof("query %d messages successfully", len(msgs))
	return msgs, nil
}

func CreateMessageTable() error {
	log.Log.Info("Starting to create messages table")
	_, err := DB.CreateTable(context.TODO(), &dynamodb.CreateTableInput{
		TableName: aws.String(MessageTableName),
		AttributeDefinitions: []types.AttributeDefinition{
			{AttributeName: aws.String("room_id"), AttributeType: types.ScalarAttributeTypeS},
			{AttributeName: aws.String("timestamp"), AttributeType: types.ScalarAttributeTypeS},
		},
		KeySchema: []types.KeySchemaElement{
			{AttributeName: aws.String("room_id"), KeyType: types.KeyTypeHash},    // Partition Key
			{AttributeName: aws.String("timestamp"), KeyType: types.KeyTypeRange}, // Sort Key
		},
		BillingMode: types.BillingModePayPerRequest,
	})
	if err != nil {
		var rne *types.ResourceInUseException
		if errors.As(err, &rne) {
			log.Log.Info("Messages table [%s] already exists, skipping creation.", MessageTableName)
			return nil
		}
		return fmt.Errorf("create mseeages table [%s] failed: %w", MessageTableName, err)
	}

	log.Log.Info("Messages table created successfully (primary key is room_id + timestamp)")
	return nil
}
