# K3s HPA + AWS ASG validation lab

This lab demonstrates the complete scaling chain used by the chatroom deployment:

1. Kubernetes Metrics Server reports pod CPU and memory utilization.
2. HPA increases the desired replica count.
3. Pod anti-affinity leaves the second replica Pending on the one-worker cluster.
4. Cluster Autoscaler discovers the tagged worker ASG and changes its desired capacity from 1 to 2.
5. The second EC2 worker joins K3s and the Pending pod becomes Ready.

The ASG intentionally has no EC2 target-tracking scaling policy. Cluster Autoscaler owns ASG desired capacity; adding an independent EC2 CPU policy would create two competing controllers.

The CloudFormation stack creates one fixed `t3.medium` K3s server and an On-Demand `t3.small` worker ASG with `min=1`, `desired=1`, and `max=2`. All EBS volumes use `DeleteOnTermination`.

The legacy `chatroom-k3s-asg` is not modified.

The autoscaler uses one-minute scale-down windows only to keep this disposable demonstration short. Production values should be more conservative to avoid node churn.

See [VALIDATION.md](VALIDATION.md) for the observed scale-out and scale-in timeline and interview-safe wording.
