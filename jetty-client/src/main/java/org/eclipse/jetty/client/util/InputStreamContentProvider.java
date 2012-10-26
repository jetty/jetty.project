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

import java.io.IOException;
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
    public long length()
    {
        return -1;
    }

    @Override
    public Iterator<ByteBuffer> iterator()
    {
        return new Iterator<ByteBuffer>()
        {
            private final byte[] buffer = new byte[bufferSize];
            public boolean eof;

            @Override
            public boolean hasNext()
            {
                return !eof;
            }

            @Override
            public ByteBuffer next()
            {
                try
                {
                    int read = stream.read(buffer);
                    if (read > 0)
                    {
                        return ByteBuffer.wrap(buffer, 0, read);
                    }
                    else if (read < 0)
                    {
                        if (eof)
                            throw new NoSuchElementException();
                        eof = true;
                        return BufferUtil.EMPTY_BUFFER;
                    }
                    else
                    {
                        return BufferUtil.EMPTY_BUFFER;
                    }
                }
                catch (IOException x)
                {
                    throw (NoSuchElementException)new NoSuchElementException().initCause(x);
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
