//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.common;

import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractStream implements Stream, CyclicTimeouts.Expirable
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractStream.class);

    private final long streamId;
    private final boolean local;
    private Stream.Listener listener;
    private long idleTimeout;
    private long expireNanoTime = Long.MAX_VALUE;

    protected AbstractStream(long streamId, boolean local)
    {
        this.streamId = streamId;
        this.local = local;
    }

    @Override
    public long getId()
    {
        return streamId;
    }

    @Override
    public boolean isBidirectional()
    {
        return StreamId.isBidirectional(getId());
    }

    @Override
    public boolean isLocal()
    {
        return local;
    }

    public Stream.Listener getListener()
    {
        return listener;
    }

    public void setListener(Stream.Listener listener)
    {
        this.listener = listener;
    }

    public long getIdleTimeout()
    {
        return idleTimeout;
    }

    @Override
    public void setIdleTimeout(long idleTimeout)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("setting idle timeout {} ms for {}", idleTimeout, this);
        this.idleTimeout = idleTimeout;
        notIdle();
    }

    protected void notIdle()
    {
        expireNanoTime = CyclicTimeouts.Expirable.calcExpireNanoTime(getIdleTimeout());
    }

    @Override
    public long getExpireNanoTime()
    {
        return expireNanoTime;
    }

    protected void notifyClose()
    {
        Stream.Listener listener = getListener();
        try
        {
            if (listener != null)
                listener.onClose(this);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure while notifying listener {}", listener, x);
        }
    }

    @Override
    public String toString()
    {
        return "%s@%x#%d".formatted(TypeUtil.toShortName(getClass()), hashCode(), getId());
    }
}
