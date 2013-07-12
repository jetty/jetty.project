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

package org.eclipse.jetty.client;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Iterator;

import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

public abstract class HttpContent implements Callback
{
    private final ContentProvider provider;
    private final Iterator<ByteBuffer> iterator;
    private ByteBuffer buffer;
    private volatile ByteBuffer content;

    public HttpContent(ContentProvider provider)
    {
        this(provider, provider == null ? Collections.<ByteBuffer>emptyIterator() : provider.iterator());
    }

    public HttpContent(HttpContent that)
    {
        this(that.provider, that.iterator);
        this.buffer = that.buffer;
        this.content = that.content;
    }

    private HttpContent(ContentProvider provider, Iterator<ByteBuffer> iterator)
    {
        this.provider = provider;
        this.iterator = iterator;
    }

    public boolean hasContent()
    {
        return provider != null;
    }

    public boolean isLast()
    {
        return !iterator.hasNext();
    }

    public ByteBuffer getByteBuffer()
    {
        return buffer;
    }

    public ByteBuffer getContent()
    {
        return content;
    }

    public boolean advance()
    {
        if (isLast())
        {
            if (content != null)
                content = buffer = BufferUtil.EMPTY_BUFFER;
            return false;
        }
        else
        {
            ByteBuffer buffer = this.buffer = iterator.next();
            content = buffer == null ? null : buffer.slice();
            return buffer != null;
        }
    }
}
