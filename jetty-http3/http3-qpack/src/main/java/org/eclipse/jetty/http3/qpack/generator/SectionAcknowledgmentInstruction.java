package org.eclipse.jetty.http3.qpack.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.qpack.NBitInteger;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class SectionAcknowledgmentInstruction implements Instruction
{
    private final int _streamId;

    public SectionAcknowledgmentInstruction(int streamId)
    {
        _streamId = streamId;
    }

    @Override
    public void encode(ByteBufferPool.Lease lease)
    {
        int size = NBitInteger.octectsNeeded(7, _streamId) + 1;
        ByteBuffer buffer = lease.acquire(size, false);
        buffer.put((byte)0x80);
        NBitInteger.encode(buffer, 7, _streamId);
        BufferUtil.flipToFlush(buffer, 0);
        lease.append(buffer, true);
    }
}
