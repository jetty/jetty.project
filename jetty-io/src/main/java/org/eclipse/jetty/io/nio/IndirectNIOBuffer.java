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

package org.eclipse.jetty.io.nio;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteArrayBuffer;

public class IndirectNIOBuffer extends ByteArrayBuffer implements NIOBuffer
{
    protected final ByteBuffer _buf;

    /* ------------------------------------------------------------ */
    public IndirectNIOBuffer(int size)
    {
        super(size,READWRITE,NON_VOLATILE);
        _buf = ByteBuffer.wrap(_bytes);
        _buf.position(0);
        _buf.limit(_buf.capacity());
    }

    /* ------------------------------------------------------------ */
    public IndirectNIOBuffer(ByteBuffer buffer,boolean immutable)
    {
        super(buffer.array(),0,0, immutable?IMMUTABLE:READWRITE,NON_VOLATILE);
        if (buffer.isDirect())
            throw new IllegalArgumentException();
        _buf = buffer;
        _get=buffer.position();
        _put=buffer.limit();
        buffer.position(0);
        buffer.limit(buffer.capacity());
    }
    
    /* ------------------------------------------------------------ */
    public ByteBuffer getByteBuffer()
    {
        return _buf;
    }

    /* ------------------------------------------------------------ */
    public boolean isDirect()
    {
        return false;
    }
}
