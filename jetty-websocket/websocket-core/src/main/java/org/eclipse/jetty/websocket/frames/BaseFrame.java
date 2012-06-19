package org.eclipse.jetty.websocket.frames;

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
    private boolean fin;
    private boolean rsv1;
    private boolean rsv2;
    private boolean rsv3;
    private OpCode opcode = null;
    private boolean masked = false;
    private int payloadLength;
    private byte mask[];

    /**
     * Default constructor
     */
    public BaseFrame() {
        reset();
    }

    /**
     * Copy Constructor
     * @param copy the copy
     */
    public BaseFrame(BaseFrame copy) {
        this();
        copy(copy);
    }

    /**
     * Construct form opcode
     */
    public BaseFrame(OpCode opcode) {
        reset();
        this.opcode = opcode;
    }

    /**
     * Copy the baseframe values
     * 
     * @param copy
     */
    public void copy(BaseFrame copy) {
        this.fin = copy.fin;
        this.rsv1 = copy.rsv1;
        this.rsv2 = copy.rsv2;
        this.rsv3 = copy.rsv3;
        this.opcode = copy.opcode;
        this.masked = copy.masked;
        this.payloadLength = copy.payloadLength;
        if (copy.mask != null)
        {
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

    public OpCode getOpCode()
    {
        return opcode;
    }

    public int getPayloadLength()
    {
        return payloadLength;
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
