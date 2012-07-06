package org.eclipse.jetty.websocket.protocol;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.PolicyViolationException;
import org.eclipse.jetty.websocket.frames.BaseFrame;


public class FrameBuilder
{
    public static FrameBuilder binary()
    {
        return new FrameBuilder(new BaseFrame(OpCode.BINARY));
    }

    public static FrameBuilder binary(byte[] payload)
    {
        return new FrameBuilder(new BaseFrame(OpCode.BINARY)).payload(payload);
    }

    public static FrameBuilder binary(byte[] payload, int offset, int length)
    {
        return new FrameBuilder(new BaseFrame(OpCode.BINARY)).payload(payload,offset,length);
    }

    public static FrameBuilder close()
    {
        return close(-1,null);
    }

    public static FrameBuilder close(int statusCode)
    {
        return close(statusCode,null);
    }

    public static FrameBuilder close(int statusCode, String reason)
    {
        if (statusCode != (-1))
        {
            ByteBuffer buf = ByteBuffer.allocate(BaseFrame.MAX_CONTROL_PAYLOAD);
            buf.putChar((char)statusCode);
            if (StringUtil.isNotBlank(reason))
            {
                byte utf[] = StringUtil.getUtf8Bytes(reason);
                buf.put(utf,0,utf.length);
            }
            return new FrameBuilder(new BaseFrame(OpCode.CLOSE)).payload(buf);
        }
        return new FrameBuilder(new BaseFrame(OpCode.CLOSE));
    }

    public static FrameBuilder continuation()
    {
        return new FrameBuilder(new BaseFrame(OpCode.CONTINUATION));
    }

    public static FrameBuilder continuation(byte[] payload)
    {
        return new FrameBuilder(new BaseFrame(OpCode.CONTINUATION)).payload(payload);
    }

    public static FrameBuilder continuation(String payload)
    {
        return new FrameBuilder(new BaseFrame(OpCode.CONTINUATION)).payload(payload);
    }

    public static FrameBuilder ping()
    {
        return new FrameBuilder(new BaseFrame(OpCode.PING));
    }

    public static FrameBuilder pong()
    {
        return new FrameBuilder(new BaseFrame(OpCode.PONG));
    }

    public static FrameBuilder text()
    {
        return new FrameBuilder(new BaseFrame(OpCode.TEXT));
    }

    public static FrameBuilder text(String text)
    {
        return new FrameBuilder(new BaseFrame(OpCode.TEXT)).payload(text.getBytes());
    }

    private BaseFrame frame;

    public FrameBuilder(BaseFrame frame)
    {
        this.frame = frame;
        this.frame.setFin(true); // default
    }

    public byte[] asByteArray()
    {
        return BufferUtil.toArray(asByteBuffer());
    }

    public ByteBuffer asByteBuffer()
    {
        ByteBuffer buffer = ByteBuffer.allocate(frame.getPayloadLength() + 32 );
        fill(buffer);
        return buffer;
    }

    public BaseFrame asFrame()
    {
        return frame;
    }

    public void fill(ByteBuffer buffer)
    {
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

        // remember the position
        int positionPrePayload = buffer.position();

        // generate payload
        if (frame.getPayloadLength() > 0)
        {
            BufferUtil.put(frame.getPayload(),buffer);
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

        BufferUtil.flipToFlush(buffer,0);
    }

    public FrameBuilder fin( boolean fin )
    {
        frame.setFin(fin);

        return this;
    }

    public FrameBuilder mask(byte[] mask)
    {
        frame.setMasked(true);
        frame.setMask(mask);

        return this;
    }

    public FrameBuilder payload(byte[] payload)
    {
        frame.setPayload(payload);
        return this;
    }

    public FrameBuilder payload(byte[] payload, int offset, int length)
    {
        ByteBuffer bb = ByteBuffer.allocate(length);
        bb.put(payload,offset,length);
        frame.setPayload(bb);
        return this;
    }

    public FrameBuilder payload(ByteBuffer payload)
    {
        frame.setPayload(payload);
        return this;
    }

    public FrameBuilder payload(String payload)
    {
        frame.setPayload(payload.getBytes());
        return this;
    }

    public FrameBuilder rsv1(boolean rsv1)
    {
        frame.setRsv1(rsv1);

        return this;
    }

    public FrameBuilder rsv2(boolean rsv2)
    {
        frame.setRsv2(rsv2);

        return this;
    }

    public FrameBuilder rsv3(boolean rsv3)
    {
        frame.setRsv3(rsv3);

        return this;
    }
}
