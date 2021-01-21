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

package org.eclipse.jetty.client;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Iterator;

import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * {@link HttpContent} is a stateful, linear representation of the request content provided
 * by a {@link ContentProvider} that can be traversed one-way to obtain content buffers to
 * send to an HTTP server.
 * <p>
 * {@link HttpContent} offers the notion of a one-way cursor to traverse the content.
 * The cursor starts in a virtual "before" position and can be advanced using {@link #advance()}
 * until it reaches a virtual "after" position where the content is fully consumed.
 * <pre>
 *      +---+  +---+  +---+  +---+  +---+
 *      |   |  |   |  |   |  |   |  |   |
 *      +---+  +---+  +---+  +---+  +---+
 *   ^           ^                    ^    ^
 *   |           | --&gt; advance()      |    |
 *   |           |                  last   |
 *   |           |                         |
 * before        |                        after
 *               |
 *            current
 * </pre>
 * At each valid (non-before and non-after) cursor position, {@link HttpContent} provides the following state:
 * <ul>
 * <li>the buffer containing the content to send, via {@link #getByteBuffer()}</li>
 * <li>a copy of the content buffer that can be used for notifications, via {@link #getContent()}</li>
 * <li>whether the buffer to write is the last one, via {@link #isLast()}</li>
 * </ul>
 * {@link HttpContent} may not have content, if the related {@link ContentProvider} is {@code null}, and this
 * is reflected by {@link #hasContent()}.
 * <p>
 * {@link HttpContent} may have {@link AsyncContentProvider deferred content}, in which case {@link #advance()}
 * moves the cursor to a position that provides {@code null} {@link #getByteBuffer() buffer} and
 * {@link #getContent() content}. When the deferred content is available, a further call to {@link #advance()}
 * will move the cursor to a position that provides non {@code null} buffer and content.
 */
public class HttpContent implements Callback, Closeable
{
    private static final Logger LOG = Log.getLogger(HttpContent.class);
    private static final ByteBuffer AFTER = ByteBuffer.allocate(0);
    private static final ByteBuffer CLOSE = ByteBuffer.allocate(0);

    private final ContentProvider provider;
    private final Iterator<ByteBuffer> iterator;
    private ByteBuffer buffer;
    private ByteBuffer content;
    private boolean last;

    public HttpContent(ContentProvider provider)
    {
        this.provider = provider;
        this.iterator = provider == null ? Collections.emptyIterator() : provider.iterator();
    }

    /**
     * @return true if the buffer is the sentinel instance {@link CLOSE}
     */
    private static boolean isTheCloseBuffer(ByteBuffer buffer)
    {
        @SuppressWarnings("ReferenceEquality")
        boolean isTheCloseBuffer = (buffer == CLOSE);
        return isTheCloseBuffer;
    }

    /**
     * @return whether there is any content at all
     */
    public boolean hasContent()
    {
        return provider != null;
    }

    /**
     * @return whether the cursor points to the last content
     */
    public boolean isLast()
    {
        return last;
    }

    /**
     * @return the {@link ByteBuffer} containing the content at the cursor's position
     */
    public ByteBuffer getByteBuffer()
    {
        return buffer;
    }

    /**
     * @return a {@link ByteBuffer#slice()} of {@link #getByteBuffer()} at the cursor's position
     */
    public ByteBuffer getContent()
    {
        return content;
    }

    /**
     * Advances the cursor to the next block of content.
     * <p>
     * The next block of content may be valid (which yields a non-null buffer
     * returned by {@link #getByteBuffer()}), but may also be deferred
     * (which yields a null buffer returned by {@link #getByteBuffer()}).
     * <p>
     * If the block of content pointed by the new cursor position is valid, this method returns true.
     *
     * @return true if there is content at the new cursor's position, false otherwise.
     */
    public boolean advance()
    {
        if (iterator instanceof Synchronizable)
        {
            synchronized (((Synchronizable)iterator).getLock())
            {
                return advance(iterator);
            }
        }
        else
        {
            return advance(iterator);
        }
    }

    private boolean advance(Iterator<ByteBuffer> iterator)
    {
        boolean hasNext = iterator.hasNext();
        ByteBuffer bytes = hasNext ? iterator.next() : null;
        boolean hasMore = hasNext && iterator.hasNext();
        boolean wasLast = last;
        last = !hasMore;

        if (hasNext)
        {
            buffer = bytes;
            content = bytes == null ? null : bytes.slice();
            if (LOG.isDebugEnabled())
                LOG.debug("Advanced content to {} chunk {}", hasMore ? "next" : "last", String.valueOf(bytes));
            return bytes != null;
        }
        else
        {
            // No more content, but distinguish between last and consumed.
            if (wasLast)
            {
                buffer = content = AFTER;
                if (LOG.isDebugEnabled())
                    LOG.debug("Advanced content past last chunk");
            }
            else
            {
                buffer = content = CLOSE;
                if (LOG.isDebugEnabled())
                    LOG.debug("Advanced content to last chunk");
            }
            return false;
        }
    }

    /**
     * @return whether the cursor has been advanced past the {@link #isLast() last} position.
     */
    @SuppressWarnings("ReferenceEquality")
    public boolean isConsumed()
    {
        return buffer == AFTER;
    }

    @Override
    public void succeeded()
    {
        if (isConsumed())
            return;
        if (isTheCloseBuffer(buffer))
            return;
        if (iterator instanceof Callback)
            ((Callback)iterator).succeeded();
    }

    @Override
    public void failed(Throwable x)
    {
        if (isConsumed())
            return;
        if (isTheCloseBuffer(buffer))
            return;
        if (iterator instanceof Callback)
            ((Callback)iterator).failed(x);
    }

    @Override
    public void close()
    {
        if (iterator instanceof Closeable)
            IO.close((Closeable)iterator);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x - has=%b,last=%b,consumed=%b,buffer=%s",
            getClass().getSimpleName(),
            hashCode(),
            hasContent(),
            isLast(),
            isConsumed(),
            BufferUtil.toDetailString(getContent()));
    }
}
