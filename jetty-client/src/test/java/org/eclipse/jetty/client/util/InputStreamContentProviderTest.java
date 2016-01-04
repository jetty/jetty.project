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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Assert;
import org.junit.Test;

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

        Assert.assertNotNull(iterator);
        Assert.assertFalse(iterator.hasNext());

        try
        {
            iterator.next();
            Assert.fail();
        }
        catch (NoSuchElementException expected)
        {
        }

        Assert.assertFalse(iterator.hasNext());
        Assert.assertTrue(closed.get());
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

        Assert.assertNotNull(iterator);

        ByteBuffer buffer = iterator.next();

        Assert.assertNotNull(buffer);

        try
        {
            iterator.next();
            Assert.fail();
        }
        catch (NoSuchElementException expected)
        {
        }

        Assert.assertFalse(iterator.hasNext());
        Assert.assertTrue(closed.get());
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

        Assert.assertNotNull(iterator);

        try
        {
            iterator.next();
            Assert.fail();
        }
        catch (NoSuchElementException expected)
        {
        }

        Assert.assertFalse(iterator.hasNext());
        Assert.assertTrue(closed.get());
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

        Assert.assertNotNull(iterator);
        Assert.assertTrue(iterator.hasNext());

        try
        {
            iterator.next();
            Assert.fail();
        }
        catch (NoSuchElementException expected)
        {
        }

        Assert.assertFalse(iterator.hasNext());
        Assert.assertTrue(closed.get());
    }
}
