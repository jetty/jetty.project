package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.CloseException;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

/**
 * Base Framing Protocol handling
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
public class FrameParser
{
    private enum State
    {
        PAYLOAD_LEN,
        PAYLOAD_LEN_BYTES,
        MASK,
        MASK_BYTES,
        PAYLOAD
    }

    private static final Logger LOG = Log.getLogger(FrameParser.class);
    private WebSocketPolicy policy;
    // State specific
    private State state = State.PAYLOAD_LEN;
    private long length = 0;
    private int cursor = 0;
    // Frame
    private WebSocketFrame frame;
    // payload specific
    private ByteBuffer payload;
    private int payloadLength;

    public FrameParser(WebSocketPolicy policy)
    {
        this.policy = policy;
    }

    private void assertSanePayloadLength(long len)
    {
        // Since we use ByteBuffer so often, having lengths over Integer.MAX_VALUE is really impossible.
        if (len > Integer.MAX_VALUE)
        {
            // OMG! Sanity Check! DO NOT WANT! Won't anyone think of the memory!
            throw new CloseException(StatusCode.MESSAGE_TOO_LARGE,"[int-sane!] cannot handle payload lengths larger than " + Integer.MAX_VALUE);
        }
        policy.assertValidPayloadLength((int)len);
    }

    /**
     * Copy the bytes from one buffer to the other, demasking the content if necessary.
     * 
     * @param src
     *            the source {@link ByteBuffer}
     * @param dest
     *            the destination {@link ByteBuffer}
     * @param length
     *            the length of bytes to worry about
     * @return the number of bytes copied
     */
    protected int copyBuffer(ByteBuffer src, ByteBuffer dest, int length)
    {
        int amt = Math.min(length,src.remaining());
        if (frame.isMasked())
        {
            // Demask the content 1 byte at a time
            byte mask[] = getFrame().getMask();
            for (int i = 0; i < amt; i++)
            {
                dest.put((byte)(src.get() ^ mask[i % 4]));
            }
        }
        else
        {
            // Copy the content as-is
            // TODO: Look into having a BufferUtil.put(from,to,len) method
            byte b[] = new byte[amt];
            src.get(b,0,amt);
            dest.put(b,0,amt);
        }
        return amt;
    }

    /**
     * The frame that is being parsed
     * 
     * @return the frame that is being parsed. should always return an object (never null)
     */
    public WebSocketFrame getFrame()
    {
        return frame;
    }

    protected WebSocketPolicy getPolicy()
    {
        return policy;
    }

    /**
     * Initialize the base framing values.
     * 
     * @param fin
     * @param rsv1
     * @param rsv2
     * @param rsv3
     * @param opcode
     */
    public final void initFrame(boolean fin, boolean rsv1, boolean rsv2, boolean rsv3, OpCode opcode)
    {
        WebSocketFrame frame = new WebSocketFrame();
        frame.setFin(fin);
        frame.setRsv1(rsv1);
        frame.setRsv2(rsv2);
        frame.setRsv3(rsv3);
        frame.setOpCode(opcode);
    }

    /**
     * Parse the base framing protocol buffer.
     * <p>
     * Note the first byte (fin,rsv1,rsv2,rsv3,opcode) are parsed by the {@link Parser#parse(ByteBuffer)} method
     * <p>
     * Not overridable
     * 
     * @param buffer
     *            the buffer to parse from.
     * @return true if done parsing base framing protocol and ready for parsing of the payload. false if incomplete parsing of base framing protocol.
     */
    public final boolean parseBaseFraming(ByteBuffer buffer)
    {
        LOG.debug("Parsing {} bytes",buffer.remaining());
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case PAYLOAD_LEN:
                {
                    byte b = buffer.get();
                    getFrame().setMasked((b & 0x80) != 0);
                    length = (byte)(0x7F & b);

                    if (length == 127)
                    {
                        // length 8 bytes (extended payload length)
                        length = 0;
                        state = State.PAYLOAD_LEN_BYTES;
                        cursor = 8;
                        break; // continue onto next state
                    }
                    else if (length == 126)
                    {
                        // length 2 bytes (extended payload length)
                        length = 0;
                        state = State.PAYLOAD_LEN_BYTES;
                        cursor = 2;
                        break; // continue onto next state
                    }

                    assertSanePayloadLength(length);
                    if (getFrame().isMasked())
                    {
                        state = State.MASK;
                    }
                    else
                    {
                        state = State.PAYLOAD;
                    }

                    break;
                }
                case PAYLOAD_LEN_BYTES:
                {
                    byte b = buffer.get();
                    --cursor;
                    length |= (b & 0xFF) << (8 * cursor);
                    if (cursor == 0)
                    {
                        assertSanePayloadLength(length);
                        if (getFrame().isMasked())
                        {
                            state = State.MASK;
                        }
                        else
                        {
                            state = State.PAYLOAD;
                        }
                    }
                    break;
                }
                case MASK:
                {
                    byte m[] = new byte[4];
                    getFrame().setMask(m);
                    if (buffer.remaining() >= 4)
                    {
                        buffer.get(m,0,4);
                        state = State.PAYLOAD;
                    }
                    else
                    {
                        state = State.MASK_BYTES;
                        cursor = 4;
                    }
                    break;
                }
                case MASK_BYTES:
                {
                    byte b = buffer.get();
                    --cursor;
                    getFrame().getMask()[cursor] = b;
                    if (cursor == 0)
                    {
                        state = State.PAYLOAD;
                    }
                    break;
                }
            }

            if (state == State.PAYLOAD)
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Implementation specific parsing of a payload
     * 
     * @param buffer
     *            the payload buffer
     * @return true if payload is done reading, false if incomplete
     */
    public boolean parsePayload(ByteBuffer buffer)
    {
        payloadLength = getFrame().getPayloadLength();
        while (buffer.hasRemaining())
        {
            if (payload == null)
            {
                getPolicy().assertValidPayloadLength(payloadLength);
                frame.assertValid();
                payload = ByteBuffer.allocate(payloadLength);
            }

            copyBuffer(buffer,payload,payload.remaining());

            if (payload.position() >= payloadLength)
            {
                BufferUtil.flipToFlush(payload,0);
                frame.setPayload(BufferUtil.toArray(payload));
                this.payload = null;
                return true;
            }
        }
        return false;
    }

    /**
     * Reset the frame and parser states
     */
    public void reset() {
        // reset parser
        state = State.PAYLOAD_LEN;
    }
}
