package org.eclipse.jetty.io;

import java.nio.ByteBuffer;


public abstract class AbstractBuffers implements Buffers
{
    protected final Buffers.Type _headerType;
    protected final int _headerSize;
    protected final Buffers.Type _bufferType;
    protected final int _bufferSize;
    protected final Buffers.Type _otherType;

    /* ------------------------------------------------------------ */
    public AbstractBuffers(Buffers.Type headerType, int headerSize, Buffers.Type bufferType, int bufferSize, Buffers.Type otherType)
    {
        _headerType=headerType;
        _headerSize=headerSize;
        _bufferType=bufferType;
        _bufferSize=bufferSize;
        _otherType=otherType;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the buffer size in bytes.
     */
    public int getBufferSize()
    {
        return _bufferSize;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the header size in bytes.
     */
    public int getHeaderSize()
    {
        return _headerSize;
    }


    /* ------------------------------------------------------------ */
    /**
     * Create a new header Buffer
     * @return new Buffer
     */
    final protected ByteBuffer newHeader()
    {
        switch(_headerType)
        {
            case DIRECT:
                return BufferUtil.allocateDirect(_headerSize);
            case INDIRECT:
                return BufferUtil.allocate(_headerSize);
        }
        throw new IllegalStateException();
    }

    /* ------------------------------------------------------------ */
    /**
     * Create a new content Buffer
     * @return new Buffer
     */
    final protected ByteBuffer newBuffer()
    {
       switch(_bufferType)
       {
           case DIRECT:
               return BufferUtil.allocateDirect(_bufferSize);
           case INDIRECT:
               return BufferUtil.allocate(_bufferSize);
       }
       throw new IllegalStateException();
    }

    /* ------------------------------------------------------------ */
    /**
     * Create a new content Buffer
     * @param size
     * @return new Buffer
     */
    final protected ByteBuffer newBuffer(int size)
    {
       switch(_otherType)
       {
           case DIRECT:
               return BufferUtil.allocateDirect(size);
           case INDIRECT:
               return BufferUtil.allocate(size);
       }
       throw new IllegalStateException();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param buffer
     * @return True if the buffer is the correct type to be a Header buffer
     */
    public final boolean isHeader(ByteBuffer buffer)
    {
        if (buffer.capacity()==_headerSize)
        {
            switch(_headerType)
            {
                case DIRECT:
                    return buffer.isDirect();
                    
                case INDIRECT:
                    return !buffer.isDirect();
            }
        }
        return false;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param buffer
     * @return True if the buffer is the correct type to be a Header buffer
     */
    public final boolean isBuffer(ByteBuffer buffer)
    {
        if (buffer.capacity()==_bufferSize)
        {
            switch(_bufferType)
            {
                case DIRECT:
                    return buffer.isDirect();
                    
                case INDIRECT:
                    return !buffer.isDirect();
            }
        }
        return false;
    }

    /* ------------------------------------------------------------ */
    public String toString()
    {
        return String.format("%s [%d,%d]", getClass().getSimpleName(), _headerSize, _bufferSize);
    }
}
