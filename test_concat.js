const smpp = require('smpp');

const session = smpp.connect({
    url: 'smpp://ma-backend:2775'
});

session.bind_transceiver({
    system_id: 'client1',
    password: 'secret'
}, (pdu) => {
    if (pdu.command_status === 0) {
        console.log('Successfully bound');
        // Let's send a concatenated message manually
        session.submit_sm({
            destination_addr: '1234567890',
            esm_class: 0x40,
            short_message: Buffer.from([0x05, 0x00, 0x03, 0x0A, 0x02, 0x01, 0x48, 0x65, 0x6C, 0x6C, 0x6F]),
            registered_delivery: 1
        }, (submitPdu) => {
            console.log('Part 1 response status:', submitPdu.command_status);
            console.log('Part 1 message ID:', submitPdu.message_id);
            
            session.submit_sm({
                destination_addr: '1234567890',
                esm_class: 0x40,
                short_message: Buffer.from([0x05, 0x00, 0x03, 0x0A, 0x02, 0x02, 0x20, 0x57, 0x6F, 0x72, 0x6C, 0x64]),
                registered_delivery: 1
            }, (submitPdu2) => {
                console.log('Part 2 response status:', submitPdu2.command_status);
                console.log('Part 2 message ID:', submitPdu2.message_id);
                // Don't unbind immediately, wait for DLR
            });
        });
    } else {
        console.log('Bind failed:', pdu.command_status);
        session.close();
    }
});

session.on('deliver_sm', (pdu) => {
    console.log('Received DELIVER_SM:', pdu);
    session.send(pdu.response());
});

session.on('error', (err) => {
    console.error('SMPP error:', err);
});
session.on('close', () => {
    console.log('Connection closed');
});