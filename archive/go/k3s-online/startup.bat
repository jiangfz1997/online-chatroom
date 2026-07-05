@echo off
echo 🚀 正在刪除舊的 K3s 集群...
k3d cluster delete chat-cluster

echo ✅ 集群刪除完成，正在創建新的集群...
k3d cluster create chat-cluster ^
  --agents 1 ^
  --port "30080:80@loadbalancer" ^
  --port "30081:8081@loadbalancer" ^
  --port "30082:8082@loadbalancer" ^
  --port "30083:8080@loadbalancer" ^
  --port "30017:5173@loadbalancer"

echo 🎉 集群創建完成！
kubectl get nodes
pause