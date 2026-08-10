const crypto = require('crypto');

// Raw JWT generation
const header = { alg: 'HS256', typ: 'JWT' };
const payload = {
    sub: 'admin',
    role: 'ADMIN',
    iat: Math.floor(Date.now() / 1000),
    exp: Math.floor(Date.now() / 1000) + 86400
};

const encodeBase64Url = (obj) => Buffer.from(JSON.stringify(obj)).toString('base64').replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');

const headerB64 = encodeBase64Url(header);
const payloadB64 = encodeBase64Url(payload);

const secret = 'change-me-in-production-at-least-32-chars';
const key = crypto.createHash('sha256').update(secret).digest();

const signature = crypto.createHmac('sha256', key).update(`${headerB64}.${payloadB64}`).digest('base64').replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');

console.log(`${headerB64}.${payloadB64}.${signature}`);
