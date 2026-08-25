package com.messagingagent.smpp;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.codec.frame.FrameDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Netty 3 FrameDecoder for HAProxy PROXY protocol v1.
 * If the connection starts with "PROXY ", it parses the source IP
 * and sets it as an attachment on the Channel.
 * If not, it just removes itself and forwards the raw bytes.
 */
public class ProxyProtocolDecoder extends FrameDecoder {

    private static final Logger log = LoggerFactory.getLogger(ProxyProtocolDecoder.class);
    private static final int MAX_PROXY_LINE_LENGTH = 107;

    @Override
    protected Object decode(ChannelHandlerContext ctx, Channel channel, ChannelBuffer buffer) throws Exception {
        if (buffer.readableBytes() < 6) {
            return null; // Need more data
        }

        buffer.markReaderIndex();
        byte[] prefix = new byte[6];
        buffer.readBytes(prefix);
        String prefixStr = new String(prefix, StandardCharsets.US_ASCII);

        if (!"PROXY ".equals(prefixStr)) {
            // Not PROXY protocol. Reset buffer and remove this decoder.
            buffer.resetReaderIndex();
            ctx.getPipeline().remove(this);
            return buffer.readBytes(buffer.readableBytes());
        }

        // It is PROXY protocol, find the \r\n
        buffer.resetReaderIndex();
        int eol = buffer.indexOf(buffer.readerIndex(), buffer.writerIndex(), (byte) '\n');
        
        if (eol == -1) {
            if (buffer.readableBytes() > MAX_PROXY_LINE_LENGTH) {
                log.warn("PROXY protocol line is too long, closing channel {}", channel.getRemoteAddress());
                channel.close();
            }
            return null; // Wait for \n
        }

        // Read the entire line including \n
        int length = eol - buffer.readerIndex() + 1;
        byte[] lineBytes = new byte[length];
        buffer.readBytes(lineBytes);
        
        String line = new String(lineBytes, StandardCharsets.US_ASCII).trim();
        
        try {
            // Format: PROXY TCP4 <src_ip> <dst_ip> <src_port> <dst_port>
            String[] parts = line.split(" ");
            if (parts.length >= 6) {
                String sourceIp = parts[2];
                channel.setAttachment(sourceIp);
                log.info("PROXY protocol extracted real IP: {}", sourceIp);
            } else {
                log.warn("Invalid PROXY protocol line format: {}", line);
            }
        } catch (Exception e) {
            log.error("Error parsing PROXY protocol line", e);
        }

        // Remove this decoder as it only processes the first line
        ctx.getPipeline().remove(this);
        
        // Return null to continue processing the rest of the stream
        return null;
    }
}
