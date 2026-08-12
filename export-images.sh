#!/bin/bash
set -e

IMAGES="messaging-agent-core-service:latest messaging-agent-ma-routing-engine:latest messaging-agent-ma-smpp-edge:latest messaging-agent-ma-rcs-mautrix:latest"
ARCHIVE="/tmp/k3s-images.tar"

echo "Saving images to $ARCHIVE..."
docker save -o $ARCHIVE $IMAGES

echo "Transferring and importing to k3s-master-1 (10.10.10.193)..."
scp -o StrictHostKeyChecking=no $ARCHIVE ubuntu@10.10.10.193:/tmp/
ssh -o StrictHostKeyChecking=no ubuntu@10.10.10.193 "sudo k3s ctr images import /tmp/k3s-images.tar"

echo "Transferring and importing to k3s-worker-1 (10.10.10.194)..."
scp -o StrictHostKeyChecking=no $ARCHIVE ubuntu@10.10.10.194:/tmp/
ssh -o StrictHostKeyChecking=no ubuntu@10.10.10.194 "sudo k3s ctr images import /tmp/k3s-images.tar"

echo "Transferring and importing to k3s-worker-2 (10.10.10.195)..."
scp -o StrictHostKeyChecking=no $ARCHIVE ubuntu@10.10.10.195:/tmp/
ssh -o StrictHostKeyChecking=no ubuntu@10.10.10.195 "sudo k3s ctr images import /tmp/k3s-images.tar"

echo "All images successfully imported to the K3s cluster!"
