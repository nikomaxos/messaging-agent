#!/bin/bash
cat deploy-agent/deploy-k8s.sh | awk '
BEGIN { in_loop = 0 }
/^for service in "\$\{\!SERVICES\[@\]\}"; do/ { in_loop = 1; print; next }
in_loop == 1 && /^done/ { in_loop = 2; print; next }
in_loop == 1 { print; next }
{ print }
'
