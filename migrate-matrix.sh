#!/bin/bash
set -euo pipefail
echo "Archiving Matrix data on legacy VM (10.10.10.192)..."
ssh -o StrictHostKeyChecking=no ubuntu@10.10.10.192 "sudo tar czf /tmp/synapse.tar.gz -C /opt/matrix/synapse/data ."

echo "Downloading archive to Deploy Agent..."
scp -o StrictHostKeyChecking=no ubuntu@10.10.10.192:/tmp/synapse.tar.gz /tmp/synapse.tar.gz

echo "Uploading archive to Kubernetes Master (10.10.10.193)..."
scp -o StrictHostKeyChecking=no /tmp/synapse.tar.gz ubuntu@10.10.10.193:/tmp/synapse.tar.gz

echo "Extracting archive directly into Synapse PVC using a temp pod..."
ssh -o StrictHostKeyChecking=no ubuntu@10.10.10.193 << 'EOF'
sudo kubectl run temp-synapse --image=ubuntu --restart=Never --overrides='{"spec": {"volumes": [{"name": "data", "persistentVolumeClaim": {"claimName": "synapse-pvc"}}], "containers": [{"name": "temp", "image": "ubuntu", "command": ["sleep", "3600"], "volumeMounts": [{"name": "data", "mountPath": "/data"}]}]}}'
sleep 15
sudo kubectl cp /tmp/synapse.tar.gz temp-synapse:/data/synapse.tar.gz
sudo kubectl exec temp-synapse -- bash -c "cd /data && tar xzf synapse.tar.gz && rm synapse.tar.gz"
sudo kubectl delete pod temp-synapse
sudo kubectl delete pod -l app=ma-synapse
EOF
echo "Matrix Synapse Migration Complete!"
