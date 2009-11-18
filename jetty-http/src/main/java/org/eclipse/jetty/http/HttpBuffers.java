// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.http;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.ThreadLocalBuffers;
import org.eclipse.jetty.util.component.AbstractLifeCycle;

/* ------------------------------------------------------------ */
/** Abstract Buffer pool.
 * simple unbounded pool of buffers for header, request and response sizes.
 *
 */
public abstract class HttpBuffers extends AbstractLifeCycle
{
    
    private final ThreadLocalBuffers _requestBuffers = new ThreadLocalBuffers()
    {
        @Override
        protected Buffer newBuffer(int size)
        {
            return newRequestBuffer(size);
        }

        @Override
        protected Buffer newHeader(int size)
        {
            return newRequestHeader(size);
        }
        @Override
        protected boolean isHeader(Buffer buffer)
        {
            return isRequestHeader(buffer);
        }
    };
    
    private final ThreadLocalBuffers _responseBuffers = new ThreadLocalBuffers()
    {
        @Override
        protected Buffer newBuffer(int size)
        {
            return newResponseBuffer(size);
        }

        @Override
        protected Buffer newHeader(int size)
        {
            return newResponseHeader(size);
        }
        @Override
        protected boolean isHeader(Buffer buffer)
        {
            return isResponseHeader(buffer);
        }
    };
    
    public HttpBuffers()
    {
        super();
        _requestBuffers.setBufferSize(8*1024);
        _requestBuffers.setHeaderSize(6*1024);
        _responseBuffers.setBufferSize(12*1024);
        _responseBuffers.setHeaderSize(6*1024);
    }

    @Override
    protected void doStart()
        throws Exception
    {
        super.doStart();
    }
    
    /**
     * @return Returns the headerBufferSize.
     * @deprecated use {@link #getRequestHeaderSize()} or {@link #getResponseHeaderSize()}
     */
    @Deprecated
    public int getHeaderBufferSize()
    {
        return _requestBuffers.getHeaderSize();
    }

    public Buffers getRequestBuffers()
    {
        return _requestBuffers;
    }
    
    /**
     * @return Returns the requestBufferSize.
     */
    public int getRequestBufferSize()
    {
        return _requestBuffers.getBufferSize();
    }
    
    /**
     * @return Returns the request header size.
     */
    public int getRequestHeaderSize()
    {
        return _requestBuffers.getHeaderSize();
    }

    public Buffers getResponseBuffers()
    {
        return _requestBuffers;
    }
    
    /**
     * @return Returns the responseBufferSize.
     */
    public int getResponseBufferSize()
    {
        return _responseBuffers.getBufferSize();
    }
    
    /**
     * @return Returns the response header size.
     */
    public int getResponseHeaderSize()
    {
        return _responseBuffers.getHeaderSize();
    }

    protected abstract Buffer newRequestBuffer(int size);

    protected abstract Buffer newRequestHeader(int size);

    protected abstract Buffer newResponseBuffer(int size);

    protected abstract Buffer newResponseHeader(int size);
    
    /* ------------------------------------------------------------ */
    /**
     * @param buffer
     * @return True if the buffer is the correct type for a request header buffer
     */
    protected abstract boolean isRequestHeader(Buffer buffer);

    /* ------------------------------------------------------------ */
    /**
     * @param buffer
     * @return True if the buffer is the correct type for a response header buffer
     */
    protected abstract boolean isResponseHeader(Buffer buffer);
    
    

    /**
     * @param headerBufferSize The headerBufferSize to set.
     * @deprecated 
     */
    @Deprecated
    public void setHeaderBufferSize( int headerBufferSize )
    {
        setRequestHeaderSize(headerBufferSize);
        setResponseHeaderSize(headerBufferSize);
    }
    
    /**
     * @param size The requestBufferSize to set.
     */
    public void setRequestBufferSize( int size )
    {
        if (isStarted())
          throw new IllegalStateException();
        _requestBuffers.setBufferSize(size);
    }
    /**
     * @param size 
     */
    public void setRequestHeaderSize( int size )
    {
        if (isStarted())
            throw new IllegalStateException();
        _requestBuffers.setHeaderSize(size);
    }
    
    /**
     * @param size The response buffer size in bytes.
     */
    public void setResponseBufferSize( int size )
    {
        if (isStarted())
          throw new IllegalStateException();
        _responseBuffers.setBufferSize(size);
    }
    /**
     * @param size 
     */
    public void setResponseHeaderSize( int size )
    {
        if (isStarted())
            throw new IllegalStateException();
        _responseBuffers.setHeaderSize(size);
    }
    
}
