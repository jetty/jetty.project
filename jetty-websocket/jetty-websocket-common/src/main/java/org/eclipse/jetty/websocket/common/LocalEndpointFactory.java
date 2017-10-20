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

import java.io.InputStream;
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.listeners.WebSocketConnectionListener;
import org.eclipse.jetty.websocket.api.listeners.WebSocketFrameListener;
import org.eclipse.jetty.websocket.api.listeners.WebSocketListener;
import org.eclipse.jetty.websocket.api.listeners.WebSocketPartialListener;
import org.eclipse.jetty.websocket.api.listeners.WebSocketPingPongListener;
import org.eclipse.jetty.websocket.common.invoke.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.invoke.InvokerUtils;
import org.eclipse.jetty.websocket.common.message.ByteArrayMessageSink;
import org.eclipse.jetty.websocket.common.message.ByteBufferMessageSink;
import org.eclipse.jetty.websocket.common.message.InputStreamMessageSink;
import org.eclipse.jetty.websocket.common.message.PartialBinaryMessageSink;
import org.eclipse.jetty.websocket.common.message.PartialTextMessageSink;
import org.eclipse.jetty.websocket.common.message.ReaderMessageSink;
import org.eclipse.jetty.websocket.common.message.StringMessageSink;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.InvalidWebSocketException;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;

public class LocalEndpointFactory
{
    private Map<Class<?>, LocalEndpointMetadata> metadataMap = new ConcurrentHashMap<>();

    public LocalEndpointMetadata getMetadata(Class<?> endpointClass)
    {
        LocalEndpointMetadata metadata = metadataMap.get(endpointClass);

        if (metadata == null)
        {
            metadata = createMetadata(endpointClass);
            metadataMap.put(endpointClass, metadata);
        }

        return metadata;
    }

    public LocalEndpointMetadata createMetadata(Class<?> endpointClass)
    {
        if (WebSocketConnectionListener.class.isAssignableFrom(endpointClass))
        {
            return createListenerMetadata(endpointClass);
        }

        WebSocket websocket = endpointClass.getAnnotation(WebSocket.class);
        if (websocket != null)
        {
            return createAnnotatedMetadata(websocket, endpointClass);
        }

        throw new InvalidWebSocketException("Unrecognized WebSocket endpoint: " + endpointClass.getName());
    }

    public LocalEndpointImpl createLocalEndpoint(Object endpointInstance, Session session, WebSocketPolicy policy, Executor executor)
    {
        LocalEndpointMetadata metadata = getMetadata(endpointInstance.getClass());

        WebSocketPolicy endpointPolicy = policy.clonePolicy();
        if (metadata.getIdleTimeout() > 0)
        {
            endpointPolicy.setIdleTimeout(metadata.getIdleTimeout());
        }

        if (metadata.getInputBufferSize() > 0)
        {
            endpointPolicy.setInputBufferSize(metadata.getInputBufferSize());
        }

        if (metadata.getMaxBinaryMessageSize() >= -1)
        {
            endpointPolicy.setMaxBinaryMessageSize(metadata.getMaxBinaryMessageSize());
        }

        if (metadata.getMaxTextMessageSize() >= -1)
        {
            endpointPolicy.setMaxTextMessageSize(metadata.getMaxTextMessageSize());
        }

        MethodHandle openHandle = metadata.getOpenHandle();
        MethodHandle closeHandle = metadata.getCloseHandle();
        MethodHandle errorHandle = metadata.getErrorHandle();
        MethodHandle textHandle = metadata.getTextHandle();
        MethodHandle binaryHandle = metadata.getBinaryHandle();
        Class<? extends MessageSink> textSinkClass = metadata.getTextSink();
        Class<? extends MessageSink> binarySinkClass = metadata.getBinarySink();
        MethodHandle frameHandle = metadata.getFrameHandle();
        MethodHandle pingHandle = metadata.getPingHandle();
        MethodHandle pongHandle = metadata.getPongHandle();

        openHandle = bindTo(openHandle, endpointInstance, session);
        closeHandle = bindTo(closeHandle, endpointInstance, session);
        errorHandle = bindTo(errorHandle, endpointInstance, session);
        textHandle = bindTo(textHandle, endpointInstance, session);
        binaryHandle = bindTo(binaryHandle, endpointInstance, session);
        frameHandle = bindTo(frameHandle, endpointInstance, session);
        pingHandle = bindTo(pingHandle, endpointInstance, session);
        pongHandle = bindTo(pongHandle, endpointInstance, session);

        MessageSink textSink = createMessageSink(textHandle, textSinkClass, endpointPolicy, executor);
        MessageSink binarySink = createMessageSink(binaryHandle, binarySinkClass, endpointPolicy, executor);

        return new LocalEndpointImpl(
                endpointInstance,
                endpointPolicy,
                openHandle, closeHandle, errorHandle,
                textSink, binarySink,
                frameHandle, pingHandle, pongHandle);
    }

