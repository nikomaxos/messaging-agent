const { Client } = require('@stomp/stompjs');
const WebSocket = require('ws');
Object.assign(global, { WebSocket });

const client = new Client({
  brokerURL: 'ws://localhost:9090/ws',
  connectHeaders: {},
  debug: function (str) {
    console.log(str);
  },
  reconnectDelay: 5000,
  heartbeatIncoming: 4000,
  heartbeatOutgoing: 4000,
});

client.onConnect = function (frame) {
  console.log('Connected to STOMP');
  client.publish({
    destination: '/app/heartbeat',
    headers: { 'deviceToken': '20747523-dc84-4eab-99ce-fb1cedcc1b92' },
    body: JSON.stringify({ batteryPercent: 100, activeNetworkType: 'WIFI', isCharging: true })
  });
  console.log('Heartbeat sent');
  
  // Wait a few seconds for routing to happen, then exit
  setTimeout(() => {
    client.deactivate();
    process.exit(0);
  }, 3000);
};

client.onStompError = function (frame) {
  console.log('Broker reported error: ' + frame.headers['message']);
  console.log('Additional details: ' + frame.body);
};

client.activate();
