package dynamodb

import (
	log "chatroom-api/logger"
	"context"
	"fmt"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"os"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
)

var DB *dynamodb.Client

func InitDB() {
	endpoint := os.Getenv("DYNAMODB_ENDPOINT") // local mode
	region := os.Getenv("DYNAMODB_REGION")
	if region == "" {
		region = "us-west-2"
		log.Log.Warn("DYNAMODB_REGION is not set, defaulting to us-west-2.")
	} else {
		log.Log.Infof("DYNAMODB_REGION: %s", region)
	}
	var cfg aws.Config
	var err error

	if endpoint != "" {
		log.Log.Info("connecting DynamoDB (local mode)")
		log.Log.Infof("current DynamoDB Endpoint: %s", endpoint)
		customResolver := aws.EndpointResolverWithOptionsFunc(func(service, region string, _ ...interface{}) (aws.Endpoint, error) {
			if service == dynamodb.ServiceID {
				log.Log.Infof("custom：service [%s], region [%s]", service, region)
				return aws.Endpoint{
					URL:           endpoint, // DynamoDB local add
					SigningRegion: region,
				}, nil
			}
			return aws.Endpoint{}, fmt.Errorf("unknown endpoint requested %s", service)
		})

		cfg, err = config.LoadDefaultConfig(context.TODO(),
			config.WithRegion(region),
			config.WithEndpointResolverWithOptions(customResolver),
			// Add dummy credentials for local mode
			config.WithCredentialsProvider(credentials.NewStaticCredentialsProvider("dummy", "dummy", "dummy")),
		)

		if err != nil {
			log.Log.Fatalf("Failed to load local DynamoDB configuration: %v", err)
		}
		log.Log.Info("Local DynamoDB configuration loaded successfully")
	} else {
		log.Log.Info("Connect to AWS DynamoDB (real cloud service)")

		cfg, err = config.LoadDefaultConfig(context.TODO(),
			config.WithRegion(region),
		)
		if err != nil {
			log.Log.Fatalf("Failed to load AWS configuration: %v", err)
		}
		log.Log.Info("AWS configuration loaded successfully")
	}

	DB = dynamodb.NewFromConfig(cfg)
	log.Log.Info("DynamoDB client initialized successfully")
}

func CreateAllTables() error {
	var errs []error

	if err := CreateUserTable(); err != nil {
		errs = append(errs, fmt.Errorf("CreateUserTable failed: %w", err))
	}
	if err := CreateChatroomTable(); err != nil {
		errs = append(errs, fmt.Errorf("CreateChatroomTable failed: %w", err))
	}
	if err := CreateMessageTable(); err != nil {
		errs = append(errs, fmt.Errorf("CreateMessageTable failed: %w", err))
	}
	if len(errs) > 0 {
		errMsg := "Table creation encountered errors:\n"
		for _, e := range errs {
			errMsg += " - " + e.Error() + "\n"
		}
		return fmt.Errorf(errMsg)
	}

	return nil
}
