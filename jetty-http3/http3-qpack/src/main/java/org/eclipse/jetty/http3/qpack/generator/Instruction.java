package org.eclipse.jetty.http3.qpack.generator;

import org.eclipse.jetty.io.ByteBufferPool;

public interface Instruction
{
    void encode(ByteBufferPool.Lease lease);
}
