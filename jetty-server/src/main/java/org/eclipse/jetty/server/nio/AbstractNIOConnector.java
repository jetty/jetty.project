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

/**
 * 
 */
package org.eclipse.jetty.server.nio;

import org.eclipse.jetty.io.Buffers.Type;
import org.eclipse.jetty.server.AbstractConnector;

/* ------------------------------------------------------------ */
/**
 * 
 *
 */
public abstract class AbstractNIOConnector extends AbstractConnector implements NIOConnector
{
    {
        setRequestBufferType(Type.DIRECT);
        setRequestHeaderType(Type.INDIRECT);
        setResponseBufferType(Type.DIRECT);
        setResponseHeaderType(Type.INDIRECT);
    }
 
    /* ------------------------------------------------------------------------------- */
    public boolean getUseDirectBuffers()
    {
        return getRequestBufferType()==Type.DIRECT;
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * @param direct If True (the default), the connector can use NIO direct buffers.
     * Some JVMs have memory management issues (bugs) with direct buffers.
     */
    public void setUseDirectBuffers(boolean direct)
    {
        setRequestBufferType(direct?Type.DIRECT:Type.INDIRECT);
        setResponseBufferType(direct?Type.DIRECT:Type.INDIRECT);
    }

}
