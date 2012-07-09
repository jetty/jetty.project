package org.eclipse.jetty.websocket.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Support class for reading binary message data as an InputStream.
 */
public class MessageInputStream extends InputStream implements StreamAppender
{
    private final ByteBuffer buffer;

    public MessageInputStream(ByteBuffer buf)
    {
        this.buffer = buf;
    }

    @Override
    public void appendBuffer(ByteBuffer buf)
    {
        // TODO Auto-generated method stub
    }

    @Override
    public void bufferComplete() throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public ByteBuffer getBuffer()
    {
        return buffer;
    }

    @Override
    public int read() throws IOException
    {
        // TODO Auto-generated method stub
        return 0;
    }
}
