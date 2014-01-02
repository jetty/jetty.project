//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http;

import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.BuffersFactory;
import org.eclipse.jetty.util.component.AbstractLifeCycle;

/* ------------------------------------------------------------ */
/** Abstract Buffer pool.
 * simple unbounded pool of buffers for header, request and response sizes.
 *
 */
public class HttpBuffersImpl extends AbstractLifeCycle implements HttpBuffers
{
    private int _requestBufferSize=16*1024;
    private int _requestHeaderSize=6*1024;
    private int _responseBufferSize=32*1024;
    private int _responseHeaderSize=6*1024;
    private int _maxBuffers=1024;
    
    private Buffers.Type _requestBufferType=Buffers.Type.BYTE_ARRAY;
    private Buffers.Type _requestHeaderType=Buffers.Type.BYTE_ARRAY;
    private Buffers.Type _responseBufferType=Buffers.Type.BYTE_ARRAY;
    private Buffers.Type _responseHeaderType=Buffers.Type.BYTE_ARRAY;
    
    private Buffers _requestBuffers;
    private Buffers _responseBuffers;
    
    
    public HttpBuffersImpl()
    {
        super();
    }
    
    /**
     * @return the requestBufferSize
     */
    public int getRequestBufferSize()
    {
        return _requestBufferSize;
    }
    
    /**
     * @param requestBufferSize the requestBufferSize to set
     */
    public void setRequestBufferSize(int requestBufferSize)
    {
        _requestBufferSize = requestBufferSize;
    }

    /**
     * @return the requestHeaderSize
     */
    public int getRequestHeaderSize()
    {
        return _requestHeaderSize;
    }

    /**
     * @param requestHeaderSize the requestHeaderSize to set
     */
    public void setRequestHeaderSize(int requestHeaderSize)
    {
        _requestHeaderSize = requestHeaderSize;
    }

    /**
     * @return the responseBufferSize
     */
    public int getResponseBufferSize()
    {
        return _responseBufferSize;
    }

    /**
     * @param responseBufferSize the responseBufferSize to set
     */
    public void setResponseBufferSize(int responseBufferSize)
    {
        _responseBufferSize = responseBufferSize;
    }

    /**
     * @return the responseHeaderSize
     */
    public int getResponseHeaderSize()
    {
        return _responseHeaderSize;
    }

    /**
     * @param responseHeaderSize the responseHeaderSize to set
     */
    public void setResponseHeaderSize(int responseHeaderSize)
    {
        _responseHeaderSize = responseHeaderSize;
    }

    /**
     * @return the requestBufferType
     */
    public Buffers.Type getRequestBufferType()
    {
        return _requestBufferType;
    }

    /**
     * @param requestBufferType the requestBufferType to set
     */
    public void setRequestBufferType(Buffers.Type requestBufferType)
    {
        _requestBufferType = requestBufferType;
    }

    /**
     * @return the requestHeaderType
     */
    public Buffers.Type getRequestHeaderType()
    {
        return _requestHeaderType;
    }

    /**
     * @param requestHeaderType the requestHeaderType to set
     */
    public void setRequestHeaderType(Buffers.Type requestHeaderType)
    {
        _requestHeaderType = requestHeaderType;
    }

    /**
     * @return the responseBufferType
     */
    public Buffers.Type getResponseBufferType()
    {
        return _responseBufferType;
    }

    /**
     * @param responseBufferType the responseBufferType to set
     */
    public void setResponseBufferType(Buffers.Type responseBufferType)
    {
        _responseBufferType = responseBufferType;
    }

    /**
     * @return the responseHeaderType
     */
    public Buffers.Type getResponseHeaderType()
    {
        return _responseHeaderType;
    }

    /**
     * @param responseHeaderType the responseHeaderType to set
     */
    public void setResponseHeaderType(Buffers.Type responseHeaderType)
    {
        _responseHeaderType = responseHeaderType;
    }

    /**
     * @param requestBuffers the requestBuffers to set
     */
    public void setRequestBuffers(Buffers requestBuffers)
    {
        _requestBuffers = requestBuffers;
    }

    /**
     * @param responseBuffers the responseBuffers to set
     */
    public void setResponseBuffers(Buffers responseBuffers)
    {
        _responseBuffers = responseBuffers;
    }

    @Override
    protected void doStart()
        throws Exception
    {
        _requestBuffers=BuffersFactory.newBuffers(_requestHeaderType,_requestHeaderSize,_requestBufferType,_requestBufferSize,_requestBufferType,getMaxBuffers());
        _responseBuffers=BuffersFactory.newBuffers(_responseHeaderType,_responseHeaderSize,_responseBufferType,_responseBufferSize,_responseBufferType,getMaxBuffers());
        super.doStart();
    }
    
    @Override
    protected void doStop()
        throws Exception
    {
        _requestBuffers=null;
        _responseBuffers=null;
    }

    public Buffers getRequestBuffers()
    {
        return _requestBuffers;
    }
    

    public Buffers getResponseBuffers()
    {
        return _responseBuffers;
    }

    public void setMaxBuffers(int maxBuffers)
    {
        _maxBuffers = maxBuffers;
    }

    public int getMaxBuffers()
    {
        return _maxBuffers;
    }
    
    public String toString()
    {
        return _requestBuffers+"/"+_responseBuffers;
    }
}
