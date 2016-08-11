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

package org.eclipse.jetty.websocket.common.function;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Function;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketConnectionListener;
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
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.ManagedEndpoint;
import org.eclipse.jetty.websocket.common.frames.ReadOnlyDelegatedFrame;
import org.eclipse.jetty.websocket.common.message.ByteArrayMessageSink;
import org.eclipse.jetty.websocket.common.message.ByteBufferMessageSink;
import org.eclipse.jetty.websocket.common.message.InputStreamMessageSink;
import org.eclipse.jetty.websocket.common.message.MessageSink;
import org.eclipse.jetty.websocket.common.message.PartialBinaryMessageSink;
import org.eclipse.jetty.websocket.common.message.PartialTextMessageSink;
import org.eclipse.jetty.websocket.common.message.ReaderMessageSink;
import org.eclipse.jetty.websocket.common.message.StringMessageSink;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;

/**
 * The Common Implementation of EndpointFunctions
 *
 * @param <T> the Session object
 */
public class CommonEndpointFunctions<T extends Session> extends AbstractLifeCycle implements EndpointFunctions<T>
{
    private static final Logger LOG = Log.getLogger(CommonEndpointFunctions.class);
    
    protected final Object endpoint;
    protected final WebSocketPolicy policy;
    protected final Executor executor;
    
    private T session;
    private Function<T, Void> onOpenFunction;
    private Function<CloseInfo, Void> onCloseFunction;
    private Function<Throwable, Void> onErrorFunction;
    private Function<Frame, Void> onFrameFunction;
    private Function<ByteBuffer, Void> onPingFunction;
    private Function<ByteBuffer, Void> onPongFunction;
    
    private MessageSink onTextSink;
    private MessageSink onBinarySink;
    
    private BatchMode batchMode;
    
