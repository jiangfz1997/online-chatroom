# ECR pull auth via kubelet credential provider (no imagePullSecret)

Previously the cluster pulled ECR images using a `docker-registry` secret
(`ecr-secret`) holding an ECR login token. ECR tokens expire after **12 hours**,
and the CI deploy never refreshed the secret — so every deploy more than 12h
after the secret was created failed with `ImagePullBackOff` and the rollout
timed out.

Fix: let the kubelet fetch ECR credentials automatically using the EC2 instance
role, via the [cloud-provider-aws `ecr-credential-provider`]. The node already
uses the instance role for DynamoDB, so this just adds ECR read permission.
No secret, nothing to expire.

Do these steps on the k3s node **before** merging this branch (which removes
`imagePullSecrets` from the manifests).

## 1. IAM — add ECR read to the EC2 instance role

Attach the managed policy `AmazonEC2ContainerRegistryReadOnly` to the instance
role used by the k3s EC2 host (or an equivalent inline policy with
`ecr:GetAuthorizationToken`, `ecr:BatchGetImage`, `ecr:GetDownloadUrlForLayer`,
`ecr:BatchCheckLayerAvailability`).

## 2. Install the provider binary on the node

```bash
sudo mkdir -p /var/lib/rancher/credentialprovider/bin
# amd64 node — match your instance architecture (arm64 for Graviton)
sudo curl -Lo /var/lib/rancher/credentialprovider/bin/ecr-credential-provider \
  https://github.com/kubernetes/cloud-provider-aws/releases/latest/download/ecr-credential-provider-linux-amd64
sudo chmod 0755 /var/lib/rancher/credentialprovider/bin/ecr-credential-provider
```

## 3. Install the provider config

Copy `credential-provider-config.yaml` from this directory to the node:

```bash
sudo cp credential-provider-config.yaml /var/lib/rancher/credentialprovider/config.yaml
```

## 4. Point k3s's kubelet at it

Add to `/etc/rancher/k3s/config.yaml` (create if absent):

```yaml
kubelet-arg:
  - "image-credential-provider-config=/var/lib/rancher/credentialprovider/config.yaml"
  - "image-credential-provider-bin-dir=/var/lib/rancher/credentialprovider/bin"
```

Then restart k3s:

```bash
sudo systemctl restart k3s
```

## 5. Verify

```bash
# force a fresh pull
sudo kubectl delete pod -l app=api-server
sudo kubectl get pods -w          # should reach Running (no ImagePullBackOff)
sudo journalctl -u k3s -f | grep -i credential   # provider being invoked
```

## 6. Clean up (this branch)

Once pulls work via the provider:

- Merge this branch (removes `imagePullSecrets` from api/ws/persist manifests).
- Delete the now-unused secret: `sudo kubectl delete secret ecr-secret`.

[cloud-provider-aws `ecr-credential-provider`]: https://github.com/kubernetes/cloud-provider-aws
