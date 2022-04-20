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

package org.eclipse.jetty.ee9.nested;

import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Blocking implementation of {@link ContentProducer}. Calling {@link ContentProducer#nextContent()} will block when
 * there is no available content but will never return null.
 */
class BlockingContentProducer implements ContentProducer
{
    private static final Logger LOG = LoggerFactory.getLogger(BlockingContentProducer.class);

    private final AsyncContentProducer _asyncContentProducer;
    private final AsyncContentProducer.LockedSemaphore _semaphore;

    BlockingContentProducer(AsyncContentProducer delegate)
    {
        _asyncContentProducer = delegate;
        _semaphore = _asyncContentProducer.newLockedSemaphore();
    }

    @Override
    public AutoLock lock()
    {
        return _asyncContentProducer.lock();
    }

    @Override
    public void recycle()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("recycling {}", this);
        _asyncContentProducer.recycle();
    }

    @Override
    public void reopen()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("reopening {}", this);
        _asyncContentProducer.reopen();
        _semaphore.drainPermits();
    }

    @Override
    public int available()
    {
        return _asyncContentProducer.available();
    }

    @Override
    public boolean hasContent()
    {
        return _asyncContentProducer.hasContent();
    }

    @Override
    public boolean isError()
    {
        return _asyncContentProducer.isError();
    }

    @Override
    public void checkMinDataRate()
    {
        _asyncContentProducer.checkMinDataRate();
    }

    @Override
    public long getRawContentArrived()
    {
        return _asyncContentProducer.getRawContentArrived();
    }

    @Override
    public boolean consumeAll()
    {
        boolean eof = _asyncContentProducer.consumeAll();
        _semaphore.release();
        return eof;
    }

    @Override
    public boolean releaseContent()
    {
        boolean eof = _asyncContentProducer.releaseContent();
        _semaphore.release();
        return eof;
    }

    @Override
    public Content nextContent()
    {
        while (true)
        {
            Content content = _asyncContentProducer.nextContent();
            if (LOG.isDebugEnabled())
                LOG.debug("nextContent async producer returned {}", content);
            if (content != null)
                return content;

            // IFF isReady() returns false then HttpChannel.needContent() has been called,
            // thus we know that eventually a call to onContentProducible will come.
            if (_asyncContentProducer.isReady())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("nextContent async producer is ready, retrying");
                continue;
            }
            if (LOG.isDebugEnabled())
                LOG.debug("nextContent async producer is not ready, waiting on semaphore {}", _semaphore);

            try
            {
                _semaphore.acquire();
            }
            catch (InterruptedException e)
            {
                return new Content.Error(e);
            }
        }
    }

    @Override
    public void reclaim(Content content)
    {
        _asyncContentProducer.reclaim(content);
    }

    @Override
    public boolean isReady()
    {
        boolean ready = available() > 0;
        if (LOG.isDebugEnabled())
            LOG.debug("isReady = {}", ready);
        return ready;
    }

    @Override
    public HttpInput.Interceptor getInterceptor()
    {
        return _asyncContentProducer.getInterceptor();
    }

    @Override
    public void setInterceptor(HttpInput.Interceptor interceptor)
    {
        _asyncContentProducer.setInterceptor(interceptor);
    }

    @Override
    public boolean onContentProducible()
    {
        _semaphore.assertLocked();
        // In blocking mode, the dispatched thread normally does not have to be rescheduled as it is normally in state
        // DISPATCHED blocked on the semaphore that just needs to be released for the dispatched thread to resume.
        // This is why this method always returns false.
        // But async errors can occur while the dispatched thread is NOT blocked reading (i.e.: in state WAITING),
        // so the WAITING to WOKEN transition must be done by the error-notifying thread which then has to reschedule
        // the dispatched thread after HttpChannelState.asyncError() is called.
        // Calling _asyncContentProducer.onContentProducible() changes the channel state from WAITING to WOKEN which
        // would prevent the subsequent call to HttpChannelState.asyncError() from rescheduling the thread.
        // AsyncServletTest.testStartAsyncThenClientStreamIdleTimeout() tests this.
        boolean unready = _asyncContentProducer.isUnready();
        if (LOG.isDebugEnabled())
            LOG.debug("onContentProducible releasing semaphore {} unready={}", _semaphore, unready);
        // Do not release the semaphore if we are not unready, as certain protocols may call this method
        // just after having received the request, not only when they have read all the available content.
        if (unready)
            _semaphore.release();
        return false;
    }
}
