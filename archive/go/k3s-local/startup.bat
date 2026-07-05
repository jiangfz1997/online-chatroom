@echo off
echo Deleteing cluster...
k3d cluster delete chat-cluster

echo cluster deleted...
k3d cluster create chat-cluster ^
  --agents 1 ^
  --port "30080:80@loadbalancer" ^
  --port "30081:8081@loadbalancer" ^
  --port "30082:8082@loadbalancer" ^
  --port "30083:8080@loadbalancer" ^
  --port "30017:5173@loadbalancer"

echo cluster UP！
kubectl get nodes
pause