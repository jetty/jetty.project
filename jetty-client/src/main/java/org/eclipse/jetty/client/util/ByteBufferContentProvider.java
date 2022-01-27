//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
 *
 * @deprecated use {@link ByteBufferRequestContent} instead.
 */
@Deprecated
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
        {
            length += buffer.remaining();
        }
        this.length = length;
    }

    @Override
    public long getLength()
    {
        return length;
    }

    @Override
    public boolean isReproducible()
    {
        return true;
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
        };
    }
}
