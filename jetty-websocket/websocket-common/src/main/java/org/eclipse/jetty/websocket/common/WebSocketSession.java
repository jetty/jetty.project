//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Utf8Appendable.NotUtf8Exception;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ThreadClassLoaderScope;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.CloseException;
import org.eclipse.jetty.websocket.api.CloseStatus;
import org.eclipse.jetty.websocket.api.FrameCallback;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.frames.CloseFrame;
import org.eclipse.jetty.websocket.common.function.CommonEndpointFunctions;
import org.eclipse.jetty.websocket.common.function.EndpointFunctions;
import org.eclipse.jetty.websocket.common.io.AbstractWebSocketConnection;
import org.eclipse.jetty.websocket.common.message.MessageSink;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.eclipse.jetty.websocket.common.scopes.WebSocketSessionScope;

@ManagedObject("A Jetty WebSocket Session")
public class WebSocketSession extends ContainerLifeCycle implements Session, RemoteEndpointFactory,
        WebSocketSessionScope, IncomingFrames, LogicalConnection.Listener, Connection.Listener
{
    private static final FrameCallback EMPTY = new FrameCallback.Adapter();
    
    private final Logger LOG;
    
    private final WebSocketContainerScope containerScope;
    private final WebSocketPolicy policy;
    private final URI requestURI;
    private final LogicalConnection connection;
    private final Executor executor;
    private final AtomicConnectionState connectionState = new AtomicConnectionState();
    private final AtomicBoolean closeSent = new AtomicBoolean();

    // The websocket endpoint object itself
    private final Object endpoint;

    // Endpoint Functions and MessageSinks
    protected EndpointFunctions endpointFunctions;
    private MessageSink activeMessageSink;

    private ClassLoader classLoader;
    private ExtensionFactory extensionFactory;
    private BatchMode batchmode = BatchMode.AUTO;
    private RemoteEndpointFactory remoteEndpointFactory;
    private String protocolVersion;
    private Map<String, String[]> parameterMap = new HashMap<>();
    private RemoteEndpoint remote;
    private OutgoingFrames outgoingHandler;
    private UpgradeRequest upgradeRequest;
    private UpgradeResponse upgradeResponse;
    private CompletableFuture<Session> openFuture;
    private AtomicReference<Throwable> pendingError = new AtomicReference<>();
    
    public WebSocketSession(WebSocketContainerScope containerScope, URI requestURI, Object endpoint, LogicalConnection connection)
    {
        Objects.requireNonNull(containerScope, "Container Scope cannot be null");
        Objects.requireNonNull(requestURI, "Request URI cannot be null");
    
        LOG = Log.getLogger(WebSocketSession.class.getName() + "." + connection.getPolicy().getBehavior().name());

        this.classLoader = Thread.currentThread().getContextClassLoader();
        this.containerScope = containerScope;
        this.requestURI = requestURI;
        this.endpoint = endpoint;
        this.connection = connection;
        this.executor = connection.getExecutor();
        this.outgoingHandler = connection;
        this.policy = connection.getPolicy();

        addBean(this.connection);
    }
    
    public EndpointFunctions newEndpointFunctions(Object endpoint)
    {
        return new CommonEndpointFunctions(endpoint, getPolicy(), this.executor);
    }
    
    public void connect()
    {
        connectionState.onConnecting();
    }
    
    @Override
    public void close()
    {
        /* This is assumed to always be a NORMAL closure, no reason phrase */
        close(StatusCode.NORMAL, null);
    }

    @Override
    public void close(CloseStatus closeStatus)
    {
        close(closeStatus.getCode(),closeStatus.getPhrase());
    }

    @Override
    public void close(int statusCode, String reason)
    {
        close(new CloseInfo(statusCode, reason), EMPTY);
    }
    
    private void close(CloseInfo closeInfo, FrameCallback callback)
    {
        // TODO: review close from onOpen
        
        if(closeSent.compareAndSet(false,true))
        {
            LOG.debug("Sending Close Frame");
            CloseFrame closeFrame = closeInfo.asFrame();
            outgoingHandler.outgoingFrame(closeFrame, callback, BatchMode.OFF);
        }
        else
        {
            LOG.debug("Close Frame Previously Sent: ignoring: {} [{}]", closeInfo, callback);
            callback.succeed();
        }
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
    
        Iterator<RemoteEndpointFactory> iter = ServiceLoader.load(RemoteEndpointFactory.class).iterator();
        if (iter.hasNext())
            remoteEndpointFactory = iter.next();

        if (remoteEndpointFactory == null)
            remoteEndpointFactory = this;

        if (LOG.isDebugEnabled())
            LOG.debug("Using RemoteEndpointFactory: {}", remoteEndpointFactory);
    
        this.endpointFunctions = newEndpointFunctions(this.endpoint);
        addManaged(this.endpointFunctions);
    
        super.doStart();
        
        connection.setMaxIdleTimeout(this.policy.getIdleTimeout());
        
        Throwable fastFail;
        synchronized (pendingError)
        {
            fastFail = pendingError.get();
        }
        if(fastFail != null)
            onError(fastFail);
    }

    @Override
    protected void doStop() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("stopping - {}", this);

        try
        {
            close(StatusCode.SHUTDOWN,"Shutdown");
        }
        catch (Throwable t)
        {
            LOG.debug("During Connection Shutdown",t);
        }
        super.doStop();
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpThis(out);
        out.append(indent).append(" +- endpoint : ").append(endpoint.getClass().getName()).append('@').append(Integer.toHexString(endpoint.hashCode()));
        out.append(indent).append(" +- outgoingHandler : ");
        if (outgoingHandler instanceof Dumpable)
        {
            ((Dumpable) outgoingHandler).dump(out, indent + "    ");
        }
        else
        {
            out.append(outgoingHandler.toString()).append(System.lineSeparator());
        }
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (obj == null)
        {
            return false;
        }
        if (getClass() != obj.getClass())
        {
            return false;
        }
        WebSocketSession other = (WebSocketSession) obj;
        if (connection == null)
        {
            if (other.connection != null)
            {
                return false;
            }
        }
        else if (!connection.equals(other.connection))
        {
            return false;
        }
        return true;
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
    
    public AtomicConnectionState getConnectionState()
    {
        return connectionState;
    }
    
    @Override
    public WebSocketContainerScope getContainerScope()
    {
        return this.containerScope;
    }

    public Executor getExecutor()
    {
        return executor;
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

    private Throwable getInvokedCause(Throwable t)
    {
        if (t instanceof FunctionCallException)
        {
            Throwable cause = ((FunctionCallException) t).getInvokedCause();
            if(cause != null)
                return cause;
        }

        return t;
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
        return this.policy;
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
            LOG.debug("{}.getRemote()", this.getClass().getSimpleName());
        
        AtomicConnectionState.State state = connectionState.get();

        if ((state == AtomicConnectionState.State.OPEN) || (state == AtomicConnectionState.State.CONNECTED))
        {
            return remote;
        }

        throw new WebSocketException("RemoteEndpoint unavailable, current state [" + state + "], expecting [OPEN or CONNECTED]");
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return connection.getRemoteAddress();
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

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = (prime * result) + ((connection == null) ? 0 : connection.hashCode());
        return result;
    }
    
    /**
     * Incoming Raw Frames from Parser (after ExtensionStack)
     */
    @Override
    public void incomingFrame(Frame frame, FrameCallback callback)
    {
        try(ThreadClassLoaderScope scope = new ThreadClassLoaderScope(classLoader))
        {
            if (connectionState.get() == AtomicConnectionState.State.OPEN)
            {
                // For endpoints that want to see raw frames.
                // These are immutable.
                endpointFunctions.onFrame(frame);

                byte opcode = frame.getOpCode();
                switch (opcode)
                {
                    case OpCode.CLOSE:
                    {
                        CloseInfo closeInfo = null;
                        
                        if (connectionState.onClosing())
                        {
                            LOG.debug("ConnectionState: Transition to CLOSING");
                            CloseFrame closeframe = (CloseFrame) frame;
                            closeInfo = new CloseInfo(closeframe, true);
                        }
                        else
                        {
                            LOG.debug("ConnectionState: {} - Close Frame Received", connectionState);
                        }
                        
                        if (closeInfo != null)
                        {
                            notifyClose(closeInfo.getStatusCode(), closeInfo.getReason());
                            close(closeInfo, new CompletionCallback()
                            {
                                @Override
                                public void complete()
                                {
                                    if (connectionState.onClosed())
                                    {
                                        LOG.debug("ConnectionState: Transition to CLOSED");
                                        connection.disconnect();
                                    }
                                }
                            });
                        }
                        
                        // let fill/parse continue
                        callback.succeed();

                        return;
                    }
                    case OpCode.PING:
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("PING: {}", BufferUtil.toDetailString(frame.getPayload()));

                        ByteBuffer pongBuf;
                        if (frame.hasPayload())
                        {
                            pongBuf = ByteBuffer.allocate(frame.getPayload().remaining());
                            BufferUtil.put(frame.getPayload().slice(), pongBuf);
                            BufferUtil.flipToFlush(pongBuf, 0);
                        }
                        else
                        {
                            pongBuf = ByteBuffer.allocate(0);
                        }

                        endpointFunctions.onPing(frame.getPayload());
                        callback.succeed();
    
                        try
                        {
                            getRemote().sendPong(pongBuf);
                        }
                        catch (Throwable t)
                        {
                            LOG.debug("Unable to send pong", t);
                        }
                        break;
                    }
                    case OpCode.PONG:
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("PONG: {}", BufferUtil.toDetailString(frame.getPayload()));

                        endpointFunctions.onPong(frame.getPayload());
                        callback.succeed();
                        break;
                    }
                    case OpCode.BINARY:
                    {
                        endpointFunctions.onBinary(frame, callback);
                        return;
                    }
                    case OpCode.TEXT:
                    {
                        endpointFunctions.onText(frame, callback);
                        return;
                    }
                    case OpCode.CONTINUATION:
                    {
                        endpointFunctions.onContinuation(frame, callback);
                        if (activeMessageSink != null)
                            activeMessageSink.accept(frame, callback);

                        return;
                    }
                    default:
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Unhandled OpCode: {}", opcode);
                    }
                }
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Discarding post EOF frame - {}", frame);
            }
        }
        
        // Unset active MessageSink if this was a fin frame
        if (frame.getType().isData() && frame.isFin() && activeMessageSink != null)
            activeMessageSink = null;
    }

    @Override
    public boolean isOpen()
    {
        return this.connectionState.get() == AtomicConnectionState.State.OPEN;
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

    public void notifyClose(int statusCode, String reason)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("notifyClose({},{}) [{}]", statusCode, reason, getState());
        }
    
        CloseInfo closeInfo = new CloseInfo(statusCode, reason);
        endpointFunctions.onClose(closeInfo);
    }
    
    /**
     * Error Event.
     * <p>
     *     Can be seen from Session and Connection.
     * </p>
     *
     * @param t the raw cause
     */
    @Override
    public void onError(Throwable t)
    {
        synchronized (pendingError)
        {
            if (!endpointFunctions.isStarted())
            {
                // this is a *really* fast fail, before the Session has even started.
                pendingError.compareAndSet(null, t);
                return;
            }
        }
        
        Throwable cause = getInvokedCause(t);
        
        if (openFuture != null && !openFuture.isDone())
            openFuture.completeExceptionally(cause);
    
        // Forward Errors to User WebSocket Object
        endpointFunctions.onError(cause);
    
        if (cause instanceof NotUtf8Exception)
        {
            close(StatusCode.BAD_PAYLOAD, cause.getMessage());
        }
        else if (cause instanceof SocketTimeoutException)
        {
            close(StatusCode.SHUTDOWN, cause.getMessage());
        }
        else if (cause instanceof IOException)
        {
            close(StatusCode.PROTOCOL, cause.getMessage());
        }
        else if (cause instanceof SocketException)
        {
            close(StatusCode.SHUTDOWN, cause.getMessage());
        }
        else if (cause instanceof CloseException)
        {
            CloseException ce = (CloseException) cause;
            close(ce.getStatusCode(), ce.getMessage());
        }
        else
        {
            LOG.warn("Unhandled Error (closing connection)", cause);
        
            // Exception on end-user WS-Endpoint.
            // Fast-fail & close connection with reason.
            int statusCode = StatusCode.SERVER_ERROR;
            if (getPolicy().getBehavior() == WebSocketBehavior.CLIENT)
            {
                statusCode = StatusCode.POLICY_VIOLATION;
            }
            close(statusCode, cause.getMessage());
        }
    }
    
    /**
     * Connection Disconnect Event
     * @param connection the connection
     */
    @Override
    public void onClosed(Connection connection)
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{}.onSessionClosed()", containerScope.getClass().getSimpleName());
            containerScope.onSessionClosed(this);
        }
        catch (Throwable t)
        {
            LOG.ignore(t);
        }
    }
    
    /**
     * Connection Open Event
     * @param connection the connection
     */
    @Override
    public void onOpened(Connection connection)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}.onOpened()", this.getClass().getSimpleName());
        connectionState.onConnecting();
        open();
    }
    
    public WebSocketRemoteEndpoint newRemoteEndpoint(LogicalConnection connection, OutgoingFrames outgoingFrames, BatchMode batchMode)
    {
        return new WebSocketRemoteEndpoint(this,outgoingHandler,getBatchMode());
    }

    /**
     * Open/Activate the session
     */
    public void open()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}.open()", this.getClass().getSimpleName());

        if (remote != null)
        {
            // already opened
            return;
        }

        try (ThreadClassLoaderScope scope = new ThreadClassLoaderScope(classLoader))
        {
            // Upgrade success
            if(connectionState.onConnected())
            {
                // Connect remote
                remote = remoteEndpointFactory.newRemoteEndpoint(connection, outgoingHandler, getBatchMode());
                if (LOG.isDebugEnabled())
                    LOG.debug("{}.open() remote={}", this.getClass().getSimpleName(), remote);
    
                // Open WebSocket
                endpointFunctions.onOpen(this);
    
                // Open connection
                if(connectionState.onOpen())
                {
                    // notify session listeners
                    try
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("{}.onSessionOpened()", containerScope.getClass().getSimpleName());
                        containerScope.onSessionOpened(this);
                    }
                    catch (Throwable t)
                    {
                        LOG.ignore(t);
                    }
                    
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug("open -> {}", dump());
                    }
    
                    if (openFuture != null)
                    {
                        openFuture.complete(this);
                    }
                    
                    connection.fillInterested();
                }
            }
            else
            {
                IllegalStateException ise = new IllegalStateException("Unexpected state [" + connectionState.get() + "] when attempting to transition to CONNECTED");
                if (openFuture != null)
                {
                    openFuture.completeExceptionally(ise);
                }
                else
                {
                    throw ise;
                }
            }
        }
        catch (Throwable t)
        {
            LOG.warn(t);
            onError(t);
        }
    }

    public void setExtensionFactory(ExtensionFactory extensionFactory)
    {
        this.extensionFactory = extensionFactory;
    }

    public void setFuture(CompletableFuture<Session> fut)
    {
        this.openFuture = fut;
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
        return connection.suspend();
    }

    /**
     * @return the default (initial) value for the batching mode.
     */
    public BatchMode getBatchMode()
    {
        return this.batchmode;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(this.getClass().getSimpleName());
        sb.append('[');
        sb.append(getPolicy().getBehavior());
        Object endp = endpoint;
        // unwrap
        while (endp instanceof ManagedEndpoint)
        {
            endp = ((ManagedEndpoint) endp).getRawEndpoint();
        }
        sb.append(',').append(endp.getClass().getName());
        sb.append(',').append(getConnection().getClass().getSimpleName());
        if (getConnection() instanceof AbstractWebSocketConnection)
        {
            if(isOpen() && remote != null)
            {
                sb.append(',').append(getRemoteAddress());
                if (getPolicy().getBehavior() == WebSocketBehavior.SERVER)
                {
                    sb.append(',').append(getRequestURI());
                    sb.append(',').append(getLocalAddress());
                }
            }
        }
        sb.append(']');
        return sb.toString();
    }

    public interface Listener
    {
        void onOpened(WebSocketSession session);

        void onClosed(WebSocketSession session);
    }
}
