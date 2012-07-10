package org.eclipse.jetty.websocket.io;

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;

/**
 * Support class for reading text message data as an Reader.
 * <p>
 * Due to the spec, this reader is forced to use the UTF8 charset.
 */
public class MessageReader extends Reader implements StreamAppender
{
    private ByteBuffer buffer;

    public MessageReader(ByteBuffer buf)
    {
        this.buffer = buf;
    }

    @Override
    public void appendBuffer(ByteBuffer byteBuffer)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void bufferComplete() throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void close() throws IOException
    {
        // TODO Auto-generated method stub
    }

    @Override
    public ByteBuffer getBuffer()
    {
        return buffer;
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException
    {
        // TODO Auto-generated method stub
        return 0;
    }
}
