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

package org.eclipse.jetty.websocket.common;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ThreadClassLoaderScope;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.CloseStatus;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.io.DisconnectCallback;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.eclipse.jetty.websocket.common.scopes.WebSocketSessionScope;

@ManagedObject("A Jetty WebSocket Session")
public class WebSocketSession extends ContainerLifeCycle implements Session, RemoteEndpointFactory, WebSocketSessionScope, IncomingFrames, OutgoingFrames, Connection.Listener
{
    private static final Logger LOG = Log.getLogger(WebSocketSession.class);
    private static final RemoteEndpointFactory defaultRemoteEndpointFactory;
    private final WebSocketContainerScope containerScope;
    private final URI requestURI;
    private final LogicalConnection connection;
    private final EventDriver websocket;
    private final Executor executor;
    private final WebSocketPolicy policy;
    private final AtomicBoolean onCloseCalled = new AtomicBoolean(false);
    private final RemoteEndpointFactory remoteEndpointFactory;
    private final ClassLoader classLoader;
    private ExtensionFactory extensionFactory;
    private String protocolVersion;
    private final Map<String, String[]> parameterMap = new HashMap<>();
    private RemoteEndpoint remote;
    private final IncomingFrames incomingHandler;
    private OutgoingFrames outgoingHandler;
    private UpgradeRequest upgradeRequest;
    private UpgradeResponse upgradeResponse;
    private CompletableFuture<Session> openFuture;

    static
    {
        // Attempt to discover a RemoteEndpointFactory with the SerivceLoader.
        Iterator<RemoteEndpointFactory> iter = ServiceLoader.load(RemoteEndpointFactory.class).iterator();
        defaultRemoteEndpointFactory = iter.hasNext() ? iter.next() : null;
        if (LOG.isDebugEnabled())
            LOG.debug("Discovered default RemoteEndpointFactory: {}", defaultRemoteEndpointFactory);
    }

    public WebSocketSession(WebSocketContainerScope containerScope, URI requestURI, EventDriver websocket, LogicalConnection connection)
    {
        Objects.requireNonNull(containerScope, "Container Scope cannot be null");
        Objects.requireNonNull(requestURI, "Request URI cannot be null");

        this.classLoader = containerScope.getClassLoader();
        this.containerScope = containerScope;
        this.requestURI = requestURI;
        this.websocket = websocket;
        this.connection = connection;
        this.executor = connection.getExecutor();
        this.outgoingHandler = connection;
        this.incomingHandler = websocket;
        this.policy = websocket.getPolicy();

        remoteEndpointFactory = (defaultRemoteEndpointFactory == null) ? this : defaultRemoteEndpointFactory;
        if (LOG.isDebugEnabled())
            LOG.debug("Using RemoteEndpointFactory: {}", remoteEndpointFactory);

        this.connection.setSession(this);

        addBean(this.connection);
        addBean(this.websocket);

        notifySessionListeners(containerScope, (listener) -> listener.onSessionCreated(this));
    }

    /**
     * Close the active session based on the throwable
     *
     * @param cause the cause for closing the connection
     */
    public void close(Throwable cause)
    {
        connection.close(cause);
    }

    @Override
    public void close()
    {
        /* This is assumed to always be a NORMAL closure, no reason phrase */
        close(new CloseInfo(StatusCode.NORMAL), null);
    }

    @Override
    public void close(CloseStatus closeStatus)
    {
        close(new CloseInfo(closeStatus.getCode(), closeStatus.getPhrase()), null);
    }

    @Override
    public void close(int statusCode, String reason)
    {
        close(new CloseInfo(statusCode, reason), null);
    }

    /**
     * Close Primary Entry Point.
     *
     * @param closeInfo the close details
     */
    private void close(CloseInfo closeInfo, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("close({})", closeInfo);

        connection.close(closeInfo, callback);
    }

    /**
     * Harsh disconnect
     */
    @Override
    public void disconnect()
    {
        connection.disconnect();
    }

    public void dispatch(Runnable runnable)
    {
        executor.execute(runnable);
    }

