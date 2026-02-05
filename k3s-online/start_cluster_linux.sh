#!/bin/bash

set -e

echo "Starting full K3s chat-app deployment..."


echo "Checking Kubernetes cluster..."
kubectl cluster-info >/dev/null 2>&1 || { echo " Kubeconfig not ready or no cluster"; exit 1; }

# 1. Namespace
echo "📡 Checking if namespace 'chat-app' exists..."
if ! kubectl get ns chat-app >/dev/null 2>&1; then
  echo "🔧 Creating namespace chat-app..."
  kubectl create ns chat-app
else
  echo "Namespace 'chat-app' already exists."
fi

# 2. Secret
echo ""
echo "Applying AWS credentials secret..."
kubectl apply -f ./k3s/aws-credentials.yaml -n chat-app
echo "AWS secret applied."

# 3. DynamoDB (skip for online)
echo ""
echo "Skipping DynamoDB (using AWS managed service)"

# 4. Kafka + Zookeeper
echo ""
echo "Applying Kafka + Zookeeper Deployment..."
kubectl apply -f ./k3s/kafka.yaml -n chat-app
sleep 2
echo "Waiting for Zookeeper..."
kubectl rollout status deployment/zookeeper -n chat-app
echo "Waiting for Kafka..."
kubectl rollout status deployment/kafka -n chat-app
echo "Kafka and Zookeeper deployed."

# 5. Redis
echo ""
echo "Applying Redis Deployment..."
kubectl apply -f ./k3s/redis.yaml -n chat-app
sleep 2
kubectl rollout status deployment/redis -n chat-app
echo "Redis deployed."

6. wait
echo ""
echo " Waiting for Kafka & Redis to stabilize..."
sleep 8

# 7. API Server
echo ""
echo " Deploying API server..."
kubectl apply -f ./k3s/api-server.yaml -n chat-app
kubectl apply -f ./k3s/api-server-ingress.yaml -n chat-app
sleep 1
kubectl rollout status deployment/api-server -n chat-app
echo "API server deployed."

# 8. WS Server
echo ""
echo "Deploying WebSocket server..."
kubectl apply -f ./k3s/ws-server.yaml -n chat-app
kubectl apply -f ./k3s/ws-ingress.yaml -n chat-app
sleep 1
kubectl rollout status deployment/ws-server -n chat-app
echo "WS server deployed."

# 9. Persist Worker
echo ""
echo "Deploying persist-worker..."
kubectl apply -f ./k3s/persist-worker.yaml -n chat-app
sleep 1
kubectl rollout status deployment/persist-worker -n chat-app
echo "Persist worker deployed."

# 10. Done
echo ""
echo "All services deployed successfully!"
echo "Current pod status:"
kubectl get pods -n chat-app

PUBLIC_IP=$(curl -s ifconfig.me || echo "<your-ec2-ip>")
echo ""
echo "API Server URL:     http://$PUBLIC_IP/api"
echo " WebSocket Endpoint: ws://$PUBLIC_IP/ws"