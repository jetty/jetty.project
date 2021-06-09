package org.eclipse.jetty.io;

public class AdapterMemoryPool implements MemoryPool<RetainableByteBuffer>
{
    private final ByteBufferPool byteBufferPool;

    public AdapterMemoryPool(ByteBufferPool byteBufferPool)
    {
        this.byteBufferPool = byteBufferPool;
    }

    @Override
    public RetainableByteBuffer acquire(int size, boolean direct)
    {
        return new RetainableByteBuffer(byteBufferPool, size, direct);
    }

    @Override
    public void release(RetainableByteBuffer buffer)
    {
        buffer.release();
    }
}
