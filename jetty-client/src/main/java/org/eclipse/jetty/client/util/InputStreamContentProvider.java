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

import org.eclipse.jetty.client.api.ContentProvider;

public class InputStreamContentProvider implements ContentProvider
{
    private final InputStream input;
    private final long length;
    private final int capacity;

    public InputStreamContentProvider(InputStream input)
    {
        this(input, -1);
    }

    public InputStreamContentProvider(InputStream input, long length)
    {
        this(input, length, length <= 0 ? 4096 : (int)Math.min(4096, length));
    }

    public InputStreamContentProvider(InputStream input, long length, int capacity)
    {
        this.input = input;
        this.length = length;
        this.capacity = capacity;
    }

    @Override
    public long length()
    {
        return length;
    }

    @Override
    public Iterator<ByteBuffer> iterator()
    {
        return null; // TODO
    }

    private class LazyIterator implements Iterator<ByteBuffer>
    {
        @Override
        public boolean hasNext()
        {
            return false;
        }

        @Override
        public ByteBuffer next()
        {
            return null;
        }

        @Override
        public void remove()
        {
            throw new UnsupportedOperationException();
        }
    }
}
