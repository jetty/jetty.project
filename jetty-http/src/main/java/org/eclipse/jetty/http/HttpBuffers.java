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
 */
public interface HttpBuffers
{
    /**
     * @return the requestBufferSize
     */
    public int getRequestBufferSize();
    
    /**
     * @param requestBufferSize the requestBufferSize to set
     */
    public void setRequestBufferSize(int requestBufferSize);

    /**
     * @return the requestHeaderSize
     */
    public int getRequestHeaderSize();

    /**
     * @param requestHeaderSize the requestHeaderSize to set
     */
    public void setRequestHeaderSize(int requestHeaderSize);

    /**
     * @return the responseBufferSize
     */
    public int getResponseBufferSize();

    /**
     * @param responseBufferSize the responseBufferSize to set
     */
    public void setResponseBufferSize(int responseBufferSize);

    /**
     * @return the responseHeaderSize
     */
    public int getResponseHeaderSize();

    /**
     * @param responseHeaderSize the responseHeaderSize to set
     */
    public void setResponseHeaderSize(int responseHeaderSize);

    /**
     * @return the requestBufferType
     */
    public Buffers.Type getRequestBufferType();

    /**
     * @return the requestHeaderType
     */
    public Buffers.Type getRequestHeaderType();

    /**
     * @return the responseBufferType
     */
    public Buffers.Type getResponseBufferType();

    /**
     * @return the responseHeaderType
     */
    public Buffers.Type getResponseHeaderType();

    /**
     * @param requestBuffers the requestBuffers to set
     */
    public void setRequestBuffers(Buffers requestBuffers);

    /**
     * @param responseBuffers the responseBuffers to set
     */
    public void setResponseBuffers(Buffers responseBuffers);

    public Buffers getRequestBuffers();

    public Buffers getResponseBuffers();

    public void setMaxBuffers(int maxBuffers);

    public int getMaxBuffers();
    
}
