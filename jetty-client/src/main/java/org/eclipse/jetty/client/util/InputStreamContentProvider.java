//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

/**
 * A {@link ContentProvider} for an {@link InputStream}.
 * <p />
 * The input stream is read once and therefore fully consumed.
 * Invocations to the {@link #iterator()} method after the first will return an "empty" iterator
 * because the stream has been consumed on the first invocation.
 * <p />
 * It is possible to specify, at the constructor, a buffer size used to read content from the
 * stream, by default 4096 bytes.
 * <p />
 * However, it is possible for subclasses to override {@link #onRead(byte[], int, int)} to copy
 * the content read from the stream to another location (for example a file), and be able to
 * support multiple invocations of {@link #iterator()}, returning the iterator provided by this
 * class on the first invocation, and an iterator on the bytes copied to the other location
 * for subsequent invocations.
 */
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

    /**
     * Callback method invoked just after having read from the stream,
     * but before returning the iteration element (a {@link ByteBuffer}
     * to the caller.
     * <p />
     * Subclasses may override this method to copy the content read from
     * the stream to another location (a file, or in memory if the content
     * is known to fit).
     *
     * @param buffer the byte array containing the bytes read
     * @param offset the offset from where bytes should be read
     * @param length the length of the bytes read
     * @return a {@link ByteBuffer} wrapping the byte array
     */
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
