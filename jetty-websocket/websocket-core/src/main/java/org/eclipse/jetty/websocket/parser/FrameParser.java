package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.MessageTooLargeException;
import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.protocol.CloseInfo;
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
        START,
        FINOP,
        PAYLOAD_LEN,
        PAYLOAD_LEN_BYTES,
        MASK,
        MASK_BYTES,
        PAYLOAD
    }

    private static final Logger LOG = Log.getLogger(FrameParser.class);
    private WebSocketPolicy policy;
    // State specific
    private State state = State.START;
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
        LOG.debug("Payload Length: " + len);
        // Since we use ByteBuffer so often, having lengths over Integer.MAX_VALUE is really impossible.
        if (len > Integer.MAX_VALUE)
        {
            // OMG! Sanity Check! DO NOT WANT! Won't anyone think of the memory!
            throw new MessageTooLargeException("[int-sane!] cannot handle payload lengths larger than " + Integer.MAX_VALUE);
        }
        policy.assertValidPayloadLength((int)len);

        switch (frame.getOpCode())
        {
            case CLOSE:
                if (payloadLength == 1)
                {
                    throw new ProtocolException("Invalid close frame payload length, [" + payloadLength + "]");
                }
                // fall thru
            case PING:
            case PONG:
                if (payloadLength > WebSocketFrame.MAX_CONTROL_PAYLOAD)
                {
                    throw new ProtocolException("Invalid control frame payload length, [" + payloadLength + "] cannot exceed ["
                            + WebSocketFrame.MAX_CONTROL_PAYLOAD + "]");
                }
                break;
        }
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
            // FIXME: on partially parsed frames this needs an offset from prior parse
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
    public boolean parse(ByteBuffer buffer)
    {
        LOG.debug("Parsing {} bytes",buffer.remaining());
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case START:
                {
                    if ((frame != null) && (frame.isFin()))
                    {
                        frame.reset();
                    }

                    state = State.FINOP;
                    break;
                }
                case FINOP:
                {
                    // peek at byte
                    byte b = buffer.get();
                    boolean fin = ((b & 0x80) != 0);
                    boolean rsv1 = ((b & 0x40) != 0);
                    boolean rsv2 = ((b & 0x20) != 0);
                    boolean rsv3 = ((b & 0x10) != 0);
                    byte opc = (byte)(b & 0x0F);
                    OpCode opcode = OpCode.from(opc);

                    if (opcode == null)
                    {
                        throw new WebSocketException("Unknown opcode: " + opc);
                    }

                    LOG.debug("OpCode {}, fin={}",opcode.name(),fin);

                    if (opcode.isControlFrame() && !fin)
                    {
                        throw new ProtocolException("Fragmented Control Frame [" + opcode.name() + "]");
                    }

                    if (opcode == OpCode.CONTINUATION)
                    {
                        if (frame == null)
                        {
                            throw new ProtocolException("Fragment continuation frame without prior !FIN");
                        }
                        // Be careful to use the original opcode
                        opcode = frame.getOpCode();
                    }

                    // base framing flags
                    frame = new WebSocketFrame();
                    frame.setFin(fin);
                    frame.setRsv1(rsv1);
                    frame.setRsv2(rsv2);
                    frame.setRsv3(rsv3);
                    frame.setOpCode(opcode);

                    state = State.PAYLOAD_LEN;
                    break;
                }
                case PAYLOAD_LEN:
                {
                    byte b = buffer.get();
                    getFrame().setMasked((b & 0x80) != 0);
                    payloadLength = (byte)(0x7F & b);

                    if (payloadLength == 127)
                    {
                        // length 8 bytes (extended payload length)
                        payloadLength = 0;
                        state = State.PAYLOAD_LEN_BYTES;
                        cursor = 8;
                        break; // continue onto next state
                    }
                    else if (payloadLength == 126)
                    {
                        // length 2 bytes (extended payload length)
                        payloadLength = 0;
                        state = State.PAYLOAD_LEN_BYTES;
                        cursor = 2;
                        break; // continue onto next state
                    }

                    assertSanePayloadLength(payloadLength);
                    if (getFrame().isMasked())
                    {
                        state = State.MASK;
                    }
                    else
                    {
                        // special case for empty payloads (no more bytes left in buffer)
                        if (payloadLength == 0)
                        {
                            state = State.START;
                            return true;
                        }

                        state = State.PAYLOAD;
                    }

                    break;
                }
                case PAYLOAD_LEN_BYTES:
                {
                    byte b = buffer.get();
                    --cursor;
                    payloadLength |= (b & 0xFF) << (8 * cursor);
                    if (cursor == 0)
                    {
                        assertSanePayloadLength(payloadLength);
                        if (getFrame().isMasked())
                        {
                            state = State.MASK;
                        }
                        else
                        {
                            // special case for empty payloads (no more bytes left in buffer)
                            if (payloadLength == 0)
                            {
                                state = State.START;
                                return true;
                            }

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
                        // special case for empty payloads (no more bytes left in buffer)
                        if (payloadLength == 0)
                        {
                            state = State.START;
                            return true;
                        }

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
                        // special case for empty payloads (no more bytes left in buffer)
                        if (payloadLength == 0)
                        {
                            state = State.START;
                            return true;
                        }

                        state = State.PAYLOAD;
                    }
                    break;
                }
                case PAYLOAD:
                {
                    if (parsePayload(buffer))
                    {
                        // special check for close
                        if (frame.getOpCode() == OpCode.CLOSE)
                        {
                            new CloseInfo(frame);
                        }
                        state = State.START;
                        // we have a frame!
                        return true;
                    }
                    break;
                }
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
        if (payloadLength == 0)
        {
            return true;
        }

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
}
