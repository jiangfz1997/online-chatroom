Write-Host "🌀 Applying Kafka + Zookeeper Deployment to K3s..."

kubectl apply -f .\k3s\kafka.yaml

Start-Sleep -Seconds 5
Write-Host "⏳ Waiting for Kafka Pod to be ready..."

kubectl rollout status deployment/kafka -n default
kubectl rollout status deployment/zookeeper -n default

Write-Host "✅ Kafka and Zookeeper deployed successfully!"
