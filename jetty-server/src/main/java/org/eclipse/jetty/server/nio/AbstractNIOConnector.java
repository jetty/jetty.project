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

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.nio.DirectNIOBuffer;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.eclipse.jetty.io.nio.NIOBuffer;
import org.eclipse.jetty.server.AbstractConnector;

/* ------------------------------------------------------------ */
/**
 * 
 *
 */
public abstract class AbstractNIOConnector extends AbstractConnector implements NIOConnector
{
    private boolean _useDirectBuffers=true;
 
    /* ------------------------------------------------------------------------------- */
    public boolean getUseDirectBuffers()
    {
        return _useDirectBuffers;
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * @param direct If True (the default), the connector can use NIO direct buffers.
     * Some JVMs have memory management issues (bugs) with direct buffers.
     */
    public void setUseDirectBuffers(boolean direct)
    {
        _useDirectBuffers=direct;
    }

    /* ------------------------------------------------------------------------------- */
    public Buffer newBuffer(int size)
    {
        // TODO
        // Header buffers always byte array buffers (efficiency of random access)
        // There are lots of things to consider here... DIRECT buffers are faster to
        // send but more expensive to build and access! so we have choices to make...
        // + headers are constructed bit by bit and parsed bit by bit, so INDiRECT looks
        // good for them.   
        // + but will a gather write of an INDIRECT header with a DIRECT body be any good?
        // this needs to be benchmarked.
        // + Will it be possible to get a DIRECT header buffer just for the gather writes of
        // content from file mapped buffers?  
        // + Are gather writes worth the effort?  Maybe they will work well with two INDIRECT
        // buffers being copied into a single kernel buffer?
        // 
        Buffer buf = null;
        if (size==getHeaderBufferSize())
            buf= new IndirectNIOBuffer(size);
        else
            buf = _useDirectBuffers
                ?(NIOBuffer)new DirectNIOBuffer(size)
                :(NIOBuffer)new IndirectNIOBuffer(size);
        return buf;
    }
    

}
