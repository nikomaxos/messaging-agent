const net = require('net');

const client = new net.Socket();
client.connect(2775, '10.10.10.105', () => {
    console.log('Connected to Proxy');
    // SMPP BIND_TRANSCEIVER PDU for btJ2C0qGxxgqfTI
    // command_length = 16 (header) + system_id (16) + password (9) + system_type (1) + interface_version (1) + addr_ton (1) + addr_npi (1) + address_range (1) = 46 bytes
    const buf = Buffer.alloc(46);
    buf.writeUInt32BE(46, 0); // command_length
    buf.writeUInt32BE(9, 4);  // command_id (bind_transceiver)
    buf.writeUInt32BE(0, 8);  // command_status
    buf.writeUInt32BE(1, 12); // sequence_number
    
    let offset = 16;
    buf.write('btJ2C0qGxxgqfTI', offset); offset += 16;
    buf.write('RbSpbBYE', offset); offset += 9;
    buf.writeUInt8(0, offset++); // system_type (empty)
    buf.writeUInt8(0x34, offset++); // interface_version
    buf.writeUInt8(0, offset++); // addr_ton
    buf.writeUInt8(0, offset++); // addr_npi
    buf.writeUInt8(0, offset++); // address_range (empty)
    
    client.write(buf);
    console.log('Sent bind_transceiver');
});

client.on('data', (data) => {
    console.log('Received:', data.toString('hex'));
    if (data.length >= 16) {
        const cmdId = data.readUInt32BE(4);
        const status = data.readUInt32BE(8);
        console.log(`Command ID: ${cmdId}, Status: ${status}`);
    }
    client.destroy();
});

client.on('close', () => {
    console.log('Connection closed');
});

client.on('error', (err) => {
    console.log('Error:', err);
});
