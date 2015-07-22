//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.server;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;

public class ByteBufferCallback implements Callback
{
    private final ByteBufferPool byteBufferPool;
    private final ByteBuffer buffer;
    private final Callback callback;

    public ByteBufferCallback(ByteBufferPool byteBufferPool, ByteBuffer buffer, Callback callback)
    {
        this.byteBufferPool = byteBufferPool;
        this.buffer = buffer;
        this.callback = callback;
    }

    @Override
    public boolean isNonBlocking()
    {
        return callback.isNonBlocking();
    }
    
    public ByteBuffer getByteBuffer()
    {
        return buffer;
    }

    @Override
    public void succeeded()
    {
        recycle();
        callback.succeeded();
    }

    @Override
    public void failed(Throwable x)
    {
        recycle();
        callback.failed(x);
    }

    private void recycle()
    {
        byteBufferPool.release(buffer);
    }
}
