public class TestUdh {
    public static void main(String[] args) {
        byte[] shortMessage = new byte[] { 0x05, 0x00, 0x03, 0x1A, 0x02, 0x01, 'h', 'e', 'l', 'l', 'o' };
        int udhLength = shortMessage[0] & 0xFF;
        int refNum = 0, totalParts = 1, partNum = 1;
        if (shortMessage.length > udhLength) {
            int pos = 1;
            while (pos <= udhLength) {
                int iei = shortMessage[pos] & 0xFF;
                int ieLen = shortMessage[pos + 1] & 0xFF;
                if (iei == 0x00 && ieLen == 3) {
                    refNum = shortMessage[pos + 2] & 0xFF;
                    totalParts = shortMessage[pos + 3] & 0xFF;
                    partNum = shortMessage[pos + 4] & 0xFF;
                    break;
                }
                pos += 2 + ieLen;
            }
        }
        System.out.println("refNum: " + refNum + ", totalParts: " + totalParts + ", partNum: " + partNum);
    }
}