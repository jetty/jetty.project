package org.eclipse.jetty.websocket.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.OpCode;

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
    private boolean fin = false;
    private boolean rsv1 = false;
    private boolean rsv2 = false;
    private boolean rsv3 = false;
    private OpCode opcode = null;
    private boolean masked = false;
    private int payloadLength;
    private byte mask[];
    private ByteBuffer payload = null;

    /**
     * Default constructor
     */
    public BaseFrame() {
        reset();
    }

    /**
     * Construct form opcode
     */
    public BaseFrame(OpCode opcode) {
        reset();
        this.opcode = opcode;
    }

    public byte[] getMask()
    {
        if (!masked)
        {
            throw new IllegalStateException("Frame is not masked");
        }
        return mask;
    }

    public final OpCode getOpCode()
    {
        return opcode;
    }

    /**
     * Get the data
     * 
     * @return the raw bytebuffer data (can be null)
     */
    public ByteBuffer getPayload()
    {
        return payload;
    }

    public int getPayloadLength()
    {
        return payloadLength;
    }

    public boolean hasPayload()
    {
        return payload != null;
    }

    public boolean isContinuation()
    {
        return false; // always false here
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

    public void reset()
    {
        fin = false;
        rsv1 = false;
        rsv2 = false;
        rsv3 = false;
        opcode = null;
        masked = false;
        payloadLength = -1;
        mask = null;
    }

    public void setFin(boolean fin)
    {
        this.fin = fin;
    }

    public void setMask(byte[] maskingKey)
    {
        this.mask = maskingKey;
        this.masked = (mask != null);
    }

    public void setMasked(boolean mask)
    {
        this.masked = mask;
    }

    public void setOpCode(OpCode opCode)
    {
        this.opcode = opCode;
    }

    /**
     * Set the data and payload length.
     * 
     * @param buf
     *            the bytebuffer to set
     */
    protected void setPayload(byte buf[])
    {
        int len = buf.length;
        this.payload = ByteBuffer.allocate(len);
        this.payload.put(buf,0,len);
        this.payload.flip(); // make payload readable
        this.setPayloadLength(len);
    }

    /**
     * Set the data and payload length.
     * 
     * @param buf
     *            the byte array to set
     */
    public void setPayload(ByteBuffer payload)
    {
        this.payload = payload;
        this.payload.flip(); // make payload readable
        setPayloadLength(this.payload.remaining());
    }

    public void setPayloadLength(int payloadLength)
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
