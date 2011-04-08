package org.eclipse.jetty.io;

public class BuffersFactory
{
    public static Buffers newBuffers(Buffers.Type headerType, int headerSize, Buffers.Type bufferType, int bufferSize, Buffers.Type otherType,int maxSize)
    {
        if (maxSize>=0)
            return new PooledBuffers(headerType,headerSize,bufferType,bufferSize,otherType,maxSize);
        return new ThreadLocalBuffers(headerType,headerSize,bufferType,bufferSize,otherType);
    }
}
