package org.eclipse.jetty.websocket.protocol;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.generator.Generator;


/**
 * The FrameBuilder applies a builder pattern to constructing WebSocketFrame classes.
 * 
 * WARNING: It is possible to build bad frames using this builder which is intended
 * 
 */
public class FrameBuilder
{
    /**
     * A Generator that doesn't
     */
    public class DirtyGenerator extends Generator
    {
        public DirtyGenerator()
        {
            super(WebSocketPolicy.newServerPolicy(),bufferPool);
        }

        @Override
        public void assertFrameValid(WebSocketFrame frame)
        {
            /*
             * Do no validation of the frame validity. <p> we desire the ability to craft bad frames so we'll ignore frame validation
             */
        }
    }

    private static ByteBufferPool bufferPool = new StandardByteBufferPool();

    public static FrameBuilder binary()
    {
        return new FrameBuilder(new WebSocketFrame(OpCode.BINARY));
    }

    public static FrameBuilder binary(byte[] payload)
    {
        return new FrameBuilder(new WebSocketFrame(OpCode.BINARY)).payload(payload);
    }

    public static FrameBuilder binary(byte[] payload, int offset, int length)
    {
        return new FrameBuilder(new WebSocketFrame(OpCode.BINARY)).payload(payload,offset,length);
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
            ByteBuffer buf = ByteBuffer.allocate(WebSocketFrame.MAX_CONTROL_PAYLOAD);
            buf.putChar((char)statusCode);
            if (StringUtil.isNotBlank(reason))
            {
                byte utf[] = StringUtil.getUtf8Bytes(reason);
                buf.put(utf,0,utf.length);
            }
            BufferUtil.flipToFlush(buf,0);
            return new FrameBuilder(new WebSocketFrame(OpCode.CLOSE)).payload(BufferUtil.toArray(buf));
        }
        return new FrameBuilder(new WebSocketFrame(OpCode.CLOSE));
    }

    public static FrameBuilder continuation()
    {
        return new FrameBuilder(new WebSocketFrame(OpCode.CONTINUATION));
    }

    public static FrameBuilder continuation(byte[] payload)
    {
        return new FrameBuilder(new WebSocketFrame(OpCode.CONTINUATION)).payload(payload);
    }

    public static FrameBuilder continuation(String payload)
    {
        return new FrameBuilder(new WebSocketFrame(OpCode.CONTINUATION)).payload(payload);
    }

    public static FrameBuilder ping()
    {
        return new FrameBuilder(new WebSocketFrame(OpCode.PING));
    }

    public static FrameBuilder ping(String message)
    {
        return new FrameBuilder(new WebSocketFrame(OpCode.PING)).payload(message.getBytes(StringUtil.__UTF8_CHARSET));
    }

    public static FrameBuilder pong()
    {
        return new FrameBuilder(new WebSocketFrame(OpCode.PONG));
    }

    public static FrameBuilder pong(String message)
    {
        return new FrameBuilder(new WebSocketFrame(OpCode.PONG)).payload(message.getBytes(StringUtil.__UTF8_CHARSET));
    }

    public static FrameBuilder text()
    {
        return new FrameBuilder(new WebSocketFrame(OpCode.TEXT));
    }

    public static FrameBuilder text(String text)
    {
        return new FrameBuilder(new WebSocketFrame(OpCode.TEXT)).payload(text.getBytes(StringUtil.__UTF8_CHARSET));
    }

    DirtyGenerator generator = new DirtyGenerator();

    private WebSocketFrame frame;

    public FrameBuilder(WebSocketFrame frame)
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
        return generator.generate(frame);
    }

    public WebSocketFrame asFrame()
    {
        return frame;
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
        frame.setPayload(payload,offset,length);
        return this;
    }

    public FrameBuilder payload(ByteBuffer payload)
    {
        frame.setPayload(BufferUtil.toArray(payload));
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
