package org.eclipse.jetty.client.util;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Iterator;

import org.eclipse.jetty.client.api.ContentProvider;

public class ByteBufferContentProvider implements ContentProvider
{
    private final ByteBuffer[] buffers;

    public ByteBufferContentProvider(ByteBuffer... buffers)
    {
        this.buffers = buffers;
    }

    @Override
    public long length()
    {
        int length = 0;
        for (ByteBuffer buffer : buffers)
            length += buffer.remaining();
        return length;
    }

    @Override
    public Iterator<ByteBuffer> iterator()
    {
        return Arrays.asList(buffers).iterator();
    }
}
