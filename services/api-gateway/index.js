const express = require('express');
const cors = require('cors');
const { createProxyMiddleware } = require('http-proxy-middleware');
const jwt = require('jsonwebtoken');

const app = express();
app.use(cors());

const PORT = process.env.PORT || 3000;
const JWT_SECRET = process.env.JWT_SECRET || 'messaging-agent-secret-key-12345';
const CORE_SERVICE_URL = process.env.CORE_SERVICE_URL || 'http://core-service:8080';
const DEVICE_GATEWAY_URL = process.env.DEVICE_GATEWAY_URL || 'http://device-gateway:8083';
const ROUTING_ENGINE_URL = process.env.ROUTING_ENGINE_URL || 'http://routing-engine:8084';
const PREFIX_UPDATER_URL = process.env.PREFIX_UPDATER_URL || 'http://prefix-updater:8085';

// Global Rate Limiting & Auth Logic will be placed here
app.use((req, res, next) => {
    console.log(`[API Gateway] ${req.method} ${req.url} (original: ${req.originalUrl})`);
    next();
});

// Helper function to create a proxy middleware that PRESERVES the mount path
const createPreservingProxy = (targetUrl, mountPath) => {
    return createProxyMiddleware({
        target: targetUrl,
        changeOrigin: true,
        pathRewrite: (path, req) => {
            // path is the stripped path (e.g. /clients instead of /api/smpp/clients if mounted at /api/smpp)
            // But we can just use req.originalUrl!
            return req.originalUrl;
        }
    });
};

// Proxy device related routes
app.use('/api/devices', createPreservingProxy(DEVICE_GATEWAY_URL, '/api/devices'));

// Proxy routing emulation
app.use('/api/routing/emulate', createPreservingProxy(ROUTING_ENGINE_URL, '/api/routing/emulate'));

// Proxy prefix updater sync
app.use('/api/prefixes/sync', createPreservingProxy(PREFIX_UPDATER_URL, '/api/prefixes/sync'));

// Proxy all other routes to core-service
app.use('/api', createPreservingProxy(CORE_SERVICE_URL, '/api'));

app.get('/health', (req, res) => {
    res.json({ status: 'UP', service: 'api-gateway' });
});

app.listen(PORT, () => {
    console.log(`API Gateway running on port ${PORT}`);
});
