const smpp = require('smpp');

const session = smpp.connect({
    url: 'smpp://eu-uk.mlsmpp.net:2775',
    auto_enquire_link_period: 10000,
    debug: true
});

session.bind_transceiver({
    system_id: 'tScHHdVxvIMXmGg',
    password: 'GrIg7WzH'
}, function(pdu) {
    if (pdu.command_status === 0) {
        console.log('Successfully bound to Melrose SMPP');
        
        session.submit_sm({
            destination_addr: '306981860567',
            source_addr: 'test',
            short_message: 'Hello from AntiGravity agent testing successful delivery!'
        }, function(submitPdu) {
            if (submitPdu.command_status === 0) {
                console.log('Successfully submitted message! Message ID:', submitPdu.message_id);
            } else {
                console.log('Failed to submit message:', submitPdu.command_status);
            }
            
            // Wait 5 seconds for DLR then close
            setTimeout(() => {
                session.unbind();
                session.close();
            }, 5000);
        });
    } else {
        console.log('Failed to bind:', pdu.command_status);
        session.close();
    }
});

session.on('deliver_sm', function(pdu) {
    console.log('Received deliver_sm (DLR):', pdu.short_message.message || pdu.short_message);
    session.send(pdu.response());
});

session.on('error', function(error) {
    console.log('SMPP error:', error);
});
