package org.eclipse.jetty.websocket.frames;

/**
 * A Base Frame as seen in <a href="https://tools.ietf.org/html/rfc6455#section-5.2">RFC 6455. Sec 5.2</a>
 * 
 * <pre>
 *    0                   1                   2                   3
 *    0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *   +-+-+-+-+-------+-+-------------+-------------------------------+
 *   |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 *   |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
 *   |N|V|V|V|       |S|             |   (if payload len==126/127)   |
 *   | |1|2|3|       |K|             |                               |
 *   +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
 *   |     Extended payload length continued, if payload len == 127  |
 *   + - - - - - - - - - - - - - - - +-------------------------------+
 *   |                               |Masking-key, if MASK set to 1  |
 *   +-------------------------------+-------------------------------+
 *   | Masking-key (continued)       |          Payload Data         |
 *   +-------------------------------- - - - - - - - - - - - - - - - +
 *   :                     Payload Data continued ...                :
 *   + - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
 *   |                     Payload Data continued ...                |
 *   +---------------------------------------------------------------+
 * </pre>
 */
public class BaseFrame
{
    /**
     * OpCode for a {@link ContinuationFrame}
     * 
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-11.8">RFC 6455, Section 11.8 (WebSocket Opcode Registry</a>
     */
    public final static byte OP_CONTINUATION = 0x00;
    /**
     * OpCode for a {@link TextFrame}
     * 
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-11.8">RFC 6455, Section 11.8 (WebSocket Opcode Registry</a>
     */
    public final static byte OP_TEXT = 0x01;
    /**
     * OpCode for a {@link BinaryFrame}
     * 
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-11.8">RFC 6455, Section 11.8 (WebSocket Opcode Registry</a>
     */
    public final static byte OP_BINARY = 0x02;
    /**
     * OpCode for a {@link CloseFrame}
     * 
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-11.8">RFC 6455, Section 11.8 (WebSocket Opcode Registry</a>
     */
    public final static byte OP_CLOSE = 0x08;
    /**
     * OpCode for a {@link PingFrame}
     * 
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-11.8">RFC 6455, Section 11.8 (WebSocket Opcode Registry</a>
     */
    public final static byte OP_PING = 0x09;
    /**
     * OpCode for a {@link PongFrame}
     * 
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-11.8">RFC 6455, Section 11.8 (WebSocket Opcode Registry</a>
     */
    public final static byte OP_PONG = 0x0A;

    private boolean fin;
    private boolean rsv1;
    private boolean rsv2;
    private boolean rsv3;
    private byte opcode = -1;
    private boolean masked = false;

    private long payloadLength;
    private byte mask[];
    public final static int FLAG_FIN = 0x8;
    public final static int FLAG_RSV1 = 0x4;
    public final static int FLAG_RSV2 = 0x2;
    public final static int FLAG_RSV3 = 0x1;

    public BaseFrame() {
        /* default */
    }

    /**
     * Copy Constructor
     * @param copy the copy
     */
    public BaseFrame(BaseFrame copy) {
        this.fin = copy.fin;
        this.rsv1 = copy.rsv1;
        this.rsv2 = copy.rsv2;
        this.rsv3 = copy.rsv3;
        this.opcode = copy.opcode;
        this.masked = copy.masked;
        this.payloadLength = copy.payloadLength;
        if(copy.mask != null) {
            int mlen = copy.mask.length;
            this.mask = new byte[mlen];
            System.arraycopy(copy.mask,0,this.mask,0,mlen);
        }
    }

    public byte[] getMask()
    {
        if (!masked)
        {
            throw new IllegalStateException("Frame is not masked");
        }
        return mask;
    }

    public byte getOpcode()
    {
        return opcode;
    }

    public long getPayloadLength()
    {
        return payloadLength;
    }

    public boolean isControlFrame()
    {
        return (opcode >= OP_CLOSE);
    }

    public boolean isFin()
    {
        return fin;
    }

    public boolean isLastFrame()
    {
        return fin;
    }

    public boolean isMasked()
    {
        return masked;
    }

    public boolean isRsv1()
    {
        return rsv1;
    }

    public boolean isRsv2()
    {
        return rsv2;
    }

    public boolean isRsv3()
    {
        return rsv3;
    }

    public void setFin(boolean fin)
    {
        this.fin = fin;
    }

    public void setMask(byte[] maskingKey)
    {
        this.mask = maskingKey;
    }

    public void setMasked(boolean mask)
    {
        this.masked = mask;
    }

    public void setOpcode(byte opcode)
    {
        this.opcode = opcode;
    }

    public void setPayloadLength(long payloadLength)
    {
        this.payloadLength = payloadLength;
    }

    public void setRsv1(boolean rsv1)
    {
        this.rsv1 = rsv1;
    }

    public void setRsv2(boolean rsv2)
    {
        this.rsv2 = rsv2;
    }

    public void setRsv3(boolean rsv3)
    {
        this.rsv3 = rsv3;
    }
}
