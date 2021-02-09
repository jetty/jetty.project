//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InputStreamContentProviderTest
{
    @Test
    public void testHasNextFalseThenNext()
    {
        final AtomicBoolean closed = new AtomicBoolean();
        InputStream stream = new InputStream()
        {
            @Override
            public int read() throws IOException
            {
                return -1;
            }

            @Override
            public void close() throws IOException
            {
                super.close();
                closed.compareAndSet(false, true);
            }
        };

        InputStreamContentProvider provider = new InputStreamContentProvider(stream);
        Iterator<ByteBuffer> iterator = provider.iterator();

        assertNotNull(iterator);
        assertFalse(iterator.hasNext());

        assertThrows(NoSuchElementException.class, () -> iterator.next());

        assertFalse(iterator.hasNext());
        assertTrue(closed.get());
    }

    @Test
    public void testStreamWithContentThenNextThenNext()
    {
        final AtomicBoolean closed = new AtomicBoolean();
        ByteArrayInputStream stream = new ByteArrayInputStream(new byte[]{1})
        {
            @Override
            public void close() throws IOException
            {
                super.close();
                closed.compareAndSet(false, true);
            }
        };

        InputStreamContentProvider provider = new InputStreamContentProvider(stream);
        Iterator<ByteBuffer> iterator = provider.iterator();

        assertNotNull(iterator);

        ByteBuffer buffer = iterator.next();

        assertNotNull(buffer);

        assertThrows(NoSuchElementException.class, () -> iterator.next());

        assertFalse(iterator.hasNext());
        assertTrue(closed.get());
    }

    @Test
    public void testStreamWithExceptionThenNext()
    {
        final AtomicBoolean closed = new AtomicBoolean();
        InputStream stream = new InputStream()
        {
            @Override
            public int read() throws IOException
            {
                throw new IOException();
            }

            @Override
            public void close() throws IOException
            {
                super.close();
                closed.compareAndSet(false, true);
            }
        };

        InputStreamContentProvider provider = new InputStreamContentProvider(stream);
        Iterator<ByteBuffer> iterator = provider.iterator();

        assertNotNull(iterator);

        assertThrows(NoSuchElementException.class, () -> iterator.next());

        assertFalse(iterator.hasNext());
        assertTrue(closed.get());
    }

    @Test
    public void testHasNextWithExceptionThenNext()
    {
        final AtomicBoolean closed = new AtomicBoolean();
        InputStream stream = new InputStream()
        {
            @Override
            public int read() throws IOException
            {
                throw new IOException();
            }

            @Override
            public void close() throws IOException
            {
                super.close();
                closed.compareAndSet(false, true);
            }
        };

        InputStreamContentProvider provider = new InputStreamContentProvider(stream);
        Iterator<ByteBuffer> iterator = provider.iterator();

        assertNotNull(iterator);
        assertTrue(iterator.hasNext());

        assertThrows(NoSuchElementException.class, () -> iterator.next());

        assertFalse(iterator.hasNext());
        assertTrue(closed.get());
    }
}
