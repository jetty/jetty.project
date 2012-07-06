package org.eclipse.jetty.websocket.frames;

import java.nio.ByteBuffer;

import javax.xml.ws.ProtocolException;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.protocol.Frame;
import org.eclipse.jetty.websocket.protocol.OpCode;

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
public class BaseFrame implements Frame
{
    /** Maximum size of Control frame, per RFC 6455 */
    public static final int MAX_CONTROL_PAYLOAD = 125;

    private boolean fin = false;
    private boolean rsv1 = false;
    private boolean rsv2 = false;
    private boolean rsv3 = false;
    private OpCode opcode = null;
    private boolean masked = false;
    private int payloadLength = 0;
    private byte mask[];
    private ByteBuffer payload = null;
    private boolean continuation = false;

    protected int continuationIndex = 0;

    /**
     * Default constructor
     */
    public BaseFrame()
    {
        reset();
    }

    /**
     * Construct form opcode
     */
    public BaseFrame(OpCode opcode)
    {
        reset();
        this.opcode = opcode;
    }

    public void assertValid()
    {
        if (opcode.isControlFrame())
        {
            if (payloadLength > BaseFrame.MAX_CONTROL_PAYLOAD)
            {
                throw new ProtocolException("Desired payload length [" + payloadLength + "] exceeds maximum control payload length [" + MAX_CONTROL_PAYLOAD
                        + "]");
            }

            if (fin == false)
            {
                throw new ProtocolException("Cannot have FIN==false on Control frames");
            }

            if (rsv1 == false)
            {
                throw new ProtocolException("Cannot have RSV1==false on Control frames");
            }

            if (rsv2 == false)
            {
                throw new ProtocolException("Cannot have RSV2==false on Control frames");
            }

            if (rsv3 == false)
            {
                throw new ProtocolException("Cannot have RSV3==false on Control frames");
            }

            if (isContinuation())
            {
                throw new ProtocolException("Control frames cannot be Continuations");
            }
        }
    }

    @Override
    public BaseFrame clone()
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

    /**
     * Get the data
     * 
     * @return the raw bytebuffer data (can be null)
     */
    public ByteBuffer getPayload()
    {
        return payload;
    }

    @Override
    public byte[] getPayloadData()
    {
        return BufferUtil.toArray(payload);
    }

    @Override
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
        payloadLength = 0;
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
        if (opcode.isControlFrame())
        {
            if (buf.length > BaseFrame.MAX_CONTROL_PAYLOAD)
            {
                throw new ProtocolException("Control Payloads can not exceed 125 bytes in length.");
            }
        }
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
     *            the bytebuffer to set
     */
    public void setPayload(byte buf[], int offset, int len)
    {
        if (opcode.isControlFrame())
        {
            if (len > BaseFrame.MAX_CONTROL_PAYLOAD)
            {
                throw new ProtocolException("Control Payloads can not exceed 125 bytes in length.");
            }
        }

        this.payload = ByteBuffer.allocate(len);
        this.payload.put(buf,offset,len);
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
        if (opcode.isControlFrame())
        {
            if (payload.position() > BaseFrame.MAX_CONTROL_PAYLOAD)
            {
                throw new ProtocolException("Control Payloads can not exceed 125 bytes in length.");
            }
        }

        this.payload = payload;
        this.payload.flip(); // make payload readable
        setPayloadLength(this.payload.remaining());
    }

    public void setPayloadLength(int length)
    {
        this.payloadLength = length;
    }

    public void setPayloadLength(long length)
    {
        // Since we use ByteBuffer so often, having lengths over Integer.MAX_VALUE is really impossible.
        if (length > Integer.MAX_VALUE)
        {
            // OMG! Sanity Check! DO NOT WANT! Won't anyone think of the memory!
            throw new IllegalArgumentException("[int-sane!] cannot handle payload lengths larger than " + Integer.MAX_VALUE);
        }
        this.payloadLength = (int)length;
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
