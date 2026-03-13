const smpp = require('smpp');

const session = smpp.connect({
    url: 'smpp://backend:2775'
});

session.bind_transceiver({
    system_id: 'client1',
    password: 'secret'
}, (pdu) => {
    if (pdu.command_status === 0) {
        console.log('Successfully bound');
        session.submit_sm({
            destination_addr: '1234567890',
            short_message: 'Hello from Node SMPP!'
        }, (submitPdu) => {
            console.log('Submit response status:', submitPdu.command_status);
            if(submitPdu.command_status === 0) {
                console.log('Message ID:', submitPdu.message_id);
            }
            session.unbind();
        });
    } else {
        console.log('Bind failed:', pdu.command_status);
        session.close();
    }
});

session.on('error', (err) => {
    console.error('SMPP error:', err);
});
session.on('close', () => {
    console.log('Connection closed');
});
