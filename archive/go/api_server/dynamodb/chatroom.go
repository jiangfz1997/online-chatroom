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
	"time"
)

var ChatroomTableName = "chatrooms" // Can be replaced with environment variable or configuration file reading

type Chatroom struct {
	RoomID    string   `json:"room_id" dynamodbav:"room_id"`
	Name      string   `json:"name" dynamodbav:"name"`
	IsPrivate bool     `json:"is_private" dynamodbav:"is_private"`
	CreatedBy string   `json:"created_by" dynamodbav:"created_by"`
	CreatedAt string   `json:"created_at" dynamodbav:"created_at"`
	Users     []string `json:"users" dynamodbav:"users"`
}

func CreateChatroomTable() error {
	log.Log.Info("Preparing to create the chatrooms table")
	_, err := DB.CreateTable(context.TODO(), &dynamodb.CreateTableInput{
		TableName: aws.String(ChatroomTableName),
		AttributeDefinitions: []types.AttributeDefinition{
			{
				AttributeName: aws.String("room_id"),
				AttributeType: types.ScalarAttributeTypeS,
			},
		},
		KeySchema: []types.KeySchemaElement{
			{
				AttributeName: aws.String("room_id"),
				KeyType:       types.KeyTypeHash,
			},
		},
		BillingMode: types.BillingModePayPerRequest,
	})
	if err != nil {
		var rne *types.ResourceInUseException
		if errors.As(err, &rne) {
			log.Log.Info(fmt.Sprintf("Chatroom table [%s] already exists, skipping creation", ChatroomTableName))
			return nil
		}

		return fmt.Errorf("create chatroomtable [%s] failed %w", ChatroomTableName, err)
	}
	log.Log.Info("chatrooms table created successfully")
	return nil
}

func CreateChatroom(chatroom Chatroom) error {
	// Time formatting (standard ISO format)
	if chatroom.CreatedAt == "" {
		chatroom.CreatedAt = time.Now().Format(time.RFC3339)
		log.Log.Debugf("Chatroom created at: %s", chatroom.CreatedAt)
	}
	log.Log.Infof("Preparing to create chatroom: room_id=%s, name=%s, created_by=%s", chatroom.RoomID, chatroom.Name, chatroom.CreatedBy)
	item, err := attributevalue.MarshalMap(chatroom)
	if err != nil {
		log.Log.Errorf("Failed to serialize chatroom data: %v", err)
		return err
	}

	_, err = DB.PutItem(context.TODO(), &dynamodb.PutItemInput{
		TableName: &ChatroomTableName,
		Item:      item,
	})
	if err != nil {
		log.Log.Errorf("Failed to write chatroom data: %v", err)
	} else {
		log.Log.Infof("Chatroom created successfully: room_id=%s", chatroom.RoomID)
	}
	return err

}

func GetChatroom(chatroomId string) (Chatroom, error) {
	var chatroom Chatroom
	log.Log.Infof("Attempting to retrieve chatroom: room_id=%s", chatroomId)
	// query conditions
	input := &dynamodb.GetItemInput{
		TableName: aws.String(ChatroomTableName),
		Key: map[string]types.AttributeValue{
			"room_id": &types.AttributeValueMemberS{Value: chatroomId},
		},
	}

	result, err := DB.GetItem(context.TODO(), input)
	if err != nil {
		return chatroom, err
	}

	if result.Item == nil {
		log.Log.Warnf("can not find chatroom: room_id=%s", chatroomId)
		return chatroom, fmt.Errorf("chatroom does not exist")
	}

	err = attributevalue.UnmarshalMap(result.Item, &chatroom)
	if err != nil {
		log.Log.Errorf("Failed to unmarshal chatroom data: %v", err)
		return chatroom, err
	}

	log.Log.Infof("get chatroom successfully: room_id=%s", chatroomId)
	return chatroom, nil
}

func AddUserToChatroom(username, roomID string) error {
	log.Log.Infof("trying to add user into chatroom: user=%s, room=%s", username, roomID)
	// get chatroom
	chatroom, err := GetChatroom(roomID)
	if err != nil {
		log.Log.Warnf("chatroom does not exist, failed: room_id=%s", roomID)
		return fmt.Errorf("chatroom not exist: %w", err)
	}

	// check if user is already joined
	for _, u := range chatroom.Users {
		if u == username {
			log.Log.Infof("User is already in: user=%s, room=%s", username, roomID)
			return nil
		}
	}
	log.Log.Infof("add user into chatroom user=%s, room=%s", username, roomID)
	// add user
	chatroom.Users = append(chatroom.Users, username)

	// write DB
	item, err := attributevalue.MarshalMap(chatroom)
	if err != nil {
		return err
	}
	_, err = DB.PutItem(context.TODO(), &dynamodb.PutItemInput{
		TableName: aws.String(ChatroomTableName),
		Item:      item,
	})
	if err != nil {
		log.Log.Errorf("write user data failed: %v", err)
	} else {
		log.Log.Infof("add user into chatroom successfully: user=%s, room=%s", username, roomID)
	}
	return err
}

func RemoveUserFromChatroom(username, roomID string) error {
	log.Log.Infof("Tring to remove user from chatroom user=%s, room=%s", username, roomID)
	room, err := GetChatroom(roomID)
	if err != nil {
		log.Log.Warnf("chatroom not exist, failed: room_id=%s", roomID)
		return fmt.Errorf("chatroom not exist: %w", err)
	}

	// remove user
	var newUsers []string
	for _, u := range room.Users {
		if u != username {
			newUsers = append(newUsers, u)
		}
	}
	room.Users = newUsers

	// write DB
	item, err := attributevalue.MarshalMap(room)
	if err != nil {
		return err
	}
	log.Log.Infof("remove the user from chatroom: user=%s, room=%s", username, roomID)
	_, err = DB.PutItem(context.TODO(), &dynamodb.PutItemInput{
		TableName: aws.String(ChatroomTableName),
		Item:      item,
	})
	if err != nil {
		log.Log.Errorf("remove failed: %v", err)
	} else {
		log.Log.Infof("remove successfully: user=%s, room=%s", username, roomID)
	}
	return err
}

func GetChatroomsByUsername(username string) ([]Chatroom, error) {
	log.Log.Infof("Query all chatrooms joined by the user: user=%s", username)
	var results []Chatroom

	// scan DB
	output, err := DB.Scan(context.TODO(), &dynamodb.ScanInput{
		TableName: aws.String(ChatroomTableName),
	})
	if err != nil {
		log.Log.Errorf("scan failed: %v", err)
		return nil, err
	}

	for _, item := range output.Items {
		var room Chatroom
		if err := attributevalue.UnmarshalMap(item, &room); err != nil {
			continue
		}

		// Find chatrooms the user has already joined
		for _, u := range room.Users {
			if u == username {
				results = append(results, room)
				break
			}
		}
	}
	log.Log.Infof("Total number of chatrooms joined by the user: %d", len(results))
	return results, nil
}
