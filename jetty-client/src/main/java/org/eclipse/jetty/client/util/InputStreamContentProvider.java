//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client.util;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.util.BufferUtil;

public class InputStreamContentProvider implements ContentProvider
{
    private final InputStream stream;
    private final int bufferSize;

    public InputStreamContentProvider(InputStream stream)
    {
        this(stream, 4096);
    }

    public InputStreamContentProvider(InputStream stream, int bufferSize)
    {
        this.stream = stream;
        this.bufferSize = bufferSize;
    }

    @Override
    public long getLength()
    {
        return -1;
    }

    protected ByteBuffer onRead(byte[] buffer, int offset, int length)
    {
        if (length <= 0)
            return BufferUtil.EMPTY_BUFFER;
        return ByteBuffer.wrap(buffer, offset, length);
    }

    @Override
    public Iterator<ByteBuffer> iterator()
    {
        return new Iterator<ByteBuffer>()
        {
            private final byte[] bytes = new byte[bufferSize];
            private Exception failure;
            private ByteBuffer buffer;

            @Override
            public boolean hasNext()
            {
                try
                {
                    int read = stream.read(bytes);
                    if (read > 0)
                    {
                        buffer = onRead(bytes, 0, read);
                        return true;
                    }
                    else if (read < 0)
                    {
                        return false;
                    }
                    else
                    {
                        buffer = BufferUtil.EMPTY_BUFFER;
                        return true;
                    }
                }
                catch (Exception x)
                {
                    if (failure == null)
                    {
                        failure = x;
                        return true;
                    }
                    return false;
                }
            }

            @Override
            public ByteBuffer next()
            {
                ByteBuffer result = buffer;
                buffer = null;
                if (failure != null)
                    throw (NoSuchElementException)new NoSuchElementException().initCause(failure);
                if (result == null)
                    throw new NoSuchElementException();
                return result;
            }

            @Override
            public void remove()
            {
                throw new UnsupportedOperationException();
            }
        };
    }
}
