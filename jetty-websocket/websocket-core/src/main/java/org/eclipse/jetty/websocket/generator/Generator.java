package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.protocol.CloseInfo;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

/**
 * Generating a frame in WebSocket land.
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
public class Generator
{
    private static final Logger LOG = Log.getLogger(Generator.class);
    /**
     * The overhead (maximum) for a framing header. Assuming a maximum sized payload with masking key.
     */
    public static final int OVERHEAD = 28;

    private final WebSocketPolicy policy;
    private final ByteBufferPool bufferPool;
    private boolean validating;

    /**
     * 
     * @param policy
     * @deprecated discouraged usage form
     */
    @Deprecated
    public Generator(WebSocketPolicy policy)
    {
        this(policy,new StandardByteBufferPool());
    }

    /**
     * Construct Generator with provided policy and bufferPool
     * 
     * @param policy
     *            the policy to use
     * @param bufferPool
     *            the buffer pool to use
     */
    public Generator(WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        this(policy,bufferPool,true);
    }

    /**
     * Construct Generator with provided policy and bufferPool
     * 
     * @param policy
     *            the policy to use
     * @param bufferPool
     *            the buffer pool to use
     * @param validating
     *            true to enable RFC frame validation
     */
    public Generator(WebSocketPolicy policy, ByteBufferPool bufferPool, boolean validating)
    {
        this.policy = policy;
        this.bufferPool = bufferPool;
        this.validating = validating;
    }

    public void assertFrameValid(WebSocketFrame frame)
    {
        if (!validating)
        {
            return;
        }

        /*
         * RFC 6455 Section 5.2
         * 
         * MUST be 0 unless an extension is negotiated that defines meanings for non-zero values. If a nonzero value is received and none of the negotiated
         * extensions defines the meaning of such a nonzero value, the receiving endpoint MUST _Fail the WebSocket Connection_.
         */
        if (frame.isRsv1())
        {
            // TODO: extensions can negotiate this (somehow)
            throw new ProtocolException("RSV1 not allowed to be set");
        }

        if (frame.isRsv2())
        {
            // TODO: extensions can negotiate this (somehow)
            throw new ProtocolException("RSV2 not allowed to be set");
        }

        if (frame.isRsv3())
        {
            // TODO: extensions can negotiate this (somehow)
            throw new ProtocolException("RSV3 not allowed to be set");
        }

        if (frame.getOpCode().isControlFrame())
        {
            /*
             * RFC 6455 Section 5.5
             * 
             * All control frames MUST have a payload length of 125 bytes or less and MUST NOT be fragmented.
             */
            if (frame.getPayloadLength() > 125)
            {
                throw new ProtocolException("Invalid control frame payload length");
            }

            if (!frame.isFin())
            {
                throw new ProtocolException("Control Frames must be FIN=true");
            }

            /*
             * RFC 6455 Section 5.5.1
             * 
             * close frame payload is specially formatted which is checked in CloseInfo
             */
            if (frame.getOpCode() == OpCode.CLOSE)
            {

                ByteBuffer payload = frame.getPayload();
                if (payload != null)
                {
                    new CloseInfo(payload,true);
                }
            }
        }

    }

    public ByteBuffer generate(int bufferSize, WebSocketFrame frame)
    {
        LOG.debug(String.format("Generate.Frame[opcode=%s,fin=%b,cont=%b,rsv1=%b,rsv2=%b,rsv3=%b,mask=%b,plength=%d]",frame.getOpCode().toString(),
                frame.isFin(),frame.isContinuation(),frame.isRsv1(),frame.isRsv2(),frame.isRsv3(),frame.isMasked(),frame.getPayloadLength()));

        assertFrameValid(frame);

        /*
         * prepare the byte buffer to put frame into
         */
        ByteBuffer buffer = bufferPool.acquire(bufferSize,true);
        BufferUtil.clearToFill(buffer);

        /*
         * start the generation process
         */
        byte b;

        // Setup fin thru opcode
        b = 0x00;
        if (frame.isFin())
        {
            b |= 0x80; // 1000_0000
        }
        if (frame.isRsv1())
        {
            b |= 0x40; // 0100_0000
        }
        if (frame.isRsv2())
        {
            b |= 0x20; // 0010_0000
        }
        if (frame.isRsv3())
        {
            b |= 0x10;
        }

        byte opcode = frame.getOpCode().getCode();

        if (frame.isContinuation())
        {
            // Continuations are not the same OPCODE
            opcode = OpCode.CONTINUATION.getCode();
        }

        b |= opcode & 0x0F;

        buffer.put(b);

        // is masked
        b = 0x00;
        b |= (frame.isMasked()?0x80:0x00);

        // payload lengths
        int payloadLength = frame.getPayloadLength();

        /*
         * if length is over 65535 then its a 7 + 64 bit length
         */
        if (payloadLength > 0xFF_FF)
        {
            // we have a 64 bit length
            b |= 0x7F;
            buffer.put(b); // indicate 8 byte length
            buffer.put((byte)0); //
            buffer.put((byte)0); // anything over an
            buffer.put((byte)0); // int is just
            buffer.put((byte)0); // intsane!
            buffer.put((byte)((payloadLength >> 24) & 0xFF));
            buffer.put((byte)((payloadLength >> 16) & 0xFF));
            buffer.put((byte)((payloadLength >> 8) & 0xFF));
            buffer.put((byte)(payloadLength & 0xFF));
        }
        /*
         * if payload is ge 126 we have a 7 + 16 bit length
         */
        else if (payloadLength >= 0x7E)
        {
            b |= 0x7E;
            buffer.put(b); // indicate 2 byte length
            buffer.put((byte)(payloadLength >> 8));
            buffer.put((byte)(payloadLength & 0xFF));
        }
        /*
         * we have a 7 bit length
         */
        else
        {
            b |= (payloadLength & 0x7F);
            buffer.put(b);
        }

        // masking key
        if (frame.isMasked())
        {
            buffer.put(frame.getMask());
        }

        // remember the position
        int positionPrePayload = buffer.position();

        // copy payload
        if (frame.hasPayload())
        {
            buffer.put(frame.getPayload());
        }

        int positionPostPayload = buffer.position();

        // mask it if needed
        if (frame.isMasked())
        {
            // move back to remembered position.
            int size = positionPostPayload - positionPrePayload;
            byte[] mask = frame.getMask();
            int pos;
            for (int i = 0; i < size; i++)
            {
                pos = positionPrePayload + i;
                // Mask each byte by its absolute position in the bytebuffer
                buffer.put(pos,(byte)(buffer.get(pos) ^ mask[i % 4]));
            }
        }

        return buffer;
    }

    /**
     * generate a byte buffer based on the frame being passed in
     * 
     * bufferSize is determined by the length of the payload + 28 for frame overhead
     * 
     * @param frame
     * @return
     */
    public ByteBuffer generate(WebSocketFrame frame)
    {
        int bufferSize = frame.getPayloadLength() + OVERHEAD;

        return generate(bufferSize,frame);
    }

    @Override
    public String toString()
    {
        return String.format("Generator [basic=%s]",this.getClass().getSimpleName());
    }

}
