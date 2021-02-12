package org.eclipse.jetty.http3.qpack.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.qpack.NBitInteger;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class StreamCancellationInstruction implements Instruction
{
    private final int _streamId;

    public StreamCancellationInstruction(int streamId)
    {
        _streamId = streamId;
    }

    @Override
    public void encode(ByteBufferPool.Lease lease)
    {
        int size = NBitInteger.octectsNeeded(6, _streamId) + 1;
        ByteBuffer buffer = lease.acquire(size, false);
        buffer.put((byte)0x40);
        NBitInteger.encode(buffer, 6, _streamId);
        BufferUtil.flipToFlush(buffer, 0);
        lease.append(buffer, true);
    }
}
