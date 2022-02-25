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

package org.eclipse.jetty.client.internal;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.Iterator;

import org.eclipse.jetty.client.AsyncContentProvider;
import org.eclipse.jetty.client.Synchronizable;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Implements the conversion from {@link ContentProvider} to {@link Request.Content}.</p>
 */
public class RequestContentAdapter implements Request.Content, Request.Content.Subscription, AsyncContentProvider.Listener, Callback
{
    private static final Logger LOG = LoggerFactory.getLogger(RequestContentAdapter.class);

    private final AutoLock lock = new AutoLock();
    private final ContentProvider provider;
    private Iterator<ByteBuffer> iterator;
    private Consumer consumer;
    private boolean emitInitialContent;
    private boolean lastContent;
    private boolean committed;
    private int demand;
    private boolean stalled;
    private boolean hasContent;
    private Throwable failure;

    public RequestContentAdapter(ContentProvider provider)
    {
        this.provider = provider;
        if (provider instanceof AsyncContentProvider)
            ((AsyncContentProvider)provider).setListener(this);
    }

    public ContentProvider getContentProvider()
    {
        return provider;
    }

    @Override
    public String getContentType()
    {
        return provider instanceof ContentProvider.Typed ? ((ContentProvider.Typed)provider).getContentType() : null;
    }

    @Override
    public long getLength()
    {
        return provider.getLength();
    }

    @Override
    public boolean isReproducible()
    {
        return provider.isReproducible();
    }

    @Override
    public Subscription subscribe(Consumer consumer, boolean emitInitialContent)
    {
        try (AutoLock ignored = lock.lock())
        {
            if (this.consumer != null && !isReproducible())
                throw new IllegalStateException("Multiple subscriptions not supported on " + this);
            this.iterator = provider.iterator();
            this.consumer = consumer;
            this.emitInitialContent = emitInitialContent;
            this.lastContent = false;
            this.committed = false;
            this.demand = 0;
            this.stalled = true;
            this.hasContent = false;
        }
        return this;
    }

    @Override
    public void demand()
    {
        boolean produce;
        try (AutoLock ignored = lock.lock())
        {
            ++demand;
            produce = stalled;
            if (stalled)
                stalled = false;
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Content demand, producing {} for {}", produce, this);
        if (produce)
            produce();
    }

    @Override
    public void fail(Throwable failure)
    {
        try (AutoLock ignored = lock.lock())
        {
            if (this.failure == null)
                this.failure = failure;
        }
        failed(failure);
    }

    @Override
    public void onContent()
    {
        boolean produce = false;
        try (AutoLock ignored = lock.lock())
        {
            hasContent = true;
            if (demand > 0)
            {
                produce = stalled;
                if (stalled)
                    stalled = false;
            }
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Content event, processing {} for {}", produce, this);
        if (produce)
            produce();
    }

    @Override
    public void succeeded()
    {
        if (iterator instanceof Callback)
            ((Callback)iterator).succeeded();
        if (lastContent && iterator instanceof Closeable)
            IO.close((Closeable)iterator);
    }

    @Override
    public void failed(Throwable x)
    {
        if (iterator == null)
            failed(provider, x);
        else
            failed(iterator, x);
    }

    private void failed(Object object, Throwable failure)
    {
        if (object instanceof Callback)
            ((Callback)object).failed(failure);
        if (object instanceof Closeable)
            IO.close((Closeable)object);
    }

    @Override
    public InvocationType getInvocationType()
    {
        return InvocationType.NON_BLOCKING;
    }

    private void produce()
    {
        while (true)
        {
            Throwable failure;
            try (AutoLock ignored = lock.lock())
            {
                failure = this.failure;
            }
            if (failure != null)
            {
                notifyFailure(failure);
                return;
            }

            if (committed)
            {
                ByteBuffer content = advance();
                if (content != null)
                {
                    notifyContent(content, lastContent);
                }
                else
                {
                    try (AutoLock ignored = lock.lock())
                    {
                        // Call to advance() said there was no content,
                        // but some content may have arrived meanwhile.
                        if (hasContent)
                        {
                            hasContent = false;
                            continue;
                        }
                        else
                        {
                            stalled = true;
                        }
                    }
                    if (LOG.isDebugEnabled())
                        LOG.debug("No content, processing stalled for {}", this);
                    return;
                }
            }
            else
            {
                committed = true;
                if (emitInitialContent)
                {
                    ByteBuffer content = advance();
                    if (content != null)
                        notifyContent(content, lastContent);
                    else
                        notifyContent(BufferUtil.EMPTY_BUFFER, false);
                }
                else
                {
                    notifyContent(BufferUtil.EMPTY_BUFFER, false);
                }
            }
            boolean noDemand;
            try (AutoLock ignored = lock.lock())
            {
                noDemand = demand == 0;
                if (noDemand)
                    stalled = true;
            }
            if (noDemand)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("No demand, processing stalled for {}", this);
                return;
            }
        }
    }

    private ByteBuffer advance()
    {
        if (iterator instanceof Synchronizable)
        {
            synchronized (((Synchronizable)iterator).getLock())
            {
                return next();
            }
        }
        else
        {
            return next();
        }
    }

    private ByteBuffer next()
    {
        boolean hasNext = iterator.hasNext();
        ByteBuffer bytes = hasNext ? iterator.next() : null;
        boolean hasMore = hasNext && iterator.hasNext();
        lastContent = !hasMore;
        return hasNext ? bytes : BufferUtil.EMPTY_BUFFER;
    }

    private void notifyContent(ByteBuffer buffer, boolean last)
    {
        try (AutoLock ignored = lock.lock())
        {
            --demand;
            hasContent = false;
        }

        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Notifying content last={} {} for {}", last, BufferUtil.toDetailString(buffer), this);
            consumer.onContent(buffer, last, this);
        }
        catch (Throwable x)
        {
            fail(x);
        }
    }

    private void notifyFailure(Throwable failure)
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Notifying failure for {}", this, failure);
            consumer.onFailure(failure);
        }
        catch (Exception x)
        {
            LOG.trace("Failure while notifying content failure {}", failure, x);
        }
    }

    @Override
    public String toString()
    {
        int demand;
        boolean stalled;
        try (AutoLock ignored = lock.lock())
        {
            demand = this.demand;
            stalled = this.stalled;
        }
        return String.format("%s@%x[demand=%d,stalled=%b]", getClass().getSimpleName(), hashCode(), demand, stalled);
    }
}
