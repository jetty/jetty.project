package org.eclipse.jetty.http3.qpack.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.qpack.NBitInteger;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class DuplicateInstruction implements Instruction
{
    private final int _index;

    public DuplicateInstruction(int index)
    {
        _index = index;
    }

    @Override
    public void encode(ByteBufferPool.Lease lease)
    {
        int size = NBitInteger.octectsNeeded(5, _index) + 1;
        ByteBuffer buffer = lease.acquire(size, false);
        buffer.put((byte)0x00);
        NBitInteger.encode(buffer, 5, _index);
        BufferUtil.flipToFlush(buffer, 0);
        lease.append(buffer, true);
    }
}
