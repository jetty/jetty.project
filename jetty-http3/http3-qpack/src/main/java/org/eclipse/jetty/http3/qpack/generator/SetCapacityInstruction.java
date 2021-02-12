package org.eclipse.jetty.http3.qpack.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.qpack.NBitInteger;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class SetCapacityInstruction implements Instruction
{
    private final int _capacity;

    public SetCapacityInstruction(int capacity)
    {
        _capacity = capacity;
    }

    @Override
    public void encode(ByteBufferPool.Lease lease)
    {
        int size = NBitInteger.octectsNeeded(5, _capacity) + 1;
        ByteBuffer buffer = lease.acquire(size, false);
        buffer.put((byte)0x20);
        NBitInteger.encode(buffer, 5, _capacity);
        BufferUtil.flipToFlush(buffer, 0);
        lease.append(buffer, true);
    }
}
