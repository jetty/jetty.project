package org.eclipse.jetty.http3.qpack.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.qpack.NBitInteger;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class InsertCountIncrementInstruction implements Instruction
{
    private final int _increment;

    public InsertCountIncrementInstruction(int increment)
    {
        _increment = increment;
    }

    @Override
    public void encode(ByteBufferPool.Lease lease)
    {
        int size = NBitInteger.octectsNeeded(6, _increment) + 1;
        ByteBuffer buffer = lease.acquire(size, false);
        buffer.put((byte)0x00);
        NBitInteger.encode(buffer, 6, _increment);
        BufferUtil.flipToFlush(buffer, 0);
        lease.append(buffer, true);
    }
}
