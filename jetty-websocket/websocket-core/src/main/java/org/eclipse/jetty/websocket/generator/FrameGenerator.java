package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.OpCode;
import org.eclipse.jetty.websocket.api.PolicyViolationException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.BaseFrame;

/**
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
 * 
 * @param <T>
 */
public abstract class FrameGenerator<T extends BaseFrame>
{
    private static final Logger LOG = Log.getLogger(FrameGenerator.class);

    /**
     * The overhead (maximum) for a framing header. Assuming a maximum sized payload with masking key.
     */
    public static final int OVERHEAD = 28;
    private final WebSocketPolicy policy;

    protected FrameGenerator(WebSocketPolicy policy)
    {
        this.policy = policy;
    }

    public abstract void fillPayload(ByteBuffer buffer, T frame);

    public ByteBuffer generate(ByteBuffer buffer, T frame)
    {
        LOG.debug(String.format("Generate.Frame[opcode=%s,fin=%b,cont=%b,rsv1=%b,rsv2=%b,rsv3=%b,mask=%b,plength=%d]",frame.getOpCode().toString(),
                frame.isFin(),frame.isContinuation(),frame.isRsv1(),frame.isRsv2(),frame.isRsv3(),frame.isMasked(),frame.getPayloadLength()));

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
            // TODO: extensions can negotiate this (somehow)
            throw new PolicyViolationException("RSV1 not allowed to be set");
        }
        if (frame.isRsv2())
        {
            b |= 0x20; // 0010_0000
            // TODO: extensions can negotiate this (somehow)
            throw new PolicyViolationException("RSV2 not allowed to be set");
        }
        if (frame.isRsv3())
        {
            b |= 0x10;
            // TODO: extensions can negotiate this (somehow)
            throw new PolicyViolationException("RSV3 not allowed to be set");
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
            // TODO: figure out maskgen
            buffer.put(frame.getMask());
        }

        // now the payload itself

        // call back into masking check/method on this class?

        // remember the position
        int positionPrePayload = buffer.position();

        // generate payload
        fillPayload(buffer, frame);

        int positionPostPayload = buffer.position();

        // mask it if needed
        if ( frame.isMasked() )
        {
            // move back to remembered position.
            int size = positionPostPayload - positionPrePayload;
            byte[] mask = frame.getMask();
            int pos;
            for (int i=0;i<size;i++)
            {
                pos = positionPrePayload + i;
                // Mask each byte by its absolute position in the bytebuffer
                buffer.put(pos, (byte)(buffer.get(pos) ^ mask[i % 4]));
            }
        }

        return buffer;
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
    }
}
