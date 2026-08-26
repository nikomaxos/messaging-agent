FROM node:20-alpine

# Install SSH client, Git, Bash, and Maven/Java for version bumping
RUN apk add --no-cache openssh-client bash curl git openjdk17 maven rclone docker-cli

WORKDIR /app

# Install dependencies
COPY package*.json ./
RUN npm install

# Copy application files
COPY server.js ./

# Expose port
EXPOSE 8082

CMD ["node", "server.js"]
