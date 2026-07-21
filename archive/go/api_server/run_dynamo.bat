docker stop dynamodb-local
docker rm dynamodb-local
docker run -d -p 8000:8000 --name dynamodb-local amazon/dynamodb-local -jar DynamoDBLocal.jar -sharedDb