    private MessageSink createMessageSink(MethodHandle msgHandle, Class<? extends MessageSink> sinkClass, WebSocketPolicy endpointPolicy, Executor executor)
    {
        if (msgHandle == null)
            return null;
        if (sinkClass == null)
            return null;

        try
        {
            Constructor sinkConstructor = sinkClass.getConstructor(WebSocketPolicy.class, Executor.class, MethodHandle.class);
            MessageSink messageSink = (MessageSink) sinkConstructor.newInstance(endpointPolicy, executor, msgHandle);
            return messageSink;
        }
        catch (NoSuchMethodException e)
        {
            throw new RuntimeException("Missing expected MessageSink constructor found at: " + sinkClass.getName(), e);
        }
        catch (IllegalAccessException | InstantiationException | InvocationTargetException e)
        {
            throw new RuntimeException("Unable to create MessageSink: " + sinkClass.getName(), e);
        }
    }

    private MethodHandle bindTo(MethodHandle methodHandle, Object... objs)
    {
        if (methodHandle == null)
            return null;
        MethodHandle ret = methodHandle;
        for (Object obj : objs)
        {
            if (ret.type().parameterType(0).isAssignableFrom(obj.getClass()))
            {
                ret = ret.bindTo(obj);
            }
        }
        return ret;
    }

    private MethodHandle toMethodHandle(MethodHandles.Lookup lookup, Method method)
    {
        try
        {
            return lookup.unreflect(method);
        }
        catch (IllegalAccessException e)
        {
            throw new RuntimeException("Unable to access method " + method, e);
        }
    }

    private LocalEndpointMetadata createListenerMetadata(Class<?> endpointClass)
    {
        LocalEndpointMetadata metadata = new LocalEndpointMetadata();

        MethodHandles.Lookup lookup = MethodHandles.lookup();

        Method openMethod = ReflectUtils.findMethod(endpointClass, "onWebSocketConnect", Session.class);
        MethodHandle open = toMethodHandle(lookup, openMethod);
        metadata.setOpenHandler(open, openMethod);

        Method closeMethod = ReflectUtils.findMethod(endpointClass, "onWebSocketClose", int.class, String.class);
        MethodHandle close = toMethodHandle(lookup, closeMethod);
        metadata.setCloseHandler(close, closeMethod);

        Method errorMethod = ReflectUtils.findMethod(endpointClass, "onWebSocketError", Throwable.class);
        MethodHandle error = toMethodHandle(lookup, errorMethod);
        metadata.setErrorHandler(error, errorMethod);

        // Simple Data Listener
        if (WebSocketListener.class.isAssignableFrom(endpointClass))
        {
            Method textMethod = ReflectUtils.findMethod(endpointClass, "onWebSocketText", String.class);
            MethodHandle text = toMethodHandle(lookup, textMethod);
            metadata.setTextHandler(StringMessageSink.class, text, textMethod);

            Method binaryMethod = ReflectUtils.findMethod(endpointClass, "onWebSocketBinary", byte[].class, int.class, int.class);
            MethodHandle binary = toMethodHandle(lookup, binaryMethod);
            metadata.setBinaryHandle(ByteArrayMessageSink.class, binary, binaryMethod);
        }

        // Ping/Pong Listener
        if (WebSocketPingPongListener.class.isAssignableFrom(endpointClass))
        {
            Method pongMethod = ReflectUtils.findMethod(endpointClass, "onWebSocketPong", ByteBuffer.class);
            MethodHandle pong = toMethodHandle(lookup, pongMethod);
            metadata.setPongHandle(pong, pongMethod);

            Method pingMethod = ReflectUtils.findMethod(endpointClass, "onWebSocketPing", ByteBuffer.class);
            MethodHandle ping = toMethodHandle(lookup, pingMethod);
            metadata.setPingHandle(ping, pingMethod);
        }

        // Partial Data / Message Listener
        if (WebSocketPartialListener.class.isAssignableFrom(endpointClass))
        {
            Method textMethod = ReflectUtils.findMethod(endpointClass, "onWebSocketPartialText", String.class, boolean.class);
            MethodHandle text = toMethodHandle(lookup, textMethod);
            metadata.setTextHandler(PartialTextMessageSink.class, text, textMethod);

            Method binaryMethod = ReflectUtils.findMethod(endpointClass, "onWebSocketPartialBinary", ByteBuffer.class, boolean.class);
            MethodHandle binary = toMethodHandle(lookup, binaryMethod);
            metadata.setBinaryHandle(PartialBinaryMessageSink.class, binary, binaryMethod);
        }

        // Frame Listener
        if (WebSocketFrameListener.class.isAssignableFrom(endpointClass))
        {
            Method frameMethod = ReflectUtils.findMethod(endpointClass, "onWebSocketFrame", Frame.class);
            MethodHandle frame = toMethodHandle(lookup, frameMethod);
            metadata.setFrameHandler(frame, frameMethod);
        }

        return metadata;
    }

