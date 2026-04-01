FROM docker:27-cli

# Install Python3, Git, Bash, Docker Compose plugin, and curl
RUN apk add --no-cache \
    python3 \
    git \
    bash \
    curl \
    docker-cli-compose \
    openssh-client

WORKDIR /app

# Copy deploy agent files
COPY webhook_server.py /app/webhook_server.py
COPY deploy.sh /app/deploy.sh
RUN chmod +x /app/deploy.sh

# Git config (needed for git operations)
RUN git config --global --add safe.directory /repo

EXPOSE 9000

CMD ["python3", "/app/webhook_server.py"]
