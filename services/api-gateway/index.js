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

const DEVICE_GATEWAY_URL = process.env.DEVICE_GATEWAY_URL || 'http://device-gateway:8083';
const ROUTING_ENGINE_URL = process.env.ROUTING_ENGINE_URL || 'http://routing-engine:8084';
const PREFIX_UPDATER_URL = process.env.PREFIX_UPDATER_URL || 'http://prefix-updater:8085';

// Proxy routes to core-service (excluding devices, routing emulate, and sync)
app.use('/api', createProxyMiddleware({
    target: CORE_SERVICE_URL,
    changeOrigin: true,
    pathFilter: (pathname, req) => {
        return pathname.startsWith('/api') && 
               !pathname.startsWith('/api/devices') && 
               !pathname.startsWith('/api/routing/emulate') &&
               !pathname.startsWith('/api/prefixes/sync');
    }
}));

// Proxy device related routes
app.use('/api/devices', createProxyMiddleware({
    target: DEVICE_GATEWAY_URL,
    changeOrigin: true
}));

// Proxy routing emulation
app.use('/api/routing/emulate', createProxyMiddleware({
    target: ROUTING_ENGINE_URL,
    changeOrigin: true
}));

// Proxy prefix updater sync
app.use('/api/prefixes/sync', createProxyMiddleware({
    target: PREFIX_UPDATER_URL,
    changeOrigin: true
}));

app.get('/health', (req, res) => {
    res.json({ status: 'UP', service: 'api-gateway' });
});

app.listen(PORT, () => {
    console.log(`API Gateway running on port ${PORT}`);
});
