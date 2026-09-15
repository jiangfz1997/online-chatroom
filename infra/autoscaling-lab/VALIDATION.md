# Autoscaling validation record

Date: 2026-09-08  
Region: `us-east-1`  
Stack: `chatroom-autoscaling-lab`

## Validated architecture

```text
Metrics Server
  -> HPA changes Deployment replicas
  -> additional pod cannot fit because of required pod anti-affinity
  -> pod stays Pending
  -> Cluster Autoscaler detects the Pending pod
  -> Cluster Autoscaler changes worker ASG desired capacity
  -> new EC2 worker joins K3s
  -> scheduler places the Pending pod on the new worker
```

The fixed K3s server used `workload=infra`. ASG workers used `workload=apps`. The worker ASG had `min=1`, `desired=1`, and `max=2` before load.

## Scale-out evidence

1. Baseline HPA state: one replica, CPU `0%/50%`, memory `28%/70%`.
2. The in-cluster load generator started at `2026-09-08T22:14:19Z`.
3. CPU reached `250%/50%`; HPA emitted `SuccessfulRescale` and changed the deployment from one replica to two.
4. The second pod could not share the first worker because of required pod anti-affinity.
5. Cluster Autoscaler logged:

   ```text
   Final scale-up plan: [{chatroom-autoscaling-lab-workers 1->2 (max: 2)}]
   Scale-up: setting group chatroom-autoscaling-lab-workers size to 2
   pod triggered scale-up: [{chatroom-autoscaling-lab-workers 1->2 (max: 2)}]
   ```

6. AWS reported ASG `desired=2` with two healthy `InService` workers.
7. Both application pods became Ready on separate worker nodes.

## Scale-in evidence

1. The load generator stopped at `2026-09-08T22:17:25Z`.
2. HPA emitted `SuccessfulRescale`, changed from two replicas to one, and reported `All metrics below target`.
3. Cluster Autoscaler marked the new worker unneeded and logged:

   ```text
   Scale-down: removing empty node
   Terminating EC2 instance
   ```

4. AWS completed the termination at `2026-09-08T22:25:36Z`; the ASG returned to `desired=1`.

## Important engineering observations

- CPU and memory HPA metrics are evaluated independently; HPA uses the recommendation requiring the most replicas. Memory at `37%` with two replicas and a `70%` target still rounded up to two replicas, so the disposable demo target was adjusted to `80%` to verify scale-in.
- The ASG intentionally had no target-tracking CPU policy. Cluster Autoscaler was the single controller of ASG desired capacity. An independent ASG CPU policy would compete with Kubernetes scheduling decisions.
- One-minute scale-down windows were used only to shorten this disposable test. Production values should be longer to avoid instance churn.
- The controlled demo validates the HPA-to-ASG mechanism. It is not a production-capacity benchmark of the chat application.

## Accurate interview wording

> I used HPA for pod-level scaling and Kubernetes Cluster Autoscaler as the bridge to an EC2 worker Auto Scaling Group. HPA reacted to pod CPU and memory metrics. When an additional replica was unschedulable, Cluster Autoscaler raised ASG desired capacity from one to two; the new instance joined K3s and accepted the Pending pod. After load stopped, HPA returned to one replica and Cluster Autoscaler returned the ASG to one worker. I validated this path with a controlled workload; it was not a production traffic benchmark.
