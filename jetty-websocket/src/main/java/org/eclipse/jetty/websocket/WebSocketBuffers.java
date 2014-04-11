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

package org.eclipse.jetty.websocket;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.Buffers.Type;
import org.eclipse.jetty.io.BuffersFactory;


/* ------------------------------------------------------------ */
/** The WebSocket Buffer Pool.
 *
 * The normal buffers are byte array buffers so that user processes
 * can access directly.   However the generator uses direct buffers
 * for the final output stage as they are filled in bulk and are more
 * efficient to flush.
 */
public class WebSocketBuffers
{
    final private int _bufferSize;
    final private Buffers _buffers;

    public WebSocketBuffers(final int bufferSize)
    {
        _bufferSize=bufferSize;
        _buffers = BuffersFactory.newBuffers(Type.DIRECT,bufferSize,Type.INDIRECT,bufferSize,Type.INDIRECT,-1);
    }

    public Buffer getBuffer()
    {
        return _buffers.getBuffer();
    }

    public Buffer getDirectBuffer()
    {
        return _buffers.getHeader();
    }

    public void returnBuffer(Buffer buffer)
    {
        _buffers.returnBuffer(buffer);
    }

    public int getBufferSize()
    {
        return _bufferSize;
    }
}
