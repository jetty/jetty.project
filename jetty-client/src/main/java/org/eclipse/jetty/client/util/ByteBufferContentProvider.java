//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.eclipse.jetty.client.api.ContentProvider;

/**
 * A {@link ContentProvider} for {@link ByteBuffer}s.
 * <p>
 * The position and limit of the {@link ByteBuffer}s passed to the constructor are not modified,
 * and each invocation of the {@link #iterator()} method returns a {@link ByteBuffer#slice() slice}
 * of the original {@link ByteBuffer}.
 */
public class ByteBufferContentProvider extends AbstractTypedContentProvider
{
    private final ByteBuffer[] buffers;
    private final int length;

    public ByteBufferContentProvider(ByteBuffer... buffers)
    {
        this("application/octet-stream", buffers);
    }

    public ByteBufferContentProvider(String contentType, ByteBuffer... buffers)
    {
        super(contentType);
        this.buffers = buffers;
        int length = 0;
        for (ByteBuffer buffer : buffers)
            length += buffer.remaining();
        this.length = length;
    }

    @Override
    public long getLength()
    {
        return length;
    }

    @Override
    public Iterator<ByteBuffer> iterator()
    {
        return new Iterator<ByteBuffer>()
        {
            private int index;

            @Override
            public boolean hasNext()
            {
                return index < buffers.length;
            }

            @Override
            public ByteBuffer next()
            {
                try
                {
                    ByteBuffer buffer = buffers[index];
                    buffers[index] = buffer.slice();
                    ++index;
                    return buffer;
                }
                catch (ArrayIndexOutOfBoundsException x)
                {
                    throw new NoSuchElementException();
                }
            }

            @Override
            public void remove()
            {
                throw new UnsupportedOperationException();
            }
        };
    }
}
