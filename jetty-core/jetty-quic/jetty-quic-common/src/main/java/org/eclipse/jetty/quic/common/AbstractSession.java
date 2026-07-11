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

import java.nio.channels.ClosedChannelException;
import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.api.frames.DataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.MaxDataFrame;
import org.eclipse.jetty.quic.api.frames.MaxStreamsFrame;
import org.eclipse.jetty.quic.api.frames.StreamsBlockedFrame;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractSession extends ContainerLifeCycle implements Session
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractSession.class);

    private final Executor executor;
    private final QuicConfiguration configuration;
    private final Session.Listener listener;

    protected AbstractSession(Executor executor, QuicConfiguration configuration, Session.Listener listener)
    {
        this.executor = executor;
        installBean(executor);
        this.configuration = configuration;
        installBean(configuration);
        this.listener = listener;
        installBean(listener);
    }

    public Executor getExecutor()
    {
        return executor;
    }

    public QuicConfiguration getQuicConfiguration()
    {
        return configuration;
    }

    public Session.Listener getListener()
    {
        return listener;
    }

    public abstract X509Certificate[] getPeerCertificates();

    protected void emitOpen()
    {
        notifyOpen();
        configuration.getEventListeners().stream()
            .filter(l -> l instanceof Session.Listener)
            .map(Session.Listener.class::cast)
            .forEach(this::notifyOpen);
    }

    protected void emitDisconnect(ConnectionCloseFrame frame)
    {
        notifyDisconnect(frame);
        configuration.getEventListeners().stream()
            .filter(l -> l instanceof Session.Listener)
            .map(Session.Listener.class::cast)
            .forEach(l -> notifyDisconnect(l, frame));
    }

    public abstract void offerTask(Runnable task, boolean dispatch);

    public CompletableFuture<Session> shutdown()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("shutdown {}", this);
        return notifyLocalShutdown();
    }

    @Override
    public void close(long appError, String reason, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("closing {}/{} {}", appError, reason, this);

        // Propagate upwards.
        notifyLocalClose(appError, reason, Callback.from(callback.getInvocationType(), _ ->
            disconnect(appError, reason, new ClosedChannelException(), callback)));
    }

    public void notifyCreate()
    {
        try
        {
            listener.onCreated(this);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure while notifying listener {}", listener, x);
        }
    }

    protected void notifyOpen()
    {
        notifyOpen(listener);
    }

    private void notifyOpen(Session.Listener listener)
    {
        try
        {
            listener.onOpen(this);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure while notifying listener {}", listener, x);
        }
    }

    protected Stream.Listener notifyNewStream(Frame.WithStreamId frame)
    {
        try
        {
            return listener.onNewStream(this, frame);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure while notifying listener {}", listener, x);
            return null;
        }
    }

    public void notifyPrepare(TransportParameters transportParameters)
    {
        try
        {
            listener.onPrepare(this, transportParameters);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure while notifying listener {}", listener, x);
        }
    }

    protected void notifyTransportParameters(TransportParameters parameters)
    {
        try
        {
            listener.onTransportParameters(this, parameters);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure while notifying listener {}", listener, x);
        }
    }

    protected void notifyMaxStreams(MaxStreamsFrame frame)
    {
        try
        {
            listener.onMaxStreams(this, frame);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure while notifying listener {}", listener, x);
        }
    }

    protected void notifyDataBlocked(DataBlockedFrame frame)
    {
        try
        {
            listener.onDataBlocked(this, frame);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure while notifying listener {}", listener, x);
        }
    }

    protected void notifyMaxData(MaxDataFrame frame)
    {
        try
        {
            listener.onMaxData(this, frame);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure while notifying listener {}", listener, x);
        }
    }

    protected void notifyPing()
    {
        try
        {
            listener.onPing(this);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure while notifying listener {}", listener, x);
        }
    }

    protected void notifyStreamsBlocked(StreamsBlockedFrame frame)
    {
        try
        {
            listener.onStreamsBlocked(this, frame);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure while notifying listener {}", listener, x);
        }
    }

    private CompletableFuture<Session> notifyLocalShutdown()
    {
        try
        {
            if (listener instanceof AbstractSession.Listener extended)
                return extended.onLocalShutdown(this);
            return CompletableFuture.completedFuture(this);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure while notifying listener {}", listener, x);
            return CompletableFuture.failedFuture(x);
        }
    }

    protected void notifyLocalClose(long appError, String reason, Callback callback)
    {
        try
        {
            if (listener instanceof AbstractSession.Listener extended)
                extended.onLocalClose(this, appError, reason, callback);
            else
                callback.succeeded();
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure while notifying listener {}", listener, x);
            callback.failed(x);
        }
    }

    protected void notifyConnectionClose(ConnectionCloseFrame frame)
    {
        try
        {
            listener.onClose(this, frame);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure while notifying listener {}", listener, x);
        }
    }

    protected void notifyFailure(Throwable failure)
    {
        try
        {
            listener.onFailure(this, failure);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure while notifying listener {}", listener, x);
        }
    }

    protected void notifyDisconnect(ConnectionCloseFrame frame)
    {
        notifyDisconnect(listener, frame);
    }

    private void notifyDisconnect(Session.Listener listener, ConnectionCloseFrame frame)
    {
        try
        {
            listener.onDisconnect(this, frame);
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
        return "%s@%x".formatted(TypeUtil.toShortName(getClass()), hashCode());
    }

    public interface Listener extends Session.Listener
    {
        CompletableFuture<Session> onLocalShutdown(Session session);

        void onLocalClose(Session session, long appError, String reason, Callback callback);
    }
}
