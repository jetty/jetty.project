package org.eclipse.jetty.websocket.protocol;


import java.nio.ByteBuffer;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.ProtocolException;

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
public class WebSocketFrame implements Frame
{
    /** Maximum size of Control frame, per RFC 6455 */
    public static final int MAX_CONTROL_PAYLOAD = 125;

    private boolean fin = false;
    private boolean rsv1 = false;
    private boolean rsv2 = false;
    private boolean rsv3 = false;
    private OpCode opcode = null;
    private boolean masked = false;
    private byte mask[];
    private byte payload[];
    private boolean continuation = false;
    private int continuationIndex = 0;

    /**
     * Default constructor
     */
    public WebSocketFrame()
    {
        reset();
    }

    /**
     * Construct form opcode
     */
    public WebSocketFrame(OpCode opcode)
    {
        reset();
        this.opcode = opcode;
    }

    public void assertValid()
    {
        if (opcode.isControlFrame())
        {
            if (getPayloadLength() > WebSocketFrame.MAX_CONTROL_PAYLOAD)
            {
                throw new ProtocolException("Desired payload length [" + getPayloadLength() + "] exceeds maximum control payload length ["
                        + MAX_CONTROL_PAYLOAD + "]");
            }

            if (fin == false)
            {
                throw new ProtocolException("Cannot have FIN==false on Control frames");
            }

            if (rsv1 == true)
            {
                throw new ProtocolException("Cannot have RSV1==true on Control frames");
            }

            if (rsv2 == true)
            {
                throw new ProtocolException("Cannot have RSV2==true on Control frames");
            }

            if (rsv3 == true)
            {
                throw new ProtocolException("Cannot have RSV3==true on Control frames");
            }

            if (isContinuation())
            {
                throw new ProtocolException("Control frames cannot be Continuations");
            }
        }
    }

    @Override
    public WebSocketFrame clone()
    {
        // TODO: impl
        return null;
    }

    /**
     * The number of fragments this frame consists of.
     * <p>
     * For every {@link OpCode#CONTINUATION} opcode encountered, this increments by one.
     * <p>
     * Note: Not part of the Base Framing Protocol / header information.
     * 
     * @return the number of continuation fragments encountered.
     */
    public int getContinuationIndex()
    {
        return continuationIndex;
    }

    @Override
    public byte[] getMask()
    {
        if (!masked)
        {
            throw new IllegalStateException("Frame is not masked");
        }
        return mask;
    }

    @Override
    public final OpCode getOpCode()
    {
        return opcode;
    }

    public ByteBuffer getPayload()
    {
        return payload.slice();
    }

    public String getPayloadAsUTF8()
    {
        if (payload == null)
        {
            return null;
        }
        return StringUtil.toUTF8String(payload,0,payload.length);
    }

    @Override
    public byte[] getPayloadData()
    {
        return payload;
    }

    @Override
    public int getPayloadLength()
    {
        if (payload == null)
        {
            return 0;
        }
        return payload.length;
    }

    public boolean hasPayload()
    {
        return payload != null;
    }

    public boolean isContinuation()
    {
        return continuation;
    }

    @Override
    public boolean isFin()
    {
        return fin;
    }

    public boolean isLastFrame()
    {
        return fin;
    }

    @Override
    public boolean isMasked()
    {
        return masked;
    }

    @Override
    public boolean isRsv1()
    {
        return rsv1;
    }

    @Override
    public boolean isRsv2()
    {
        return rsv2;
    }

    @Override
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
        payload = null;
        mask = null;
        continuationIndex = 0;
        continuation = false;
    }

    public void setContinuation(boolean continuation)
    {
        this.continuation = continuation;
    }

    public void setContinuationIndex(int continuationIndex)
    {
        this.continuationIndex = continuationIndex;
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
    public void setPayload(byte buf[])
    {
        if (buf == null)
        {
            payload = null;
            return;
        }

        if (opcode.isControlFrame())
        {
            if (buf.length > WebSocketFrame.MAX_CONTROL_PAYLOAD)
            {
                throw new ProtocolException("Control Payloads can not exceed 125 bytes in length.");
            }
        }
        int len = buf.length;
        payload = new byte[len];
        System.arraycopy(buf,0,payload,0,len);
    }

    /**
     * Set the data and payload length.
     * 
     * @param buf
     *            the bytebuffer to set
     */
    public void setPayload(byte buf[], int offset, int len)
    {
        if (buf == null)
        {
            payload = null;
            return;
        }

        if (opcode.isControlFrame())
        {
            if (len > WebSocketFrame.MAX_CONTROL_PAYLOAD)
            {
                throw new ProtocolException("Control Payloads can not exceed 125 bytes in length.");
            }
        }

        payload = new byte[len];
        System.arraycopy(buf,offset,payload,0,len);
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

    @Override
    public String toString()
    {
        StringBuilder b = new StringBuilder();
        if (opcode != null)
        {
            b.append(opcode.name());
        }
        else
        {
            b.append("NO-OP");
        }
        b.append('[');
        b.append("len=").append(getPayloadLength());
        b.append(",fin=").append(fin);
        b.append(",masked=").append(masked);
        b.append(",continuation=").append(continuation);
        b.append(']');
        return b.toString();
    }
}
