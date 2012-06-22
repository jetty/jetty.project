package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.websocket.api.OpCode;
import org.eclipse.jetty.websocket.api.PolicyViolationException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.BaseFrame;

public abstract class FrameGenerator<T extends BaseFrame>
{
    private final ByteBufferPool bufferPool;
    private final WebSocketPolicy policy;

    protected FrameGenerator(ByteBufferPool bufferPool, WebSocketPolicy policy)
    {
        this.bufferPool = bufferPool;
        this.policy = policy;
    }

    public ByteBuffer generate(T frame)
    {
        ByteBuffer framing = ByteBuffer.allocate(16);

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

        framing.put(b);

        // is masked
        b = 0x00;
        b |= (frame.isMasked()?0x80:0x00);

        // payload lengths
        int payloadLength = frame.getPayloadLength();
        if ((payloadLength >= 0x7F) && (payloadLength <= 0xFF_FF))
        {
            // we have a 16 bit length
            b |= 0x7E;
            framing.put(b); // indicate 2 byte length
            framing.putShort((short)(payloadLength & 0xFF_FF)); // write 2 byte length
        }
        else if (payloadLength >= 0xFFFF)
        {
            // we have a 64 bit length
            b |= 0x7F;
            framing.put(b); // indicate 4 byte length
            framing.putInt(payloadLength); // write 4 byte length
        }
        else
        {
            // we have a 7 bit length
            b |= (payloadLength & 0x7F);
            framing.put(b);
        }

        // masking key
        if (frame.isMasked())
        {
            // TODO: figure out maskgen
            framing.put(frame.getMask());
        }

        framing.flip(); // to figure out how many bytes are used

        // now the payload itself
        int buflen = frame.getPayloadLength() + framing.remaining();
        ByteBuffer buffer = ByteBuffer.allocate(buflen);
        // TODO: figure out how to get this from a bytebuffer pool

        // TODO see if we can avoid the extra buffer here, make convention of payload
        // call back into masking check/method on this class?

        // generate payload
        ByteBuffer payloadBuffer = payload(frame);

        // insert framing
        buffer.put(framing);

        // mask it if needed
        if ( frame.isMasked() )
        {
            int size = frame.getPayloadLength();
            byte[] mask = frame.getMask();
            for (int i=0;i<size;i++)
            {
                buffer.put((byte)(payloadBuffer.get() ^ mask[i % 4]));
            }
        }
        else
        {
            buffer.put(payloadBuffer);
        }

        return buffer;
    }

    protected ByteBufferPool getByteBufferPool()
    {
        return bufferPool;
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    public abstract ByteBuffer payload(T frame);
}
