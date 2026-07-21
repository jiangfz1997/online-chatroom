package dynamodb

import (
	"context"
	"fmt"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"os"
	log "persist_worker/logger"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	ddb "github.com/aws/aws-sdk-go-v2/service/dynamodb"
)

var DB *ddb.Client

func InitDB() {
	endpoint := os.Getenv("DYNAMODB_ENDPOINT")
	region := os.Getenv("DYNAMODB_REGION")
	if region == "" {
		region = "us-west-2"
		log.Log.Warn("DYNAMODB_REGION not set，default us-west-2")
	} else {
		log.Log.Infof("DYNAMODB_REGION set = %s", region)
	}
	var cfg aws.Config
	var err error

	if endpoint != "" {
		log.Log.Info("local DynamoDB (local mode)")
		log.Log.Infof("Using endpoint: %s", endpoint)

		customResolver := aws.EndpointResolverWithOptionsFunc(func(service, region string, _ ...interface{}) (aws.Endpoint, error) {
			if service == ddb.ServiceID {
				return aws.Endpoint{
					URL:           endpoint,
					SigningRegion: region,
				}, nil
			}
			return aws.Endpoint{}, fmt.Errorf("unknown endpoint requested")
		})

		cfg, err = config.LoadDefaultConfig(context.TODO(),
			config.WithRegion(region),
			config.WithEndpointResolverWithOptions(customResolver),
			config.WithCredentialsProvider(credentials.NewStaticCredentialsProvider("dummy", "dummy", "dummy")),
		)
		log.Log.Infof("📦 DynamoDB Config Loaded | Region: %s", cfg.Region)

		if cfg.Retryer != nil {
			log.Log.Info("🔁 Retryer setup")
		}

		if cfg.Credentials != nil {
			creds, err := cfg.Credentials.Retrieve(context.TODO())
			if err != nil {
				log.Log.Warnf("Failed to get credentials: %v", err)
			} else {
				log.Log.Infof("Credentials：AccessKey=%s (Provider=%s)", creds.AccessKeyID, creds.Source)
			}
		}
		if err != nil {
			log.Log.Fatalf("Failed to load local DynamoDB cfg: %v", err)
		}

	} else {
		log.Log.Info("Connect to AWS DynamoDB")
		cfg, err = config.LoadDefaultConfig(context.TODO(),
			config.WithRegion(region),
		)
		if err != nil {
			log.Log.Fatalf("Failed to load AWS cfg: %v", err)
		}
	}

	DB = ddb.NewFromConfig(cfg)
	log.Log.Info("Connected to DynamoDB")

	resp, err := DB.ListTables(context.TODO(), &ddb.ListTablesInput{})
	if err != nil {
		log.Log.Errorf("Failed to get tables : %v", err)
	} else {
		log.Log.Infof("Current talbe in dynamodb: %v", resp.TableNames)
	}
}
