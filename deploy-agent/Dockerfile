FROM node:20-alpine

# Install SSH client and Bash for remote execution
RUN apk add --no-cache openssh-client bash curl

WORKDIR /app

# Install dependencies
COPY package*.json ./
RUN npm install

# Copy application files
COPY server.js ./

# Expose port
EXPOSE 8082

CMD ["node", "server.js"]
