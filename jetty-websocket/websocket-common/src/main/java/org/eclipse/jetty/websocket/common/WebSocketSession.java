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
import org.eclipse.jetty.websocket.common.io.IOState;
import org.eclipse.jetty.websocket.common.io.IOState.ConnectionStateListener;
import org.eclipse.jetty.websocket.common.message.MessageSink;
import org.eclipse.jetty.websocket.common.reflect.DynamicArgsException;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.eclipse.jetty.websocket.common.scopes.WebSocketSessionScope;

@ManagedObject("A Jetty WebSocket Session")
public class WebSocketSession extends ContainerLifeCycle implements Session, RemoteEndpointFactory, WebSocketSessionScope, IncomingFrames, Connection.Listener, ConnectionStateListener
{
    private static final Logger LOG = Log.getLogger(WebSocketSession.class);
    private static final Logger LOG_OPEN = Log.getLogger(WebSocketSession.class.getName() + "_OPEN");
    private final WebSocketContainerScope containerScope;
    private final URI requestURI;
    private final LogicalConnection connection;
    private final Executor executor;

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

    public WebSocketSession(WebSocketContainerScope containerScope, URI requestURI, Object endpoint, LogicalConnection connection)
    {
        Objects.requireNonNull(containerScope, "Container Scope cannot be null");
        Objects.requireNonNull(requestURI, "Request URI cannot be null");

        this.classLoader = Thread.currentThread().getContextClassLoader();
        this.containerScope = containerScope;
        this.requestURI = requestURI;
        this.endpoint = endpoint;
        this.connection = connection;
        this.executor = connection.getExecutor();
        this.outgoingHandler = connection;
        this.connection.getIOState().addListener(this);
        this.policy = connection.getPolicy();

        addBean(this.connection);
    }
    
    public EndpointFunctions newEndpointFunctions(Object endpoint)
    {
        return new CommonEndpointFunctions(endpoint, this.policy, this.executor);
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
        connection.close(statusCode, reason);
    }

    /**
     * Harsh disconnect
     */
    @Override
    public void disconnect()
    {
        connection.disconnect();

        // notify of harsh disconnect
        notifyClose(StatusCode.NO_CLOSE, "Harsh disconnect");
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
        addBean(this.endpointFunctions);
    
        super.doStart();
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
            return ((FunctionCallException) t).getInvokedCause();
        }
        else if (t instanceof DynamicArgsException)
        {
            Throwable cause = ((DynamicArgsException) t).getInvokedCause();
            if (cause != null)
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
        if (LOG_OPEN.isDebugEnabled())
            LOG_OPEN.debug("[{}] {}.getRemote()", policy.getBehavior(), this.getClass().getSimpleName());
        ConnectionState state = connection.getIOState().getConnectionState();

        if ((state == ConnectionState.OPEN) || (state == ConnectionState.CONNECTED))
        {
            return remote;
        }

        throw new WebSocketException("RemoteEndpoint unavailable, current state [" + state + "], expecting [OPEN or CONNECTED]");
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

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = (prime * result) + ((connection == null) ? 0 : connection.hashCode());
        return result;
    }

