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

import java.io.InputStream;
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
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
import org.eclipse.jetty.websocket.common.reflect.Arg;
import org.eclipse.jetty.websocket.common.reflect.UnorderedSignature;
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
    private MessageSink activeMessageSink;
    
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
                final Arg SESSION = new Arg(Session.class).required();
                UnorderedSignature sig = new UnorderedSignature(SESSION);
                if (sig.test(onmethod))
                {
                    assertSignatureValid(onmethod, OnWebSocketConnect.class);
                    BiFunction<Object, Object[], Object> invoker = sig.newFunction(onmethod);
                    final Object[] args = new Object[1];
                    setOnOpen((newSession) ->
                    {
                        args[0] = newSession;
                        invoker.apply(endpoint, args);
                        return null;
                    }, onmethod);
                }
            }
            // OnWebSocketClose [0..1]
            onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnWebSocketClose.class);
            if (onmethod != null)
            {
                final Arg SESSION = new Arg(Session.class);
                final Arg STATUS_CODE = new Arg(int.class);
                final Arg REASON = new Arg(String.class);
                UnorderedSignature sig = new UnorderedSignature(SESSION, STATUS_CODE, REASON);
                if (sig.test(onmethod))
                {
                    assertSignatureValid(onmethod, OnWebSocketClose.class);
                    BiFunction<Object, Object[], Object> invoker = sig.newFunction(onmethod);
                    final Object[] args = new Object[3];
                    setOnClose((closeInfo) ->
                    {
                        args[0] = getSession();
                        args[1] = closeInfo.getStatusCode();
                        args[2] = closeInfo.getReason();
                        invoker.apply(endpoint, args);
                        return null;
                    }, onmethod);
                }
            }
            // OnWebSocketError [0..1]
            onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnWebSocketError.class);
            if (onmethod != null)
            {
                final Arg SESSION = new Arg(Session.class);
                final Arg CAUSE = new Arg(Throwable.class).required();
                UnorderedSignature sig = new UnorderedSignature(SESSION, CAUSE);
                if(sig.test(onmethod))
                {
                    assertSignatureValid(onmethod, OnWebSocketError.class);
                    BiFunction<Object,Object[],Object> invoker = sig.newFunction(onmethod);
                    final Object[] args = new Object[2];
                    setOnError((throwable) -> {
                        args[0] = getSession();
                        args[1] = throwable;
                        invoker.apply(endpoint, args);
                        return null;
                    }, onmethod);
                }
            }
            // OnWebSocketFrame [0..1]
            onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnWebSocketFrame.class);
            if (onmethod != null)
            {
                final Arg SESSION = new Arg(Session.class);
                final Arg FRAME = new Arg(Frame.class).required();
                UnorderedSignature sig = new UnorderedSignature(SESSION, FRAME);
                if(sig.test(onmethod))
                {
                    assertSignatureValid(onmethod, OnWebSocketFrame.class);
                    BiFunction<Object,Object[],Object> invoker = sig.newFunction(onmethod);
                    final Object[] args = new Object[2];
                    setOnFrame((frame) -> {
                        args[0] = getSession();
                        args[1] = frame;
                        invoker.apply(endpoint, args);
                        return null;
                    }, onmethod);
                }
            }
            // OnWebSocketMessage [0..2]
            Method onMessages[] = ReflectUtils.findAnnotatedMethods(endpointClass, OnWebSocketMessage.class);
            if (onMessages != null && onMessages.length > 0)
            {
                Arg SESSION = new Arg(Session.class);
                
                Arg TEXT = new Arg(String.class).required();
                UnorderedSignature sigText = new UnorderedSignature(SESSION, TEXT);
                
                Arg BYTE_BUFFER = new Arg(ByteBuffer.class).required();
                UnorderedSignature sigBinaryBuffer = new UnorderedSignature(SESSION, BYTE_BUFFER);
                
                Arg BYTE_ARRAY = new Arg(byte[].class).required();
                Arg OFFSET = new Arg(int.class);
                Arg LENGTH = new Arg(int.class);
                UnorderedSignature sigBinaryArray = new UnorderedSignature(SESSION, BYTE_ARRAY, OFFSET, LENGTH);
                
                Arg INPUT_STREAM = new Arg(InputStream.class).required();
                UnorderedSignature sigInputStream = new UnorderedSignature(SESSION, INPUT_STREAM);
                
                Arg READER = new Arg(Reader.class).required();
                UnorderedSignature sigReader = new UnorderedSignature(SESSION, READER);
                
                for (Method onMsg : onMessages)
                {
                    if(sigText.test(onMsg))
                    {
                        // Normal Text Message
                        assertSignatureValid(onMsg, OnWebSocketMessage.class);
                        BiFunction<Object, Object[], Object> invoker = sigText.newFunction(onMsg);
                        final Object[] args = new Object[2];
                        StringMessageSink messageSink = new StringMessageSink(policy,
                                (msg) ->
                                {
                                    args[0] = getSession();
                                    args[1] = msg;
                                    invoker.apply(endpoint, args);
                                    return null;
                                });
                        setOnText(messageSink, onMsg);
                    }
                    else if (sigBinaryBuffer.test(onMsg))
                    {
                        // ByteBuffer Binary Message
                        assertSignatureValid(onMsg, OnWebSocketMessage.class);
                        BiFunction<Object, Object[], Object> invoker = sigBinaryBuffer.newFunction(onMsg);
                        final Object[] args = new Object[2];
                        ByteBufferMessageSink messageSink = new ByteBufferMessageSink(policy,
                                (buffer) ->
                                {
                                    args[0] = getSession();
                                    args[1] = buffer;
                                    invoker.apply(endpoint, args);
                                    return null;
                                });
                        setOnBinary(messageSink, onMsg);
                    }
                    else if (sigBinaryArray.test(onMsg))
                    {
                        // byte[] Binary Message
                        assertSignatureValid(onMsg, OnWebSocketMessage.class);
                        BiFunction<Object, Object[], Object> invoker = sigBinaryArray.newFunction(onMsg);
                        final Object[] args = new Object[4];
                        ByteArrayMessageSink messageSink = new ByteArrayMessageSink(policy,
                                (buffer) ->
                                {
                                    args[0] = getSession();
                                    args[1] = buffer;
                                    args[2] = 0;
                                    args[3] = buffer.length;
                                    invoker.apply(endpoint, args);
                                    return null;
                                });
                        setOnBinary(messageSink, onMsg);
                    }
                    else if (sigInputStream.test(onMsg))
                    {
                        // InputStream Binary Message
                        assertSignatureValid(onMsg, OnWebSocketMessage.class);
                        BiFunction<Object, Object[], Object> invoker = sigInputStream.newFunction(onMsg);
                        final Object[] args = new Object[2];
                        InputStreamMessageSink messageSink = new InputStreamMessageSink(executor,
                                (stream) ->
                                {
                                    args[0] = getSession();
                                    args[1] = stream;
                                    invoker.apply(endpoint, args);
                                    return null;
                                });
                        setOnBinary(messageSink, onMsg);
                    }
                    else if (sigReader.test(onMsg))
                    {
                        // Reader Text Message
                        assertSignatureValid(onMsg, OnWebSocketMessage.class);
                        BiFunction<Object, Object[], Object> invoker = sigReader.newFunction(onMsg);
                        final Object[] args = new Object[2];
                        ReaderMessageSink messageSink = new ReaderMessageSink(executor,
                                (reader) ->
                                {
                                    args[0] = getSession();
                                    args[1] = reader;
                                    invoker.apply(endpoint, args);
                                    return null;
                                });
                        setOnText(messageSink, onMsg);
                    }
                    else
                    {
                        // Not a valid @OnWebSocketMessage declaration signature
                        throw InvalidSignatureException.build(endpoint.getClass(), OnWebSocketMessage.class, onMsg);
                    }
                }
            }
        }
    }
    
    private void assertSignatureValid(Method method, Class<? extends Annotation> annotationClass)
    {
        // Test modifiers
        int mods = method.getModifiers();
        if (!Modifier.isPublic(mods))
        {
            StringBuilder err = new StringBuilder();
            err.append("@").append(annotationClass.getSimpleName());
            err.append(" method must be public: ");
            ReflectUtils.append(err, endpoint.getClass(), method);
            throw new InvalidSignatureException(err.toString());
        }
        
        if (Modifier.isStatic(mods))
        {
            StringBuilder err = new StringBuilder();
            err.append("@").append(annotationClass.getSimpleName());
            err.append(" method must not be static: ");
            ReflectUtils.append(err, endpoint.getClass(), method);
            throw new InvalidSignatureException(err.toString());
        }
        
        // Test return type
        Class<?> returnType = method.getReturnType();
        if ((returnType == Void.TYPE) || (returnType == Void.class))
        {
            // Void is 100% valid, always
            return;
        }
        
        StringBuilder err = new StringBuilder();
        err.append("@").append(annotationClass.getSimpleName());
        err.append(" return must be void: ");
        ReflectUtils.append(err, endpoint.getClass(), method);
        throw new InvalidSignatureException(err.toString());
    }
    
    protected void clearOnPongFunction()
    {
        
    }
    
    protected void clearOnTextSink()
    {
        
    }
    
    protected void clearOnBinarySink()
    {
        
    }
    
    public BatchMode getBatchMode()
    {
        return batchMode;
    }
    
    public Executor getExecutor()
    {
        return executor;
    }
    
    public T getSession()
    {
        return session;
    }
    
    protected MessageSink getOnTextSink()
    {
        return onTextSink;
    }
    
    protected MessageSink getOnBinarySink()
    {
        return onBinarySink;
    }
    
    protected Function<ByteBuffer, Void> getOnPongFunction()
    {
        return onPongFunction;
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
    
    public boolean hasOnOpen()
    {
        return this.onOpenFunction != null;
    }
    
    public boolean hasOnClose()
    {
        return this.onCloseFunction != null;
    }
    
    public boolean hasOnError()
    {
        return this.onErrorFunction != null;
    }
    
    public boolean hasOnFrame()
    {
        return this.onFrameFunction != null;
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
        assertIsStarted();
        
        this.session = session;
        
        if (onOpenFunction != null)
            onOpenFunction.apply(this.session);
    }
    
    @Override
    public void onClose(CloseInfo close)
    {
        assertIsStarted();
        
        if (onCloseFunction != null)
            onCloseFunction.apply(close);
    }
    
    @Override
    public void onFrame(Frame frame)
    {
        assertIsStarted();
        
        if (onFrameFunction != null)
            onFrameFunction.apply(frame);
    }
    
    @Override
    public void onError(Throwable cause)
    {
        assertIsStarted();
        
        if (onErrorFunction != null)
            onErrorFunction.apply(cause);
        else
            LOG.debug(cause);
    }
    
    @Override
    public void onText(ByteBuffer payload, boolean fin)
    {
        assertIsStarted();
        
        if (activeMessageSink == null)
            activeMessageSink = onTextSink;
        
        acceptMessage(payload, fin);
    }
    
    @Override
    public void onBinary(ByteBuffer payload, boolean fin)
    {
        assertIsStarted();
        
        if (activeMessageSink == null)
            activeMessageSink = onBinarySink;
        
        acceptMessage(payload, fin);
    }
    
    @Override
    public void onContinuation(ByteBuffer payload, boolean fin)
    {
        acceptMessage(payload, fin);
    }
    
    private void acceptMessage(ByteBuffer payload, boolean fin)
    {
        // No message sink is active
        if (activeMessageSink == null)
            return;
        
        // Accept the payload into the message sink
        activeMessageSink.accept(payload, fin);
        if (fin)
            activeMessageSink = null;
    }
    
    @Override
    public void onPing(ByteBuffer payload)
    {
        assertIsStarted();
        
        if (onPingFunction != null)
            onPingFunction.apply(payload);
    }
    
    @Override
    public void onPong(ByteBuffer payload)
    {
        assertIsStarted();
        
        if (onPongFunction != null)
            onPongFunction.apply(payload);
    }
    
    protected void assertIsStarted()
    {
        if (!isStarted())
            throw new IllegalStateException(this.getClass().getName() + " not started");
    }
}
