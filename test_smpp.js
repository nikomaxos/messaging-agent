const smpp = require('smpp');

const session = smpp.connect({
    url: 'smpp://127.0.0.1:2775',
    auto_enquire_link_period: 10000,
    debug: true
});

session.bind_transceiver({
    system_id: 'client1',
    password: 'secret'
}, function(pdu) {
    if (pdu.command_status === 0) {
        console.log('Successfully bound');
        session.submit_sm({
            destination_addr: '306981860567',
            source_addr: '123456',
            short_message: 'Hello from Node JS TEST'
        }, function(pdu) {
            console.log('Submit SM status:', pdu.command_status);
            if (pdu.command_status === 0) {
                console.log('Message ID:', pdu.message_id);
            }
            session.unbind();
            process.exit(0);
        });
    } else {
        console.log('Failed to bind', pdu);
        process.exit(1);
    }
});

session.on('error', function(err){
    console.log('error', err);
});
