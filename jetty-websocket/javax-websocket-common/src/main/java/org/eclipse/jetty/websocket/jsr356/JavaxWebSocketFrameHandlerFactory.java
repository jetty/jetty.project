//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356;

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

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.core.InvalidWebSocketException;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.jsr356.messages.ByteArrayMessageSink;
import org.eclipse.jetty.websocket.jsr356.messages.ByteBufferMessageSink;
import org.eclipse.jetty.websocket.jsr356.messages.InputStreamMessageSink;
import org.eclipse.jetty.websocket.jsr356.messages.ReaderMessageSink;
import org.eclipse.jetty.websocket.jsr356.messages.StringMessageSink;
import org.eclipse.jetty.websocket.jsr356.util.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.util.InvokerUtils;
import org.eclipse.jetty.websocket.jsr356.util.ReflectUtils;

public class JavaxWebSocketFrameHandlerFactory
{
    private Map<Class<?>, JavaxWebSocketFrameHandlerMetadata> metadataMap = new ConcurrentHashMap<>();

    public JavaxWebSocketFrameHandlerMetadata getMetadata(Class<?> endpointClass)
    {
        JavaxWebSocketFrameHandlerMetadata metadata = metadataMap.get(endpointClass);

        if (metadata == null)
        {
            metadata = createMetadata(endpointClass);
            metadataMap.put(endpointClass, metadata);
        }

        return metadata;
    }

    public JavaxWebSocketFrameHandlerMetadata createMetadata(Class<?> endpointClass)
    {
        if (javax.websocket.Endpoint.class.isAssignableFrom(endpointClass))
        {
            return createEndpointMetadata(endpointClass);
        }

        ClientEndpoint websocket = endpointClass.getAnnotation(javax.websocket.ClientEndpoint.class);
        if (websocket != null)
        {
            return createClientEndpointMetadata(websocket, endpointClass);
        }

        throw new InvalidWebSocketException("Unrecognized WebSocket endpoint: " + endpointClass.getName());
    }

