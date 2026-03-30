self.addEventListener('push', function(event) {
    console.log('[Service Worker] Push Received.', event);

    let title = 'New Alert';
    let options = {
        body: 'Notification received.'
    };

    if (event.data) {
        try {
            const data = event.data.json();
            title = data.title || title;
            options.body = data.message || data.body || options.body;
        } catch (e) {
            options.body = event.data.text() || options.body;
        }
    }

    event.waitUntil(self.registration.showNotification(title, options));
});

self.addEventListener('notificationclick', function(event) {
    console.log('[Service Worker] Notification click Received.');
    event.notification.close();
    
    // Open the admin panel if click
    event.waitUntil(
        clients.openWindow('/')
    );
});
