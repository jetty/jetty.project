package org.eclipse.jetty.io;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;

public class NullByteBufferPool implements ByteBufferPool
{
    @Override
    public ByteBuffer acquire(int size, boolean direct)
    {
        return BufferUtil.allocate(size, direct);
    }

    @Override
    public void release(ByteBuffer buffer)
    {
        // Do nothing.
    }
}