    private LocalEndpointMetadata createAnnotatedMetadata(WebSocket anno, Class<?> endpointClass)
    {
        LocalEndpointMetadata metadata = new LocalEndpointMetadata();

        metadata.setInputBufferSize(anno.inputBufferSize());
        metadata.setMaxBinaryMessageSize(anno.maxBinaryMessageSize());
        metadata.setMaxTextMessageSize(anno.maxTextMessageSize());
        metadata.setIdleTimeout(anno.maxIdleTime());
        metadata.setBatchMode(anno.batchMode());

        Method onmethod;

        // OnWebSocketConnect [0..1]
        onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnWebSocketConnect.class);
        if (onmethod != null)
        {
            assertSignatureValid(endpointClass, onmethod, OnWebSocketConnect.class);
            final InvokerUtils.Arg SESSION = new InvokerUtils.Arg(Session.class).required();
            MethodHandle methodHandle = InvokerUtils.mutatedInvoker(endpointClass, onmethod, SESSION);
            metadata.setOpenHandler(methodHandle, onmethod);
        }

        // OnWebSocketClose [0..1]
        onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnWebSocketClose.class);
        if (onmethod != null)
        {
            assertSignatureValid(endpointClass, onmethod, OnWebSocketClose.class);
            final InvokerUtils.Arg SESSION = new InvokerUtils.Arg(Session.class);
            final InvokerUtils.Arg STATUS_CODE = new InvokerUtils.Arg(int.class);
            final InvokerUtils.Arg REASON = new InvokerUtils.Arg(String.class);
            MethodHandle methodHandle = InvokerUtils.mutatedInvoker(endpointClass, onmethod, SESSION, STATUS_CODE, REASON);
            // TODO: need mutation of args? ...
            // Session + CloseInfo ->
            // setOnClose((closeInfo) ->{
            // args[0] = getSession();
            // args[1] = closeInfo.getStatusCode();
            // args[2] = closeInfo.getReason();
            // invoker.apply(endpoint, args);
            metadata.setCloseHandler(methodHandle, onmethod);
        }
        // OnWebSocketError [0..1]
        onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnWebSocketError.class);
        if (onmethod != null)
        {
            assertSignatureValid(endpointClass, onmethod, OnWebSocketError.class);
            final InvokerUtils.Arg SESSION = new InvokerUtils.Arg(Session.class);
            final InvokerUtils.Arg CAUSE = new InvokerUtils.Arg(Throwable.class).required();
            MethodHandle methodHandle = InvokerUtils.mutatedInvoker(endpointClass, onmethod, SESSION, CAUSE);
            metadata.setErrorHandler(methodHandle, onmethod);
        }

        // OnWebSocketFrame [0..1]
        onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnWebSocketFrame.class);
        if (onmethod != null)
        {
            assertSignatureValid(endpointClass, onmethod, OnWebSocketFrame.class);
            final InvokerUtils.Arg SESSION = new InvokerUtils.Arg(Session.class);
            final InvokerUtils.Arg FRAME = new InvokerUtils.Arg(Frame.class).required();
            MethodHandle methodHandle = InvokerUtils.mutatedInvoker(endpointClass, onmethod, SESSION, FRAME);
            metadata.setFrameHandler(methodHandle, onmethod);
        }

        // OnWebSocketMessage [0..2]
        Method onMessages[] = ReflectUtils.findAnnotatedMethods(endpointClass, OnWebSocketMessage.class);
        if (onMessages != null && onMessages.length > 0)
        {
            // The different kind of @OnWebSocketMessage method parameter signatures expected

            InvokerUtils.Arg textCallingArgs[] = new InvokerUtils.Arg[]{
                    new InvokerUtils.Arg(Session.class),
                    new InvokerUtils.Arg(String.class).required()
            };

            InvokerUtils.Arg binaryBufferCallingArgs[] = new InvokerUtils.Arg[]{
                    new InvokerUtils.Arg(Session.class),
                    new InvokerUtils.Arg(ByteBuffer.class).required()
            };

            InvokerUtils.Arg binaryArrayCallingArgs[] = new InvokerUtils.Arg[]{
                    new InvokerUtils.Arg(Session.class),
                    new InvokerUtils.Arg(byte[].class).required(),
                    new InvokerUtils.Arg(int.class), // offset
                    new InvokerUtils.Arg(int.class) // length
            };

            InvokerUtils.Arg inputStreamCallingArgs[] = new InvokerUtils.Arg[]{
                    new InvokerUtils.Arg(Session.class),
                    new InvokerUtils.Arg(InputStream.class).required()
            };

            InvokerUtils.Arg readerCallingArgs[] = new InvokerUtils.Arg[]{
                    new InvokerUtils.Arg(Session.class),
                    new InvokerUtils.Arg(Reader.class).required()
            };

            onmessageloop:
            for (Method onMsg : onMessages)
            {
                assertSignatureValid(endpointClass, onMsg, OnWebSocketMessage.class);

                MethodHandle methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, textCallingArgs);
                if (methodHandle != null)
                {
                    // Normal Text Message
                    assertSignatureValid(endpointClass, onMsg, OnWebSocketMessage.class);
                    metadata.setTextHandler(StringMessageSink.class, methodHandle, onMsg);
                    continue onmessageloop;
                }

                methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, binaryBufferCallingArgs);
                if (methodHandle != null)
                {
                    // ByteBuffer Binary Message
                    assertSignatureValid(endpointClass, onMsg, OnWebSocketMessage.class);
                    metadata.setBinaryHandle(ByteBufferMessageSink.class, methodHandle, onMsg);
                    continue onmessageloop;
                }

                methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, binaryArrayCallingArgs);
                if (methodHandle != null)
                {
                    // byte[] Binary Message
                    assertSignatureValid(endpointClass, onMsg, OnWebSocketMessage.class);
                    metadata.setBinaryHandle(ByteArrayMessageSink.class, methodHandle, onMsg);
                    continue onmessageloop;
                }

                methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, inputStreamCallingArgs);
                if (methodHandle != null)
                {
                    // InputStream Binary Message
                    assertSignatureValid(endpointClass, onMsg, OnWebSocketMessage.class);
                    metadata.setBinaryHandle(InputStreamMessageSink.class, methodHandle, onMsg);
                    continue onmessageloop;
                }

                methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, readerCallingArgs);
                if (methodHandle != null)
                {
                    // Reader Text Message
                    assertSignatureValid(endpointClass, onMsg, OnWebSocketMessage.class);
                    metadata.setTextHandler(ReaderMessageSink.class, methodHandle, onMsg);
                    continue onmessageloop;
                }
                else
                {
                    // Not a valid @OnWebSocketMessage declaration signature
                    throw InvalidSignatureException.build(endpointClass, OnWebSocketMessage.class, onMsg);
                }
            }
        }

        return metadata;
    }

    private void assertSignatureValid(Class<?> endpointClass, Method method, Class<? extends Annotation> annotationClass)
    {
        // Test modifiers
        int mods = method.getModifiers();
        if (!Modifier.isPublic(mods))
        {
            StringBuilder err = new StringBuilder();
            err.append("@").append(annotationClass.getSimpleName());
            err.append(" method must be public: ");
            ReflectUtils.append(err, endpointClass, method);
            throw new InvalidSignatureException(err.toString());
        }

        if (Modifier.isStatic(mods))
        {
            StringBuilder err = new StringBuilder();
            err.append("@").append(annotationClass.getSimpleName());
            err.append(" method must not be static: ");
            ReflectUtils.append(err, endpointClass, method);
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
        ReflectUtils.append(err, endpointClass, method);
        throw new InvalidSignatureException(err.toString());
    }

}
