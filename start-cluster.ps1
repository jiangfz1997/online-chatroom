if (-not (k3d cluster list | Select-String "chat-cluster")) {
    Write-Host "K3d cluster not found. Creating..."
    k3d cluster create chat-cluster --port "80:80@loadbalancer"
    Start-Sleep -Seconds 3
} else {
    Write-Host "K3d cluster already exists."
}


kubectl get ns chat-app > $null 2>&1
if ($LASTEXITCODE -ne 0) {
  Write-Host "Creating namespace chat-app..."
  kubectl create ns chat-app
}

Start-Sleep -Seconds 1
Write-Host " Applying DynamoDB Deployment to K3s..."

kubectl apply -f .\k3s-local\dynamodb.yaml -n chat-app
Start-Sleep -Seconds 2
kubectl rollout status deployment/dynamodb -n chat-app
Write-Host "DynamoDB deployed successfully!"


Write-Host " Applying Kafka + Zookeeper Deployment to K3s..."
kubectl apply -f .\k3s-local\kafka.yaml -n chat-app
Start-Sleep -Seconds 2
Write-Host "Waiting for Kafka Pod to be ready..."
kubectl rollout status deployment/kafka -n chat-app
kubectl rollout status deployment/zookeeper -n chat-app
Write-Host "Kafka and Zookeeper deployed successfully!"


Write-Host " Applying Redis Deployment to K3s..."
kubectl apply -f .\k3s-local\redis.yaml -n chat-app
Start-Sleep -Seconds 2
kubectl rollout status deployment/redis -n chat-app
Write-Host "Redis deployed successfully!"


Start-Sleep -Seconds 2
Write-Host " Applying API server to K3s..."
kubectl apply -f .\k3s-local\api-server.yaml -n chat-app
kubectl apply -f .\k3s-local\api-server-ingress.yaml -n chat-app
Write-Host "API server deployed successfully!"

Start-Sleep -Seconds 2
Write-Host " Applying WS server to K3s..."
kubectl apply -f .\k3s-local\ws-server.yaml -n chat-app
kubectl apply -f .\k3s-local\ws-ingress.yaml -n chat-app
Write-Host "WS server deployed successfully!"

Start-Sleep -Seconds 2
Write-Host " Applying persistserver to K3s..."
kubectl apply -f .\k3s-local\persist-worker.yaml -n chat-app
Write-Host "persist server deployed successfully!"

Write-Host "` All services deployed! Current pod status:"
kubectl get pods -n chat-app

