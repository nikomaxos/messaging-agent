const express = require('express');
const cors = require('cors');
const { createProxyMiddleware } = require('http-proxy-middleware');
const jwt = require('jsonwebtoken');

const app = express();
app.use(cors());

const PORT = process.env.PORT || 3000;
const JWT_SECRET = process.env.JWT_SECRET || 'messaging-agent-secret-key-12345';
const CORE_SERVICE_URL = process.env.CORE_SERVICE_URL || 'http://core-service:8080';

// Global Rate Limiting & Auth Logic will be placed here
app.use((req, res, next) => {
    // Basic Auth Middleware placeholder
    console.log(`[API Gateway] ${req.method} ${req.url}`);
    next();
});

// Proxy routes to core-service
app.use('/api/v1/core', createProxyMiddleware({
    target: CORE_SERVICE_URL,
    changeOrigin: true,
    pathRewrite: {
        '^/api/v1/core': '/api' // Rewrite so core-service sees /api/users
    },
    onProxyReq: (proxyReq, req, res) => {
        // Forward auth info
        if (req.user) {
            proxyReq.setHeader('x-user-id', req.user.id);
        }
    }
}));

app.get('/health', (req, res) => {
    res.json({ status: 'UP', service: 'api-gateway' });
});

app.listen(PORT, () => {
    console.log(`API Gateway running on port ${PORT}`);
});
