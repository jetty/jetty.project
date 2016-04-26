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

package org.eclipse.jetty.websocket.common;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.Executor;
import java.util.function.Function;

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
import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketConnectionListener;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketFrameListener;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.api.WebSocketPartialListener;
import org.eclipse.jetty.websocket.api.WebSocketPingPongListener;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.frames.CloseFrame;
import org.eclipse.jetty.websocket.common.frames.ReadOnlyDelegatedFrame;
import org.eclipse.jetty.websocket.common.functions.OnByteArrayFunction;
import org.eclipse.jetty.websocket.common.functions.OnByteBufferFunction;
import org.eclipse.jetty.websocket.common.functions.OnCloseFunction;
import org.eclipse.jetty.websocket.common.functions.OnErrorFunction;
import org.eclipse.jetty.websocket.common.functions.OnFrameFunction;
import org.eclipse.jetty.websocket.common.functions.OnInputStreamFunction;
import org.eclipse.jetty.websocket.common.functions.OnOpenFunction;
import org.eclipse.jetty.websocket.common.functions.OnReaderFunction;
import org.eclipse.jetty.websocket.common.functions.OnTextFunction;
import org.eclipse.jetty.websocket.common.io.IOState;
import org.eclipse.jetty.websocket.common.io.IOState.ConnectionStateListener;
import org.eclipse.jetty.websocket.common.message.ByteArrayMessageSink;
import org.eclipse.jetty.websocket.common.message.ByteBufferMessageSink;
import org.eclipse.jetty.websocket.common.message.InputStreamMessageSink;
import org.eclipse.jetty.websocket.common.message.MessageSink;
import org.eclipse.jetty.websocket.common.message.PartialBinaryMessageSink;
import org.eclipse.jetty.websocket.common.message.PartialTextMessageSink;
import org.eclipse.jetty.websocket.common.message.ReaderMessageSink;
import org.eclipse.jetty.websocket.common.message.StringMessageSink;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.eclipse.jetty.websocket.common.scopes.WebSocketSessionScope;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;

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

    // The functions for calling into websocket endpoint's declared event handlers
    protected Function<Session, Void> onOpenFunction;
    protected Function<CloseInfo, Void> onCloseFunction;
    protected Function<Throwable, Void> onErrorFunction;
    protected Function<ByteBuffer, Void> onPingFunction;
    protected Function<ByteBuffer, Void> onPongFunction;
    protected Function<Frame, Void> onFrameFunction;

    // Message Handling sinks
    protected MessageSink onTextSink;
    protected MessageSink onBinarySink;
    protected MessageSink activeMessageSink;

    private ClassLoader classLoader;
    private ExtensionFactory extensionFactory;
    private BatchMode batchmode = BatchMode.AUTO;
    private String protocolVersion;
    private Map<String, String[]> parameterMap = new HashMap<>();
    private WebSocketRemoteEndpoint remote;
    private OutgoingFrames outgoingHandler;
    private WebSocketPolicy policy;
    private UpgradeRequest upgradeRequest;
    private UpgradeResponse upgradeResponse;

    public WebSocketSession(WebSocketContainerScope containerScope, URI requestURI, Object endpoint, LogicalConnection connection)
    {
        Objects.requireNonNull(containerScope,"Container Scope cannot be null");
        Objects.requireNonNull(requestURI,"Request URI cannot be null");

        this.classLoader = Thread.currentThread().getContextClassLoader();
        this.containerScope = containerScope;
        this.requestURI = requestURI;
        this.endpoint = endpoint;
        this.connection = connection;
        this.executor = connection.getExecutor();
        this.outgoingHandler = connection;
        this.connection.getIOState().addListener(this);
        this.policy = connection.getPolicy();

        discoverEndpointFunctions(this.endpoint);

        addBean(this.connection);
    }

    protected void discoverEndpointFunctions(Object endpoint)
    {
        // Connection Listener

        if (endpoint instanceof WebSocketConnectionListener)
        {
            WebSocketConnectionListener wslistener = (WebSocketConnectionListener)endpoint;
            onOpenFunction = (sess) -> {
                wslistener.onWebSocketConnect(sess);
                return null;
            };
            onCloseFunction = (closeinfo) -> {
                wslistener.onWebSocketClose(closeinfo.getStatusCode(),closeinfo.getReason());
                return null;
            };
            onErrorFunction = (cause) -> {
                wslistener.onWebSocketError(cause);
                return null;
            };
        }

        // Simple Data Listener

        if (endpoint instanceof WebSocketListener)
        {
            WebSocketListener wslistener = (WebSocketListener)endpoint;
            onTextSink = new StringMessageSink(policy,(payload) -> {
                wslistener.onWebSocketText(payload);
                return null;
            });
            onBinarySink = new ByteArrayMessageSink(policy,(payload) -> {
                wslistener.onWebSocketBinary(payload,0,payload.length);
                return null;
            });
        }

        // Ping/Pong Listener

        if (endpoint instanceof WebSocketPingPongListener)
        {
            WebSocketPingPongListener wslistener = (WebSocketPingPongListener)endpoint;
            onPongFunction = (pong) -> {
                ByteBuffer payload = pong;
                if (pong == null)
                    payload = BufferUtil.EMPTY_BUFFER;
                wslistener.onWebSocketPong(payload);
                return null;
            };
            onPingFunction = (ping) -> {
                ByteBuffer payload = ping;
                if (ping == null)
                    payload = BufferUtil.EMPTY_BUFFER;
                wslistener.onWebSocketPing(payload);
                return null;
            };
        }

        // Partial Data / Message Listener

        if (endpoint instanceof WebSocketPartialListener)
        {
            for(Method method: WebSocketPartialListener.class.getDeclaredMethods())
            {
                if(method.getName().equals("onWebSocketPartialText"))
                    assertNotSet(onTextSink, "TEXT Message Handler", endpoint.getClass(), method);
                else if(method.getName().equals("onWebSocketPartialBinary"))
                    assertNotSet(onBinarySink, "BINARY Message Handler", endpoint.getClass(), method);
            }
            
            WebSocketPartialListener wslistener = (WebSocketPartialListener)endpoint;
            onTextSink = new PartialTextMessageSink((partial) -> {
                wslistener.onWebSocketPartialText(partial.getPayload(),partial.isFin());
                return null;
            });
            onBinarySink = new PartialBinaryMessageSink((partial) -> {
                wslistener.onWebSocketPartialBinary(partial.getPayload(),partial.isFin());
                return null;
            });
        }

        // Frame Listener

        if (endpoint instanceof WebSocketFrameListener)
        {
            WebSocketFrameListener wslistener = (WebSocketFrameListener)endpoint;
            onFrameFunction = (frame) -> {
                wslistener.onWebSocketFrame(new ReadOnlyDelegatedFrame(frame));
                return null;
            };
        }

        // Test for annotated websocket endpoint
        
        Class<?> endpointClass = endpoint.getClass();
        WebSocket websocket = endpointClass.getAnnotation(WebSocket.class);
        if (websocket != null)
        {
            policy.setInputBufferSize(websocket.inputBufferSize());
            policy.setMaxBinaryMessageSize(websocket.maxBinaryMessageSize());
            policy.setMaxTextMessageSize(websocket.maxTextMessageSize());
            policy.setIdleTimeout(websocket.maxIdleTime());
            
            this.batchmode = websocket.batchMode();
            
            Method onmethod = null;

            // OnWebSocketConnect [0..1]
            onmethod = ReflectUtils.findAnnotatedMethod(endpointClass,OnWebSocketConnect.class);
            if (onmethod != null)
            {
                assertNotSet(onOpenFunction, "Open/Connect Handler", endpointClass, onmethod);
                onOpenFunction = new OnOpenFunction(endpoint,onmethod);
            }
            // OnWebSocketClose [0..1]
            onmethod = ReflectUtils.findAnnotatedMethod(endpointClass,OnWebSocketClose.class);
            if (onmethod != null)
            {
                assertNotSet(onCloseFunction, "Close Handler", endpointClass, onmethod);
                onCloseFunction = new OnCloseFunction(this,endpoint,onmethod);
            }
            // OnWebSocketError [0..1]
            onmethod = ReflectUtils.findAnnotatedMethod(endpointClass,OnWebSocketError.class);
            if (onmethod != null)
            {
                assertNotSet(onErrorFunction, "Error Handler", endpointClass, onmethod);
                onErrorFunction = new OnErrorFunction(this,endpoint,onmethod);
            }
            // OnWebSocketFrame [0..1]
            onmethod = ReflectUtils.findAnnotatedMethod(endpointClass,OnWebSocketFrame.class);
            if (onmethod != null)
            {
                assertNotSet(onFrameFunction, "Frame Handler", endpointClass, onmethod);
                onFrameFunction = new OnFrameFunction(this,endpoint,onmethod);
            }
            // OnWebSocketMessage [0..2]
            Method onmessages[] = ReflectUtils.findAnnotatedMethods(endpointClass,OnWebSocketMessage.class);
            if (onmessages != null && onmessages.length > 0)
            {
                for (Method onmsg : onmessages)
                {
                    if (OnTextFunction.hasMatchingSignature(onmsg))
                    {
                        assertNotSet(onTextSink, "TEXT Message Handler", endpointClass, onmsg);
                        // Normal Text Message
                        onTextSink = new StringMessageSink(policy,new OnTextFunction(this,endpoint,onmsg));
                    }
                    else if (OnByteBufferFunction.hasMatchingSignature(onmsg))
                    {
                        assertNotSet(onBinarySink, "Binary Message Handler", endpointClass, onmsg);
                        // ByteBuffer Binary Message
                        onBinarySink = new ByteBufferMessageSink(policy,new OnByteBufferFunction(this,endpoint,onmsg));
                    }
                    else if (OnByteArrayFunction.hasMatchingSignature(onmsg))
                    {
                        assertNotSet(onBinarySink, "Binary Message Handler", endpointClass, onmsg);
                        // byte[] Binary Message
                        onBinarySink = new ByteArrayMessageSink(policy,new OnByteArrayFunction(this,endpoint,onmsg));
                    }
                    else if (OnInputStreamFunction.hasMatchingSignature(onmsg))
                    {
                        assertNotSet(onBinarySink, "Binary Message Handler", endpointClass, onmsg);
                        // InputStream Binary Message
                        onBinarySink = new InputStreamMessageSink(executor,new OnInputStreamFunction(this,endpoint,onmsg));
                    }
                    else if (OnReaderFunction.hasMatchingSignature(onmsg))
                    {
                        assertNotSet(onTextSink, "TEXT Message Handler", endpointClass, onmsg);
                        // Reader Text Message
                        onTextSink = new ReaderMessageSink(executor,new OnReaderFunction(this,endpoint,onmsg));
                    }
                    else
                    {
                        // Not a valid @OnWebSocketMessage declaration signature
                        throw InvalidSignatureException.build(onmsg,OnWebSocketMessage.class,
                                OnTextFunction.getDynamicArgsBuilder(),
                                OnByteBufferFunction.getDynamicArgsBuilder(),
                                OnByteArrayFunction.getDynamicArgsBuilder(),
                                OnInputStreamFunction.getDynamicArgsBuilder(),
                                OnReaderFunction.getDynamicArgsBuilder());
                    }
                }
            }
        }
    }

    protected void assertNotSet(Object val, String role, Class<?> pojo, Method method)
    {
        if(val == null)
            return;
        
        StringBuilder err = new StringBuilder();
        err.append("Cannot replace previously assigned ");
        err.append(role);
        err.append(" with ");
        ReflectUtils.append(err,pojo,method);

        throw new InvalidWebSocketException(err.toString());
    }

    @Override
    public void close()
    {
        /* This is assumed to always be a NORMAL closure, no reason phrase */
        connection.close(StatusCode.NORMAL,null);
    }

    @Override
    public void close(CloseStatus closeStatus)
    {
        close(closeStatus.getCode(),closeStatus.getPhrase());
    }

    @Override
    public void close(int statusCode, String reason)
    {
        connection.close(statusCode,reason);
    }

    /**
     * Harsh disconnect
     */
    @Override
    public void disconnect()
    {
        connection.disconnect();

        // notify of harsh disconnect
        notifyClose(StatusCode.NO_CLOSE,"Harsh disconnect");
    }

    public void dispatch(Runnable runnable)
    {
        executor.execute(runnable);
    }

    @Override
    protected void doStart() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("starting - {}",this);

        Iterator<RemoteEndpointFactory> iter = ServiceLoader.load(RemoteEndpointFactory.class).iterator();
        if (iter.hasNext())
            remoteEndpointFactory = iter.next();

        if (remoteEndpointFactory == null)
            remoteEndpointFactory = this;

        if (LOG.isDebugEnabled())
            LOG.debug("Using RemoteEndpointFactory: {}", remoteEndpointFactory);

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("stopping - {}",this);

        if (getConnection() != null)
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
            ((Dumpable)outgoingHandler).dump(out,indent + "    ");
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
        WebSocketSession other = (WebSocketSession)obj;
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
            LOG_OPEN.debug("[{}] {}.getRemote()",policy.getBehavior(),this.getClass().getSimpleName());
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
     * Incoming Errors from Parser
     */
    @Override
    public void incomingError(Throwable t)
    {
        // Forward Errors to User WebSocket Object
        if (onErrorFunction != null)
            onErrorFunction.apply(t);
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
                if (onFrameFunction != null)
                    onFrameFunction.apply(frame);

                byte opcode = frame.getOpCode();
                switch (opcode)
                {
                    case OpCode.CLOSE:
                    {
                        boolean validate = true;
                        CloseFrame closeframe = (CloseFrame)frame;
                        CloseInfo close = new CloseInfo(closeframe,validate);

                        // process handshake
                        getConnection().getIOState().onCloseRemote(close);

                        return;
                    }
                    case OpCode.PING:
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("PING: {}",BufferUtil.toDetailString(frame.getPayload()));

                        ByteBuffer pongBuf;
                        if (frame.hasPayload())
                        {
                            pongBuf = ByteBuffer.allocate(frame.getPayload().remaining());
                            BufferUtil.put(frame.getPayload().slice(),pongBuf);
                            BufferUtil.flipToFlush(pongBuf,0);
                        }
                        else
                        {
                            pongBuf = ByteBuffer.allocate(0);
                        }

                        if (onPingFunction != null)
                            onPingFunction.apply(frame.getPayload());

                        getRemote().sendPong(pongBuf);
                        break;
                    }
                    case OpCode.PONG:
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("PONG: {}",BufferUtil.toDetailString(frame.getPayload()));

                        if (onPongFunction != null)
                            onPongFunction.apply(frame.getPayload());
                        break;
                    }
                    case OpCode.BINARY:
                    {
                        if (activeMessageSink == null)
                            activeMessageSink = onBinarySink;

                        if (activeMessageSink != null)
                            activeMessageSink.accept(frame.getPayload(),frame.isFin());
                        return;
                    }
                    case OpCode.TEXT:
                    {
                        if (activeMessageSink == null)
                            activeMessageSink = onTextSink;

                        if (activeMessageSink != null)
                            activeMessageSink.accept(frame.getPayload(),frame.isFin());
                        return;
                    }
                    case OpCode.CONTINUATION:
                    {
                        if (activeMessageSink != null)
                            activeMessageSink.accept(frame.getPayload(),frame.isFin());

                        return;
                    }
                    default:
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Unhandled OpCode: {}",opcode);
                    }
                }
            }
        }
        catch (NotUtf8Exception e)
        {
            notifyError(e);
            close(StatusCode.BAD_PAYLOAD,e.getMessage());
        }
        catch (CloseException e)
        {
            close(e.getStatusCode(),e.getMessage());
        }
        catch (Throwable t)
        {
            LOG.warn("Unhandled Error (closing connection)",t);

            notifyError(t);

            // Unhandled Error, close the connection.
            switch (policy.getBehavior())
            {
                case SERVER:
                    close(StatusCode.SERVER_ERROR,t.getClass().getSimpleName());
                    break;
                case CLIENT:
                    close(StatusCode.POLICY_VIOLATION,t.getClass().getSimpleName());
                    break;
            }
        }
        finally
        {
            // Unset active MessageSink if this was a fin frame
            if (frame.isFin() && activeMessageSink != null)
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
            LOG.debug("notifyClose({},{})",statusCode,reason);
        }
        if (onCloseFunction != null)
            onCloseFunction.apply(new CloseInfo(statusCode,reason));
    }

    public void notifyError(Throwable cause)
    {
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
            LOG_OPEN.debug("[{}] {}.onOpened()",policy.getBehavior(),this.getClass().getSimpleName());
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
                notifyClose(close.getStatusCode(),close.getReason());
                try
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("{}.onSessionClosed()",containerScope.getClass().getSimpleName());
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
                        LOG.debug("{}.onSessionOpened()",containerScope.getClass().getSimpleName());
                    containerScope.onSessionOpened(this);
                }
                catch (Throwable t)
                {
                    LOG.ignore(t);
                }
                break;
        }
    }

    /**
     * Open/Activate the session
     */
    public void open()
    {
        if (LOG_OPEN.isDebugEnabled())
            LOG_OPEN.debug("[{}] {}.open()",policy.getBehavior(),this.getClass().getSimpleName());

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
            remote = new WebSocketRemoteEndpoint(connection,outgoingHandler,getBatchMode());
            if (LOG_OPEN.isDebugEnabled())
                LOG_OPEN.debug("[{}] {}.open() remote={}",policy.getBehavior(),this.getClass().getSimpleName(),remote);

            // Open WebSocket
            if (onOpenFunction != null)
                onOpenFunction.apply(this);

            // Open connection
            connection.getIOState().onOpened();

            if (LOG.isDebugEnabled())
            {
                LOG.debug("open -> {}",dump());
            }
        }
        catch (CloseException ce)
        {
            LOG.warn(ce);
            close(ce.getStatusCode(),ce.getMessage());
        }
        catch (Throwable t)
        {
            LOG.warn(t);
            // Exception on end-user WS-Endpoint.
            // Fast-fail & close connection with reason.
            int statusCode = StatusCode.SERVER_ERROR;
            if (policy.getBehavior() == WebSocketBehavior.CLIENT)
            {
                statusCode = StatusCode.POLICY_VIOLATION;
            }
            close(statusCode,t.getMessage());
        }
    }

    public void setExtensionFactory(ExtensionFactory extensionFactory)
    {
        this.extensionFactory = extensionFactory;
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

    public void setPolicy(WebSocketPolicy policy)
    {
        this.policy = policy;
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
                    this.parameterMap.put(entry.getKey(),values.toArray(new String[values.size()]));
                }
                else
                {
                    this.parameterMap.put(entry.getKey(),new String[0]);
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
        StringBuilder builder = new StringBuilder();
        builder.append("WebSocketSession[");
        builder.append("websocket=").append(endpoint.getClass().getName());
        builder.append(",behavior=").append(policy.getBehavior());
        builder.append(",connection=").append(connection);
        builder.append(",remote=").append(remote);
        builder.append(",outgoing=").append(outgoingHandler);
        builder.append("]");
        return builder.toString();
    }

    public static interface Listener
    {
        void onOpened(WebSocketSession session);

        void onClosed(WebSocketSession session);
    }
}
