// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
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
package org.eclipse.jetty.io.nio;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteArrayBuffer;

public class IndirectNIOBuffer extends ByteArrayBuffer implements NIOBuffer
{
    protected ByteBuffer _buf;

    /* ------------------------------------------------------------ */
    public IndirectNIOBuffer(int size)
    {
        super(READWRITE,NON_VOLATILE);
        _buf = ByteBuffer.allocate(size);
        _buf.position(0);
        _buf.limit(_buf.capacity());
        _bytes=_buf.array();
    }

    /* ------------------------------------------------------------ */
    public IndirectNIOBuffer(ByteBuffer buffer,boolean immutable)
    {
        super(immutable?IMMUTABLE:READWRITE,NON_VOLATILE);
        if (buffer.isDirect())
            throw new IllegalArgumentException();
        _buf = buffer;
        setGetIndex(buffer.position());
        setPutIndex(buffer.limit());
        _bytes=_buf.array();
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
