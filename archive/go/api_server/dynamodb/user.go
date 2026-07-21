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

type User struct {
	Username string `dynamodbav:"username"` //primary key
	Password string `dynamodbav:"password"`
}

var UserTableName = "users"

func CreateUser(user User) error {
	log.Log.Infof("Attempting to create user: username=%s", user.Username)
	item, err := attributevalue.MarshalMap(user)
	if err != nil {
		log.Log.Errorf("marsha userlist failed %v", err)
		return err
	}

	_, err = DB.PutItem(context.TODO(), &dynamodb.PutItemInput{
		TableName:           &UserTableName,
		Item:                item,
		ConditionExpression: aws.String("attribute_not_exists(username)"), //Prevent duplicate registration
	})
	if err != nil {
		log.Log.Warnf("User creation failed: username=%s, err=%v", user.Username, err)
	} else {
		log.Log.Infof("User created successfully: username=%s", user.Username)
	}
	return err
}

func GetUserByUsername(username string) (*User, error) {
	log.Log.Infof("Attempting to retrieve user: username=%s", username)
	out, err := DB.GetItem(context.TODO(), &dynamodb.GetItemInput{
		TableName: &UserTableName,
		Key: map[string]types.AttributeValue{
			"username": &types.AttributeValueMemberS{Value: username},
		},
	})
	if err != nil {
		log.Log.Errorf("Failed to query user: username=%s, err=%v", username, err)
		return nil, errors.New("user not found")
	}
	if out.Item == nil {
		log.Log.Warnf("user not exist: username=%s", username)
		return nil, errors.New("user not found")
	}

	var user User
	err = attributevalue.UnmarshalMap(out.Item, &user)
	if err != nil {
		log.Log.Errorf("unmarshal user failed: username=%s, err=%v", username, err)
		return nil, err
	}

	log.Log.Infof("Successfully retrieved user information: username=%s", user.Username)
	return &user, nil
}

func CreateUserTable() error {
	log.Log.Info("Starting to create users table")
	_, err := DB.CreateTable(context.TODO(), &dynamodb.CreateTableInput{
		TableName: aws.String(UserTableName),
		AttributeDefinitions: []types.AttributeDefinition{
			{
				AttributeName: aws.String("username"),
				AttributeType: types.ScalarAttributeTypeS, // String
			},
		},
		KeySchema: []types.KeySchemaElement{
			{
				AttributeName: aws.String("username"),
				KeyType:       types.KeyTypeHash,
			},
		},
		BillingMode: types.BillingModePayPerRequest,
	})
	if err != nil {
		// Error handling: If the table already exists, do not return an error.
		var rne *types.ResourceInUseException
		if errors.As(err, &rne) {
			log.Log.Info("User table [%s] already exists, skipping creation.", UserTableName)
			return nil
		}

		return fmt.Errorf("create user table [%s] failed: %w", UserTableName, err)
	}
	log.Log.Info("users table created successfully")
	return nil
}
