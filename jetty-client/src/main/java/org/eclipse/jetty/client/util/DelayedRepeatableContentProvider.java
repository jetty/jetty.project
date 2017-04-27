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

import org.eclipse.jetty.client.api.ContentProvider;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class DelayedRepeatableContentProvider
        implements ContentProvider
{
    private final byte[] content;
    private final int count;
    private final int delayInMillis;

    public DelayedRepeatableContentProvider(int chunkSize, int count, int delayInMillis)
    {
        this(new byte[chunkSize], count, delayInMillis);
    }

    public DelayedRepeatableContentProvider(byte[] content, int count, int delayInMillis)
    {
        this.content = content;
        this.count = count;
        this.delayInMillis = delayInMillis;
    }

    @Override
    public long getLength()
    {
        return content.length * count;
    }

    @Override
    public Iterator<ByteBuffer> iterator()
    {
        return new Iterator<ByteBuffer>()
        {
            private int chunksLeft = count;

            @Override
            public boolean hasNext()
            {
                return chunksLeft > 0;
            }

            @Override
            public ByteBuffer next()
            {
                if (chunksLeft > 0) {
                    try {
                        Thread.sleep(delayInMillis);
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    chunksLeft--;
                    return ByteBuffer.wrap(content);
                }
                throw new NoSuchElementException();
            }
        };
    }
}