    /**
     * Incoming Errors
     */
    @Override
    public void incomingError(Throwable t)
    {
        // Forward Errors to User WebSocket Object
        endpointFunctions.onError(t);
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
            if (connection.getIOState().isInputAvailable())
            {
                endpointFunctions.onFrame(frame);

                byte opcode = frame.getOpCode();
                switch (opcode)
                {
                    case OpCode.CLOSE:
                    {
                        boolean validate = true;
                        CloseFrame closeframe = (CloseFrame) frame;
                        CloseInfo close = new CloseInfo(closeframe, validate);

                        // process handshake
                        getConnection().getIOState().onCloseRemote(close);

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

                        getRemote().sendPong(pongBuf);
                        break;
                    }
                    case OpCode.PONG:
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("PONG: {}", BufferUtil.toDetailString(frame.getPayload()));

                        endpointFunctions.onPong(frame.getPayload());
                        break;
                    }
                    case OpCode.BINARY:
                    {
                        endpointFunctions.onBinary(frame.getPayload(), frame.isFin());
                        return;
                    }
                    case OpCode.TEXT:
                    {
                        endpointFunctions.onText(frame.getPayload(), frame.isFin());
                        return;
                    }
                    case OpCode.CONTINUATION:
                    {
                        endpointFunctions.onContinuation(frame.getPayload(), frame.isFin());
                        if (activeMessageSink != null)
                            activeMessageSink.accept(frame.getPayload(), frame.isFin());

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
        catch (NotUtf8Exception e)
        {
            notifyError(e);
            close(StatusCode.BAD_PAYLOAD, e.getMessage());
        }
        catch (CloseException e)
        {
            close(e.getStatusCode(), e.getMessage());
        }
        catch (Throwable t)
        {
            Throwable cause = getInvokedCause(t);

            LOG.warn("Unhandled Error (closing connection)", cause);

            notifyError(cause);

            // Unhandled Error, close the connection.
            switch (policy.getBehavior())
            {
                case SERVER:
                    close(StatusCode.SERVER_ERROR, cause.getClass().getSimpleName());
                    break;
                case CLIENT:
                    close(StatusCode.POLICY_VIOLATION, cause.getClass().getSimpleName());
                    break;
            }
        }
        finally
        {
            // Unset active MessageSink if this was a fin frame
            if (frame.getType().isData() && frame.isFin() && activeMessageSink != null)
                activeMessageSink = null;

            Thread.currentThread().setContextClassLoader(old);
        }
    }

    @Override
    public boolean isOpen()
    {
        if (this.connection == null)
        {
            return false;
        }
        return this.connection.isOpen();
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
            LOG.debug("notifyClose({},{})", statusCode, reason);
        }
        endpointFunctions.onClose(new CloseInfo(statusCode, reason));
    }

    public void notifyError(Throwable cause)
    {
        if (openFuture != null && !openFuture.isDone())
            openFuture.completeExceptionally(cause);
        incomingError(cause);
    }

    @Override
    public void onClosed(Connection connection)
    {
    }

    @Override
    public void onOpened(Connection connection)
    {
        if (LOG_OPEN.isDebugEnabled())
            LOG_OPEN.debug("[{}] {}.onOpened()", policy.getBehavior(), this.getClass().getSimpleName());
        open();
    }

    @SuppressWarnings("incomplete-switch")
    @Override
    public void onConnectionStateChange(ConnectionState state)
    {
        switch (state)
        {
            case CLOSED:
                IOState ioState = this.connection.getIOState();
                CloseInfo close = ioState.getCloseInfo();
                // confirmed close of local endpoint
                notifyClose(close.getStatusCode(), close.getReason());
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
                break;
            case CONNECTED:
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
                break;
        }
    }

    public WebSocketRemoteEndpoint newRemoteEndpoint(LogicalConnection connection, OutgoingFrames outgoingFrames, BatchMode batchMode)
    {
        return new WebSocketRemoteEndpoint(connection,outgoingHandler,getBatchMode());
    }

    /**
     * Open/Activate the session
     */
    public void open()
    {
        if (LOG_OPEN.isDebugEnabled())
            LOG_OPEN.debug("[{}] {}.open()", policy.getBehavior(), this.getClass().getSimpleName());

        if (remote != null)
        {
            // already opened
            return;
        }

        try (ThreadClassLoaderScope scope = new ThreadClassLoaderScope(classLoader))
        {
            // Upgrade success
            connection.getIOState().onConnected();

            // Connect remote
            remote = remoteEndpointFactory.newRemoteEndpoint(connection,outgoingHandler,getBatchMode());
            if (LOG_OPEN.isDebugEnabled())
                LOG_OPEN.debug("[{}] {}.open() remote={}", policy.getBehavior(), this.getClass().getSimpleName(), remote);

            // Open WebSocket
            endpointFunctions.onOpen(this);

            // Open connection
            connection.getIOState().onOpened();

            if (LOG.isDebugEnabled())
            {
                LOG.debug("open -> {}", dump());
            }
            
            if(openFuture != null)
            {
                openFuture.complete(this);
            }
        }
        catch (CloseException ce)
        {
            LOG.warn(ce);
            notifyError(ce.getCause());
            close(ce.getStatusCode(), ce.getMessage());
        }
        catch (Throwable t)
        {
            Throwable cause = getInvokedCause(t);

            LOG.warn(cause);
            notifyError(cause);
            // Exception on end-user WS-Endpoint.
            // Fast-fail & close connection with reason.
            int statusCode = StatusCode.SERVER_ERROR;
            if (policy.getBehavior() == WebSocketBehavior.CLIENT)
            {
                statusCode = StatusCode.POLICY_VIOLATION;
            }
            close(statusCode, cause.getMessage());
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
            if(getConnection().getIOState().isOpen() && remote != null)
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

    public static interface Listener
    {
        void onOpened(WebSocketSession session);

        void onClosed(WebSocketSession session);
    }
}
