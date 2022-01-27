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
 * A {@link ContentProvider} for byte arrays.
 *
 * @deprecated use {@link BytesRequestContent} instead.
 */
@Deprecated
public class BytesContentProvider extends AbstractTypedContentProvider
{
    private final byte[][] bytes;
    private final long length;

    public BytesContentProvider(byte[]... bytes)
    {
        this("application/octet-stream", bytes);
    }

    public BytesContentProvider(String contentType, byte[]... bytes)
    {
        super(contentType);
        this.bytes = bytes;
        long length = 0;
        for (byte[] buffer : bytes)
        {
            length += buffer.length;
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
                return index < bytes.length;
            }

            @Override
            public ByteBuffer next()
            {
                try
                {
                    return ByteBuffer.wrap(bytes[index++]);
                }
                catch (ArrayIndexOutOfBoundsException x)
                {
                    throw new NoSuchElementException();
                }
            }
        };
    }
}