    public JavaxWebSocketFrameHandlerImpl createLocalEndpoint(Object endpointInstance, JavaxWebSocketSession session, WebSocketPolicy policy, Executor executor)
    {
        Object endpoint;
        EndpointConfig config;

        if(endpointInstance instanceof ConfiguredEndpoint)
        {
            ConfiguredEndpoint configuredEndpoint = (ConfiguredEndpoint) endpointInstance;
            endpoint = configuredEndpoint.getRawEndpoint();
            config = configuredEndpoint.getConfig();
        } else
        {
            endpoint = endpointInstance;
            config = new BasicEndpointConfig();
        }

        JavaxWebSocketFrameHandlerMetadata metadata = getMetadata(endpoint.getClass());

        WebSocketPolicy endpointPolicy = policy.clonePolicy();

        // TODO: encoders?
        // TODO: decoders?
        // TODO: subprotocols?
        // TODO: configurators?

        MethodHandle openHandle = metadata.getOpenHandle();
        MethodHandle closeHandle = metadata.getCloseHandle();
        MethodHandle errorHandle = metadata.getErrorHandle();
        MethodHandle textHandle = metadata.getTextHandle();
        MethodHandle binaryHandle = metadata.getBinaryHandle();
        Class<? extends MessageSink> textSinkClass = metadata.getTextSink();
        Class<? extends MessageSink> binarySinkClass = metadata.getBinarySink();
        MethodHandle pongHandle = metadata.getPongHandle();

        // TODO: handle decoders in bindTo steps?
        // TODO: handle parameterized @PathParam entries?

        openHandle = bindTo(openHandle, endpoint, session);
        closeHandle = bindTo(closeHandle, endpoint, session);
        errorHandle = bindTo(errorHandle, endpoint, session);
        textHandle = bindTo(textHandle, endpoint, session);
        binaryHandle = bindTo(binaryHandle, endpoint, session);
        pongHandle = bindTo(pongHandle, endpoint, session);

        // TODO: or handle decoders in createMessageSink?

        MessageSink textSink = createMessageSink(textHandle, textSinkClass, endpointPolicy, executor);
        MessageSink binarySink = createMessageSink(binaryHandle, binarySinkClass, endpointPolicy, executor);

        return new JavaxWebSocketFrameHandlerImpl(
                endpoint,
                endpointPolicy,
                openHandle, closeHandle, errorHandle,
                textSink, binarySink,
                pongHandle);
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

    private JavaxWebSocketFrameHandlerMetadata createEndpointMetadata(Class<?> endpointClass)
    {
        JavaxWebSocketFrameHandlerMetadata metadata = new JavaxWebSocketFrameHandlerMetadata();

        MethodHandles.Lookup lookup = MethodHandles.lookup();

        Method openMethod = ReflectUtils.findMethod(endpointClass, "onOpen",
                javax.websocket.Session.class, javax.websocket.EndpointConfig.class);
        MethodHandle open = toMethodHandle(lookup, openMethod);
        metadata.setOpenHandler(open, openMethod);

        Method closeMethod = ReflectUtils.findMethod(endpointClass, "onClose",
                javax.websocket.Session.class, javax.websocket.CloseReason.class);
        MethodHandle close = toMethodHandle(lookup, closeMethod);
        metadata.setCloseHandler(close, closeMethod);

        Method errorMethod = ReflectUtils.findMethod(endpointClass, "onError",
                javax.websocket.Session.class, Throwable.class);
        MethodHandle error = toMethodHandle(lookup, errorMethod);
        metadata.setErrorHandler(error, errorMethod);

        return metadata;
    }

    private JavaxWebSocketFrameHandlerMetadata createClientEndpointMetadata(javax.websocket.ClientEndpoint anno, Class<?> endpointClass)
    {
        JavaxWebSocketFrameHandlerMetadata metadata = new JavaxWebSocketFrameHandlerMetadata();

        metadata.setClientConfigurator(anno.configurator());
        metadata.setDecoders(anno.decoders());
        metadata.setEncoders(anno.encoders());
        metadata.setSubProtocols(anno.subprotocols());

        Method onmethod;

        // OnOpen [0..1]
        onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnOpen.class);
        if (onmethod != null)
        {
            assertSignatureValid(endpointClass, onmethod, OnOpen.class);
            final InvokerUtils.Arg SESSION = new InvokerUtils.Arg(Session.class);
            MethodHandle methodHandle = InvokerUtils.mutatedInvoker(endpointClass, onmethod, SESSION);
            metadata.setOpenHandler(methodHandle, onmethod);
        }

        // OnClose [0..1]
        onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnClose.class);
        if (onmethod != null)
        {
            assertSignatureValid(endpointClass, onmethod, OnClose.class);
            final InvokerUtils.Arg SESSION = new InvokerUtils.Arg(Session.class);
            final InvokerUtils.Arg CLOSE_REASON = new InvokerUtils.Arg(CloseReason.class);
            MethodHandle methodHandle = InvokerUtils.mutatedInvoker(endpointClass, onmethod, SESSION, CLOSE_REASON);
            metadata.setCloseHandler(methodHandle, onmethod);
        }
        // OnError [0..1]
        onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnError.class);
        if (onmethod != null)
        {
            assertSignatureValid(endpointClass, onmethod, OnError.class);
            final InvokerUtils.Arg SESSION = new InvokerUtils.Arg(Session.class);
            final InvokerUtils.Arg CAUSE = new InvokerUtils.Arg(Throwable.class).required();
            MethodHandle methodHandle = InvokerUtils.mutatedInvoker(endpointClass, onmethod, SESSION, CAUSE);
            metadata.setErrorHandler(methodHandle, onmethod);
        }

        // OnMessage [0..2]
        Method onMessages[] = ReflectUtils.findAnnotatedMethods(endpointClass, OnMessage.class);
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

            // TODO: match on decoded types?

            onmessageloop:
            for (Method onMsg : onMessages)
            {
                assertSignatureValid(endpointClass, onMsg, OnMessage.class);

                MethodHandle methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, textCallingArgs);
                if (methodHandle != null)
                {
                    // Normal Text Message
                    assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                    metadata.setTextHandler(StringMessageSink.class, methodHandle, onMsg);
                    continue onmessageloop;
                }

                methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, binaryBufferCallingArgs);
                if (methodHandle != null)
                {
                    // ByteBuffer Binary Message
                    assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                    metadata.setBinaryHandle(ByteBufferMessageSink.class, methodHandle, onMsg);
                    continue onmessageloop;
                }

                methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, binaryArrayCallingArgs);
                if (methodHandle != null)
                {
                    // byte[] Binary Message
                    assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                    metadata.setBinaryHandle(ByteArrayMessageSink.class, methodHandle, onMsg);
                    continue onmessageloop;
                }

                methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, inputStreamCallingArgs);
                if (methodHandle != null)
                {
                    // InputStream Binary Message
                    assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                    metadata.setBinaryHandle(InputStreamMessageSink.class, methodHandle, onMsg);
                    continue onmessageloop;
                }

                methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, readerCallingArgs);
                if (methodHandle != null)
                {
                    // Reader Text Message
                    assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                    metadata.setTextHandler(ReaderMessageSink.class, methodHandle, onMsg);
                    continue onmessageloop;
                }
                else
                {
                    // Not a valid @OnWebSocketMessage declaration signature
                    throw InvalidSignatureException.build(endpointClass, OnMessage.class, onMsg);
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
