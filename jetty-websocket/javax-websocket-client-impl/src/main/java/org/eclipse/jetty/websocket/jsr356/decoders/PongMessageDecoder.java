package org.eclipse.jetty.websocket.jsr356.decoders;

import java.nio.ByteBuffer;

import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.PongMessage;

import org.eclipse.jetty.util.BufferUtil;

public class PongMessageDecoder extends AbstractDecoder implements Decoder.Binary<PongMessage>
{
    private static class PongMsg implements PongMessage
    {
        private final ByteBuffer bytes;

        public PongMsg(ByteBuffer buf)
        {
            int len = buf.remaining();
            this.bytes = ByteBuffer.allocate(len);
            BufferUtil.put(buf,this.bytes);
            BufferUtil.flipToFlush(this.bytes,0);
        }

        @Override
        public ByteBuffer getApplicationData()
        {
            return this.bytes;
        }
    }

    @Override
    public PongMessage decode(ByteBuffer bytes) throws DecodeException
    {
        return new PongMsg(bytes);
    }

    @Override
    public boolean willDecode(ByteBuffer bytes)
    {
        return true;
    }
}
