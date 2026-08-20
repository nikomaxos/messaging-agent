package com.messagingagent.smpp;

import com.cloudhopper.commons.charset.CharsetUtil;
import java.util.Random;

public class LongSmsHelper {

    private static final Random RANDOM = new Random();

    public record SmsPart(byte[] payload, byte dataCoding, boolean hasUdh) {}

    public static SmsPart[] createParts(String text, Byte requestedDataCoding) {
        boolean isUcs2 = false;
        
        if (requestedDataCoding != null) {
            int alphabet = requestedDataCoding & 0x0F;
            isUcs2 = (alphabet == 0x08);
        } else {
            try {
                byte[] gsmBytes = CharsetUtil.encode(text, CharsetUtil.CHARSET_GSM);
                String decoded = CharsetUtil.decode(gsmBytes, CharsetUtil.CHARSET_GSM);
                if (!text.equals(decoded)) {
                    isUcs2 = true;
                }
            } catch (Exception e) {
                isUcs2 = true;
            }
        }

        byte dataCoding;
        byte[] messageBytes;
        int maxPartBytes;
        int splitThreshold;

        if (isUcs2) {
            dataCoding = requestedDataCoding != null ? requestedDataCoding : (byte) 0x08; // UCS2
            messageBytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
            maxPartBytes = 140; // Max allowed by SMSC in a single submission
            splitThreshold = 134; // 140 - 6 = 134 bytes (67 chars)
        } else {
            dataCoding = requestedDataCoding != null ? requestedDataCoding : (byte) 0x00; // Default
            
            if (requestedDataCoding != null && (requestedDataCoding & 0x0F) == 0x04) {
                // Binary (8-bit)
                messageBytes = text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
                maxPartBytes = 140;
                splitThreshold = 134;
            } else {
                messageBytes = CharsetUtil.encode(text, CharsetUtil.CHARSET_GSM);
                maxPartBytes = 160;
                splitThreshold = 153; // traditionally 153 is used for GSM multipart
            }
        }

        if (messageBytes.length <= maxPartBytes) {
            return new SmsPart[]{ new SmsPart(messageBytes, dataCoding, false) };
        }

        int totalParts = (int) Math.ceil((double) messageBytes.length / splitThreshold);
        SmsPart[] parts = new SmsPart[totalParts];
        
        byte ref = (byte) RANDOM.nextInt(256);
        int offset = 0;

        for (int i = 0; i < totalParts; i++) {
            int length = Math.min(splitThreshold, messageBytes.length - offset);
            byte[] partPayload = new byte[6 + length];
            partPayload[0] = 0x05; // UDH length
            partPayload[1] = 0x00; // IE identifier (Concatenated SM 8-bit ref)
            partPayload[2] = 0x03; // IE data length
            partPayload[3] = ref; // CSMS reference number
            partPayload[4] = (byte) totalParts; // Total parts
            partPayload[5] = (byte) (i + 1); // Part number

            System.arraycopy(messageBytes, offset, partPayload, 6, length);
            parts[i] = new SmsPart(partPayload, dataCoding, true);
            
            offset += length;
        }

        return parts;
    }
}