    @Override
    protected void doStart() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("starting - {}", this);

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("stopping - {}", this);
        connection.close(new CloseInfo(StatusCode.SHUTDOWN, "Shutdown"), new DisconnectCallback(connection));
        super.doStop();
    }

    @Override
    public String dumpSelf()
    {
        return String.format("%s@%x[behavior=%s,batchMode=%s,idleTimeout=%d,requestURI=%s]",
            this.getClass().getSimpleName(), hashCode(),
            getPolicy().getBehavior(),
            getBatchMode(),
            getIdleTimeout(),
            getRequestURI());
    }

    public ByteBufferPool getBufferPool()
    {
        return this.connection.getBufferPool();
    }

    public ClassLoader getClassLoader()
    {
        return this.getClass().getClassLoader();
    }

    public LogicalConnection getConnection()
    {
        return connection;
    }

    @Override
    public WebSocketContainerScope getContainerScope()
    {
        return this.containerScope;
    }

    public ExtensionFactory getExtensionFactory()
    {
        return extensionFactory;
    }

    /**
     * The idle timeout in milliseconds
     */
    @Override
    public long getIdleTimeout()
    {
        return connection.getMaxIdleTimeout();
    }

    @ManagedAttribute(readonly = true)
    public IncomingFrames getIncomingHandler()
    {
        return incomingHandler;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return connection.getLocalAddress();
    }

    @ManagedAttribute(readonly = true)
    public OutgoingFrames getOutgoingHandler()
    {
        return outgoingHandler;
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    @Override
    public String getProtocolVersion()
    {
        return protocolVersion;
    }

    @Override
    public RemoteEndpoint getRemote()
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("[{}] {}.getRemote()", policy.getBehavior(), this.getClass().getSimpleName());
        }

        return remote;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return remote.getInetSocketAddress();
    }

    public URI getRequestURI()
    {
        return requestURI;
    }

    @Override
    public UpgradeRequest getUpgradeRequest()
    {
        return this.upgradeRequest;
    }

    @Override
    public UpgradeResponse getUpgradeResponse()
    {
        return this.upgradeResponse;
    }

    @Override
    public WebSocketSession getWebSocketSession()
    {
        return this;
    }

    /**
     * Incoming Raw Frames from Parser
     */
    @Override
    public void incomingFrame(Frame frame)
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader(classLoader);
            if (connection.canReadWebSocketFrames())
            {
                // Forward Frames Through Extension List
                incomingHandler.incomingFrame(frame);
            }
            else
            {
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("Attempt to process frame when in wrong connection state: " + connection.toStateString(), new RuntimeException("TRACE"));
                }
            }
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    @Override
    public void outgoingFrame(Frame frame, WriteCallback callback, BatchMode batchMode)
    {
        if (onCloseCalled.get())
        {
            try
            {
                if (callback != null)
                    callback.writeFailed(new WebSocketException("Session closed"));
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Exception while notifying failure of callback " + callback, x);
            }
            return;
        }

        outgoingHandler.outgoingFrame(frame, callback, batchMode);
    }

    @Override
    public boolean isOpen()
    {
        if (this.connection == null)
            return false;

        return !onCloseCalled.get() && this.connection.isOpen();
    }

    @Override
    public boolean isSecure()
    {
        if (upgradeRequest == null)
        {
            throw new IllegalStateException("No valid UpgradeRequest yet");
        }

        URI requestURI = upgradeRequest.getRequestURI();

        return "wss".equalsIgnoreCase(requestURI.getScheme());
    }

    public void callApplicationOnClose(CloseInfo closeInfo)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("callApplicationOnClose({})", closeInfo);

        if (onCloseCalled.compareAndSet(false, true))
        {
            try
            {
                websocket.onClose(closeInfo);
            }
            catch (Throwable t)
            {
                LOG.warn("Exception while notifying onClose", t);
            }
        }
    }

    public void callApplicationOnError(Throwable cause)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("callApplicationOnError()", cause);

        if (openFuture != null)
            openFuture.completeExceptionally(cause);

        // Only notify onError if onClose has not been called.
        if (!onCloseCalled.get())
        {
            try
            {
                websocket.onError(cause);
            }
            catch (Throwable t)
            {
                LOG.warn("Exception while notifying onError", t);
            }
        }
    }

    /**
     * Jetty Connection onSessionClosed event
     *
     * @param connection the connection that was closed
     */
    @Override
    public void onClosed(Connection connection)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("[{}] {}.onSessionClosed()", policy.getBehavior(), this.getClass().getSimpleName());
        if (connection == this.connection)
        {
            this.connection.disconnect();
            try
            {
                notifySessionListeners(containerScope, (listener) -> listener.onSessionClosed(this));
            }
            catch (Throwable cause)
            {
                LOG.ignore(cause);
            }
        }
    }

    /**
     * Jetty Connection onOpen event
     *
     * @param connection the connection that was opened
     */
    @Override
    public void onOpened(Connection connection)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("[{}] {}.onSessionOpened()", policy.getBehavior(), this.getClass().getSimpleName());
        open();
    }

    @Override
    public WebSocketRemoteEndpoint newRemoteEndpoint(LogicalConnection connection, OutgoingFrames outgoingFrames, BatchMode batchMode)
    {
        return new WebSocketRemoteEndpoint(connection, outgoingFrames, getBatchMode());
    }

    /**
     * Open/Activate the session
     */
    public void open()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("[{}] {}.open()", policy.getBehavior(), this.getClass().getSimpleName());

        if (remote != null)
        {
            // already opened
            return;
        }

        try (ThreadClassLoaderScope ignored = new ThreadClassLoaderScope(classLoader))
        {
            // Upgrade success
            if (connection.opening())
            {
                // Connect remote
                remote = remoteEndpointFactory.newRemoteEndpoint(connection, this, getBatchMode());
                if (LOG.isDebugEnabled())
                    LOG.debug("[{}] {}.open() remote={}", policy.getBehavior(), this.getClass().getSimpleName(), remote);

                // Open WebSocket - and call Application onOpen
                websocket.openSession(this);

                // Open connection
                if (connection.opened())
                {
                    try
                    {
                        notifySessionListeners(containerScope, (listener) -> listener.onSessionOpened(this));
                    }
                    catch (Throwable t)
                    {
                        LOG.ignore(t);
                    }
                }
                else
                {
                    // we had a failure during onOpen()
                    callApplicationOnClose(new CloseInfo(StatusCode.ABNORMAL, "Failed to open local endpoint"));
                    disconnect();
                }

                if (LOG.isDebugEnabled())
                {
                    LOG.debug("[{}] open -> {}", getPolicy().getBehavior(), dump());
                }

                if (openFuture != null)
                {
                    openFuture.complete(this);
                }
            }
        }
        catch (Throwable t)
        {
            close(t);
        }
    }

    public void setExtensionFactory(ExtensionFactory extensionFactory)
    {
        this.extensionFactory = extensionFactory;
    }

    public void setFuture(CompletableFuture<Session> fut)
    {
        this.openFuture = fut;
        fut.whenComplete((s, t) ->
        {
            if (t != null)
                close(t);
        });
    }

    /**
     * Set the timeout in milliseconds
     */
    @Override
    public void setIdleTimeout(long ms)
    {
        connection.setMaxIdleTimeout(ms);
    }

    public void setOutgoingHandler(OutgoingFrames outgoing)
    {
        this.outgoingHandler = outgoing;
    }

    @Deprecated
    public void setPolicy(WebSocketPolicy policy)
    {
        // do nothing
    }

    public void setUpgradeRequest(UpgradeRequest request)
    {
        this.upgradeRequest = request;
        this.protocolVersion = request.getProtocolVersion();
        this.parameterMap.clear();
        if (request.getParameterMap() != null)
        {
            for (Map.Entry<String, List<String>> entry : request.getParameterMap().entrySet())
            {
                List<String> values = entry.getValue();
                if (values != null)
                {
                    this.parameterMap.put(entry.getKey(), values.toArray(new String[values.size()]));
                }
                else
                {
                    this.parameterMap.put(entry.getKey(), new String[0]);
                }
            }
        }
    }

    public void setUpgradeResponse(UpgradeResponse response)
    {
        this.upgradeResponse = response;
    }

    @Override
    public SuspendToken suspend()
    {
        if (onCloseCalled.get())
            throw new IllegalStateException("Not open");

        return connection.suspend();
    }

    /**
     * @return the default (initial) value for the batching mode.
     */
    public BatchMode getBatchMode()
    {
        return BatchMode.AUTO;
    }

    private void notifySessionListeners(WebSocketContainerScope scope, Consumer<WebSocketSessionListener> consumer)
    {
        for (WebSocketSessionListener listener : scope.getSessionListeners())
        {
            try
            {
                consumer.accept(listener);
            }
            catch (Throwable x)
            {
                LOG.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("WebSocketSession[");
        builder.append("websocket=").append(websocket);
        builder.append(",behavior=").append(policy.getBehavior());
        builder.append(",connection=").append(connection);
        builder.append(",remote=").append(remote);
        builder.append(",incoming=").append(incomingHandler);
        builder.append(",outgoing=").append(outgoingHandler);
        builder.append("]");
        return builder.toString();
    }
}
