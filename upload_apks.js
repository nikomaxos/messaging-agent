const fs = require('fs');
const path = require('path');
const http = require('http');

async function upload(apkPath, endpoint, filename) {
    const data = fs.readFileSync(apkPath);
    
    // Simple multipart form data payload construction
    const boundary = '--------------------------12345678901234567890';
    let body = Buffer.concat([
        Buffer.from(`--${boundary}\r\n`),
        Buffer.from(`Content-Disposition: form-data; name="file"; filename="${filename}"\r\n`),
        Buffer.from('Content-Type: application/vnd.android.package-archive\r\n\r\n'),
        data,
        Buffer.from(`\r\n--${boundary}--\r\n`)
    ]);

    return new Promise((resolve, reject) => {
        const req = http.request({
            hostname: 'localhost',
            port: 9090,
            path: endpoint,
            method: 'POST',
            headers: {
                'Content-Type': `multipart/form-data; boundary=${boundary}`,
                'Content-Length': body.length
            }
        }, res => {
            let resData = '';
            res.on('data', chunk => resData += chunk);
            res.on('end', () => resolve(resData));
        });
        req.on('error', reject);
        req.write(body);
        req.end();
    });
}

async function main() {
    try {
        console.log("Uploading Agent App...");
        await upload('c:\\Dev\\messaging-agent\\android-app\\app\\build\\outputs\\apk\\debug\\app-debug.apk', '/api/apk/upload', 'MessagingAgent-1.1.26.apk');
        console.log("Uploading Guardian App...");
        await upload('c:\\Dev\\messaging-agent\\android-app\\guardian\\build\\outputs\\apk\\debug\\guardian-debug.apk', '/api/apk/upload-guardian', 'MessagingGuardian-1.1.6.apk');
        console.log("Uploads complete.");
    } catch(e) {
        console.error("Upload failed", e);
    }
}

main();
