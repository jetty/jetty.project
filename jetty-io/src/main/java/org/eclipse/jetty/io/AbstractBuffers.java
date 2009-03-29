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

package org.eclipse.jetty.io;

import org.eclipse.jetty.util.component.AbstractLifeCycle;

/* ------------------------------------------------------------ */
/** Abstract Buffer pool.
 * simple unbounded pool of buffers for header, request and response sizes.
 *
 */
public abstract class AbstractBuffers extends AbstractLifeCycle implements Buffers
{
    private int _headerBufferSize=4*1024;
    private int _requestBufferSize=8*1024;
    private int _responseBufferSize=24*1024;

    final static private int __HEADER=0;
    final static private int __REQUEST=1;
    final static private int __RESPONSE=2;
    final static private int __OTHER=3;
    final private int[] _pool={2,1,1,2};

    private final ThreadLocal _buffers=new ThreadLocal()
    {
        protected Object initialValue()
        {
            return new ThreadBuffers(_pool[__HEADER],_pool[__REQUEST],_pool[__RESPONSE],_pool[__OTHER]);
        }
    };
   
    public AbstractBuffers()
    {
        super();
    }



    public Buffer getBuffer(final int size )
    {
        final int set = (size==_headerBufferSize)?__HEADER
                :(size==_responseBufferSize)?__RESPONSE
                        :(size==_requestBufferSize)?__REQUEST:__OTHER;

        final ThreadBuffers thread_buffers = (ThreadBuffers)_buffers.get();

        final Buffer[] buffers=thread_buffers._buffers[set];
        for (int i=0;i<buffers.length;i++)
        {
            final Buffer b=buffers[i];
            if (b!=null && b.capacity()==size)
            {
                buffers[i]=null;
                return b;
            }
        }

        return newBuffer(size);
    }

    public void returnBuffer( Buffer buffer )
    {
        buffer.clear();
        if (buffer.isVolatile() || buffer.isImmutable())
            return;

        int size=buffer.capacity();
        final int set = (size==_headerBufferSize)?__HEADER
                :(size==_responseBufferSize)?__RESPONSE
                        :(size==_requestBufferSize)?__REQUEST:__OTHER;
        
        final ThreadBuffers thread_buffers = (ThreadBuffers)_buffers.get();
        final Buffer[] buffers=thread_buffers._buffers[set];
        for (int i=0;i<buffers.length;i++)
        {
            if (buffers[i]==null)
            {
                buffers[i]=buffer;
                return;
            }
        }

    }

    protected void doStart()
        throws Exception
    {
        super.doStart();
        if (_headerBufferSize==_requestBufferSize && _headerBufferSize==_responseBufferSize)
        {
            _pool[__HEADER]+=_pool[__REQUEST]+_pool[__RESPONSE];
            _pool[__REQUEST]=0;
            _pool[__RESPONSE]=0;
        }
        else if (_headerBufferSize==_requestBufferSize)
        {
            _pool[__HEADER]+=_pool[__REQUEST];
            _pool[__REQUEST]=0;
        }
        else if (_headerBufferSize==_responseBufferSize)
        {
            _pool[__HEADER]+=_pool[__RESPONSE];
            _pool[__RESPONSE]=0;
        }
        else if (_requestBufferSize==_responseBufferSize)
        {
            _pool[__RESPONSE]+=_pool[__REQUEST];
            _pool[__REQUEST]=0;
        }

    }

    /**
     * @return Returns the headerBufferSize.
     */
    public int getHeaderBufferSize()
    {
        return _headerBufferSize;
    }

    /**
     * @param headerBufferSize The headerBufferSize to set.
     */
    public void setHeaderBufferSize( int headerBufferSize )
    {
        if (isStarted())
            throw new IllegalStateException();
        _headerBufferSize = headerBufferSize;
    }

    /**
     * @return Returns the requestBufferSize.
     */
    public int getRequestBufferSize()
    {
        return _requestBufferSize;
    }

    /**
     * @param requestBufferSize The requestBufferSize to set.
     */
    public void setRequestBufferSize( int requestBufferSize )
    {
        if (isStarted())
          throw new IllegalStateException();
        _requestBufferSize = requestBufferSize;
    }

    /**
     * @return Returns the responseBufferSize.
     */
    public int getResponseBufferSize()
    {
        return _responseBufferSize;
    }

    /**
     * @param responseBufferSize The responseBufferSize to set.
     */
    public void setResponseBufferSize( int responseBufferSize )
    {
        if (isStarted())
            throw new IllegalStateException();
        _responseBufferSize = responseBufferSize;
    }
    
    protected abstract Buffer newBuffer( int size );

    protected static class ThreadBuffers
    {
        final Buffer[][] _buffers;
        ThreadBuffers(int headers,int requests,int responses,int others)
        {
            _buffers = new Buffer[4][];
            _buffers[__HEADER]=new Buffer[headers];
            _buffers[__REQUEST]=new Buffer[requests];
            _buffers[__RESPONSE]=new Buffer[responses];
            _buffers[__OTHER]=new Buffer[others];

        }
    }
}