    public CommonEndpointFunctions(Object endpoint, WebSocketPolicy policy, Executor executor)
    {
        Object e = endpoint;
        // unwrap endpoint
        while (e instanceof ManagedEndpoint)
            e = ((ManagedEndpoint) e).getRawEndpoint();
        
        Objects.requireNonNull(endpoint, "Endpoint cannot be null");
        Objects.requireNonNull(policy, "WebSocketPolicy cannot be null");
        Objects.requireNonNull(executor, "Executor cannot be null");
        this.endpoint = e;
        this.policy = policy;
        this.executor = executor;
    }
    
    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        discoverEndpointFunctions(this.endpoint);
    }
    
    protected void discoverEndpointFunctions(Object endpoint)
    {
        boolean supportAnnotations = true;
        
        // Connection Listener
        if (endpoint instanceof WebSocketConnectionListener)
        {
            WebSocketConnectionListener listener = (WebSocketConnectionListener) endpoint;
            setOnOpen((session) ->
                    {
                        listener.onWebSocketConnect(session);
                        return null;
                    },
                    ReflectUtils.findMethod(endpoint.getClass(), "onWebSocketConnect", Session.class)
            );
            setOnClose((close) ->
                    {
                        listener.onWebSocketClose(close.getStatusCode(), close.getReason());
                        return null;
                    },
                    ReflectUtils.findMethod(endpoint.getClass(), "onWebSocketClose", int.class, String.class)
            );
            setOnError((cause) ->
                    {
                        listener.onWebSocketError(cause);
                        return null;
                    },
                    ReflectUtils.findMethod(endpoint.getClass(), "onWebSocketError", Throwable.class));
            supportAnnotations = false;
        }
        
        // Simple Data Listener
        if (endpoint instanceof WebSocketListener)
        {
            WebSocketListener listener = (WebSocketListener) endpoint;
            
            setOnText(new StringMessageSink(policy, (payload) ->
                    {
                        listener.onWebSocketText(payload);
                        return null;
                    }),
                    ReflectUtils.findMethod(endpoint.getClass(), "onWebSocketText", String.class));
            setOnBinary(new ByteArrayMessageSink(policy, (payload) ->
                    {
                        listener.onWebSocketBinary(payload, 0, payload.length);
                        return null;
                    }),
                    ReflectUtils.findMethod(endpoint.getClass(), "onWebSocketBinary", byte[].class, int.class, int.class));
            supportAnnotations = false;
        }
        
        // Ping/Pong Listener
        if (endpoint instanceof WebSocketPingPongListener)
        {
            WebSocketPingPongListener listener = (WebSocketPingPongListener) endpoint;
            setOnPong((pong) ->
                    {
                        ByteBuffer payload = pong;
                        if (pong == null)
                            payload = BufferUtil.EMPTY_BUFFER;
                        listener.onWebSocketPong(payload);
                        return null;
                    },
                    ReflectUtils.findMethod(endpoint.getClass(), "onWebSocketPong", ByteBuffer.class));
            setOnPing((ping) ->
                    {
                        ByteBuffer payload = ping;
                        if (ping == null)
                            payload = BufferUtil.EMPTY_BUFFER;
                        listener.onWebSocketPing(payload);
                        return null;
                    },
                    ReflectUtils.findMethod(endpoint.getClass(), "onWebSocketPing", ByteBuffer.class));
            supportAnnotations = false;
        }
        
        // Partial Data / Message Listener
        if (endpoint instanceof WebSocketPartialListener)
        {
            WebSocketPartialListener listener = (WebSocketPartialListener) endpoint;
            setOnText(new PartialTextMessageSink((partial) ->
                    {
                        listener.onWebSocketPartialText(partial.getPayload(), partial.isFin());
                        return null;
                    }),
                    ReflectUtils.findMethod(endpoint.getClass(), "onWebSocketPartialText", String.class, boolean.class));
            setOnBinary(new PartialBinaryMessageSink((partial) ->
                    {
                        listener.onWebSocketPartialBinary(partial.getPayload(), partial.isFin());
                        return null;
                    }),
                    ReflectUtils.findMethod(endpoint.getClass(), "onWebSocketPartialBinary", ByteBuffer.class, boolean.class));
            supportAnnotations = false;
        }
        
        // Frame Listener
        if (endpoint instanceof WebSocketFrameListener)
        {
            WebSocketFrameListener listener = (WebSocketFrameListener) endpoint;
            setOnFrame((frame) ->
                    {
                        listener.onWebSocketFrame(new ReadOnlyDelegatedFrame(frame));
                        return null;
                    },
                    ReflectUtils.findMethod(endpoint.getClass(), "onWebSocketFrame", Frame.class));
            supportAnnotations = false;
        }
        
        if (supportAnnotations)
            discoverAnnotatedEndpointFunctions(endpoint);
    }
    
    protected void discoverAnnotatedEndpointFunctions(Object endpoint)
    {
        // Test for annotated websocket endpoint
        
        Class<?> endpointClass = endpoint.getClass();
        WebSocket websocket = endpointClass.getAnnotation(WebSocket.class);
        if (websocket != null)
        {
            policy.setInputBufferSize(websocket.inputBufferSize());
            policy.setMaxBinaryMessageSize(websocket.maxBinaryMessageSize());
            policy.setMaxTextMessageSize(websocket.maxTextMessageSize());
            policy.setIdleTimeout(websocket.maxIdleTime());
            
            this.batchMode = websocket.batchMode();
            
            Method onmethod = null;
            
            // OnWebSocketConnect [0..1]
            onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnWebSocketConnect.class);
            if (onmethod != null)
            {
                setOnOpen(new OnOpenFunction(endpoint, onmethod), onmethod);
            }
            // OnWebSocketClose [0..1]
            onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnWebSocketClose.class);
            if (onmethod != null)
            {
                setOnClose(new OnCloseFunction(session, endpoint, onmethod), onmethod);
            }
            // OnWebSocketError [0..1]
            onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnWebSocketError.class);
            if (onmethod != null)
            {
                setOnError(new OnErrorFunction(session, endpoint, onmethod), onmethod);
            }
            // OnWebSocketFrame [0..1]
            onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnWebSocketFrame.class);
            if (onmethod != null)
            {
                setOnFrame(new OnFrameFunction(session, endpoint, onmethod), onmethod);
            }
            // OnWebSocketMessage [0..2]
            Method onmessages[] = ReflectUtils.findAnnotatedMethods(endpointClass, OnWebSocketMessage.class);
            if (onmessages != null && onmessages.length > 0)
            {
                for (Method onmsg : onmessages)
                {
                    if (OnTextFunction.hasMatchingSignature(onmsg))
                    {
                        // Normal Text Message
                        setOnText(new StringMessageSink(policy, new OnTextFunction(session, endpoint, onmsg)), onmsg);
                    }
                    else if (OnByteBufferFunction.hasMatchingSignature(onmsg))
                    {
                        // ByteBuffer Binary Message
                        setOnBinary(new ByteBufferMessageSink(policy, new OnByteBufferFunction(session, endpoint, onmsg)), onmsg);
                    }
                    else if (OnByteArrayFunction.hasMatchingSignature(onmsg))
                    {
                        // byte[] Binary Message
                        setOnBinary(new ByteArrayMessageSink(policy, new OnByteArrayFunction(session, endpoint, onmsg)), onmsg);
                    }
                    else if (OnInputStreamFunction.hasMatchingSignature(onmsg))
                    {
                        // InputStream Binary Message
                        setOnBinary(new InputStreamMessageSink(executor, new OnInputStreamFunction(session, endpoint, onmsg)), onmsg);
                    }
                    else if (OnReaderFunction.hasMatchingSignature(onmsg))
                    {
                        // Reader Text Message
                        setOnText(new ReaderMessageSink(executor, new OnReaderFunction(session, endpoint, onmsg)), onmsg);
                    }
                    else
                    {
                        // Not a valid @OnWebSocketMessage declaration signature
                        throw InvalidSignatureException.build(onmsg, OnWebSocketMessage.class,
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
    
    public BatchMode getBatchMode()
    {
        return batchMode;
    }
    
    public T getSession()
    {
        return session;
    }
    
    public void setOnOpen(Function<T, Void> function, Object origin)
    {
        assertNotSet(this.onOpenFunction, "Open Handler", origin);
        this.onOpenFunction = function;
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Assigned onOpen to " + describeOrigin(origin));
        }
    }
    
    public void setOnClose(Function<CloseInfo, Void> function, Object origin)
    {
        assertNotSet(this.onCloseFunction, "Close Handler", origin);
        this.onCloseFunction = function;
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Assigned onClose to " + describeOrigin(origin));
        }
    }
    
    public void setOnError(Function<Throwable, Void> function, Object origin)
    {
        assertNotSet(this.onErrorFunction, "Error Handler", origin);
        this.onErrorFunction = function;
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Assigned onError to " + describeOrigin(origin));
        }
    }
    
    public void setOnText(MessageSink messageSink, Object origin)
    {
        assertNotSet(this.onTextSink, "TEXT Handler", origin);
        this.onTextSink = messageSink;
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Assigned onText to " + describeOrigin(origin));
        }
    }
    
    public void setOnBinary(MessageSink messageSink, Object origin)
    {
        assertNotSet(this.onBinarySink, "BINARY Handler", origin);
        this.onBinarySink = messageSink;
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Assigned onBinary to " + describeOrigin(origin));
        }
    }
    
    public void setOnFrame(Function<Frame, Void> function, Object origin)
    {
        assertNotSet(this.onFrameFunction, "Frame Handler", origin);
        this.onFrameFunction = function;
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Assigned onFrame to " + describeOrigin(origin));
        }
    }
    
    public void setOnPing(Function<ByteBuffer, Void> function, Object origin)
    {
        assertNotSet(this.onPingFunction, "Ping Handler", origin);
        this.onPingFunction = function;
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Assigned onPing to " + describeOrigin(origin));
        }
    }
    
    public void setOnPong(Function<ByteBuffer, Void> function, Object origin)
    {
        assertNotSet(this.onPongFunction, "Pong Handler", origin);
        this.onPongFunction = function;
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Assigned onPong to " + describeOrigin(origin));
        }
    }
    
    public boolean hasBinarySink()
    {
        return this.onBinarySink != null;
    }
    
    public boolean hasTextSink()
    {
        return this.onTextSink != null;
    }
    
    private String describeOrigin(Object obj)
    {
        if (obj == null)
        {
            return "<undefined>";
        }
        
        return obj.toString();
    }
    
    protected void assertNotSet(Object val, String role, Object origin)
    {
        if (val == null)
            return;
        
        StringBuilder err = new StringBuilder();
        err.append("Cannot replace previously assigned ");
        err.append(role);
        err.append(" with ");
        err.append(describeOrigin(origin));
        
        throw new InvalidWebSocketException(err.toString());
    }
    
    @Override
    public void onOpen(T session)
    {
        if (!isStarted())
            throw new IllegalStateException(this.getClass().getName() + " not started");
        
        this.session = session;
        
        if (onOpenFunction != null)
            onOpenFunction.apply(this.session);
    }
    
    @Override
    public void onClose(CloseInfo close)
    {
        if (!isStarted())
            throw new IllegalStateException(this.getClass().getName() + " not started");
        
        if (onCloseFunction != null)
            onCloseFunction.apply(close);
    }
    
    @Override
    public void onFrame(Frame frame)
    {
        if (!isStarted())
            throw new IllegalStateException(this.getClass().getName() + " not started");
        
        if (onFrameFunction != null)
            onFrameFunction.apply(frame);
    }
    
    @Override
    public void onError(Throwable cause)
    {
        if (!isStarted())
            throw new IllegalStateException(this.getClass().getName() + " not started");
        
        if (onErrorFunction != null)
            onErrorFunction.apply(cause);
        else
            LOG.debug(cause);
    }
    
    @Override
    public void onText(ByteBuffer payload, boolean fin)
    {
        if (!isStarted())
            throw new IllegalStateException(this.getClass().getName() + " not started");
        
        if (onTextSink != null)
            onTextSink.accept(payload, fin);
    }
    
    @Override
    public void onBinary(ByteBuffer payload, boolean fin)
    {
        if (!isStarted())
            throw new IllegalStateException(this.getClass().getName() + " not started");
        
        if (onBinarySink != null)
            onBinarySink.accept(payload, fin);
    }
    
    @Override
    public void onPing(ByteBuffer payload)
    {
        if (!isStarted())
            throw new IllegalStateException(this.getClass().getName() + " not started");
        
        if (onPingFunction != null)
            onPingFunction.apply(payload);
    }
    
    @Override
    public void onPong(ByteBuffer payload)
    {
        if (!isStarted())
            throw new IllegalStateException(this.getClass().getName() + " not started");
        
        if (onPongFunction != null)
            onPongFunction.apply(payload);
    }
}
