#!/bin/bash
set -e

IMG="/var/lib/vz/template/iso/noble-server-cloudimg-amd64.img"
SSH_KEY="/root/devbox.pub"

function create_vm {
  VMID=$1
  NAME=$2
  IP=$3

  echo "Creating VM $VMID ($NAME)..."
  qm create $VMID --name $NAME --memory 4096 --cores 2 --net0 virtio,bridge=vmbr0
  qm importdisk $VMID $IMG local
  qm set $VMID --scsihw virtio-scsi-pci --scsi0 local:$VMID/vm-$VMID-disk-0.raw
  qm set $VMID --ide2 local:cloudinit
  qm set $VMID --boot c --bootdisk scsi0
  qm set $VMID --serial0 socket --vga serial0
  qm set $VMID --ipconfig0 ip=$IP/24,gw=10.10.10.1
  qm set $VMID --ciuser ubuntu --cipassword ubuntu
  qm set $VMID --sshkeys $SSH_KEY
  
  # Resize disk to 20G
  qm resize $VMID scsi0 +18G
  
  qm start $VMID
  echo "VM $VMID started."
}

# Destroy existing ones just in case
qm stop 301 || true
qm destroy 301 || true
qm stop 302 || true
qm destroy 302 || true
qm stop 303 || true
qm destroy 303 || true

create_vm 301 k3s-master-1 10.10.10.193
create_vm 302 k3s-worker-1 10.10.10.194
create_vm 303 k3s-worker-2 10.10.10.195

echo "All VMs created and started!"
