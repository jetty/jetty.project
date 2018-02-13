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

import static org.eclipse.jetty.websocket.jsr356.util.InvokerUtils.Arg;
import static org.eclipse.jetty.websocket.jsr356.JavaxWebSocketFrameHandlerMetadata.MessageMetadata;

import java.io.InputStream;
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.websocket.CloseReason;
import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.common.FrameHandlerFactory;
import org.eclipse.jetty.websocket.common.HandshakeRequest;
import org.eclipse.jetty.websocket.common.HandshakeResponse;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.jsr356.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.jsr356.messages.ByteArrayMessageSink;
import org.eclipse.jetty.websocket.jsr356.messages.ByteBufferMessageSink;
import org.eclipse.jetty.websocket.jsr356.messages.DecodedBinaryMessageSink;
import org.eclipse.jetty.websocket.jsr356.messages.DecodedBinaryStreamMessageSink;
import org.eclipse.jetty.websocket.jsr356.messages.DecodedMessageSink;
import org.eclipse.jetty.websocket.jsr356.messages.DecodedTextStreamMessageSink;
import org.eclipse.jetty.websocket.jsr356.messages.DecodedTextMessageSink;
import org.eclipse.jetty.websocket.jsr356.messages.InputStreamMessageSink;
import org.eclipse.jetty.websocket.jsr356.messages.ReaderMessageSink;
import org.eclipse.jetty.websocket.jsr356.messages.StringMessageSink;
import org.eclipse.jetty.websocket.jsr356.util.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.util.InvokerUtils;
import org.eclipse.jetty.websocket.jsr356.util.ReflectUtils;

public abstract class JavaxWebSocketFrameHandlerFactory implements FrameHandlerFactory
{
    private static final AtomicLong IDGEN = new AtomicLong(0);
    protected final JavaxWebSocketContainer container;
    private Map<Class<?>, JavaxWebSocketFrameHandlerMetadata> metadataMap = new ConcurrentHashMap<>();

    public JavaxWebSocketFrameHandlerFactory(JavaxWebSocketContainer container)
    {
        this.container = container;
    }

    public JavaxWebSocketFrameHandlerMetadata getMetadata(Class<?> endpointClass)
    {
        return metadataMap.get(endpointClass);
    }

    public abstract JavaxWebSocketFrameHandlerMetadata createMetadata(Class<?> endpointClass, EndpointConfig endpointConfig);

    @Override
    public FrameHandler newFrameHandler(Object websocketPojo, WebSocketPolicy policy, HandshakeRequest handshakeRequest, HandshakeResponse handshakeResponse)
    {
        return newJavaxFrameHandler(websocketPojo, policy, handshakeRequest, handshakeResponse, new CompletableFuture<>());
    }

    public JavaxWebSocketFrameHandler newJavaxFrameHandler(Object endpointInstance, WebSocketPolicy policy, HandshakeRequest upgradeRequest, HandshakeResponse upgradeResponse, CompletableFuture<Session> futureSession)
    {
        Object endpoint;
        EndpointConfig config;

        if (endpointInstance instanceof ConfiguredEndpoint)
        {
            ConfiguredEndpoint configuredEndpoint = (ConfiguredEndpoint) endpointInstance;
            endpoint = configuredEndpoint.getRawEndpoint();
            config = configuredEndpoint.getConfig();
        }
        else
        {
            endpoint = endpointInstance;
            config = new BasicEndpointConfig();
        }

        JavaxWebSocketFrameHandlerMetadata metadata = getMetadata(endpoint.getClass());

        if (metadata == null)
        {
            metadata = createMetadata(endpoint.getClass(), config);

            if (metadata == null)
            {
                return null;
            }
        }

        WebSocketPolicy endpointPolicy = policy.clonePolicy();

        if (metadata.hasTextMetdata() && metadata.getTextMetadata().isMaxMessageSizeSet())
            endpointPolicy.setMaxTextMessageSize(metadata.getTextMetadata().maxMessageSize);
        if (metadata.hasBinaryMetdata() && metadata.getBinaryMetadata().isMaxMessageSizeSet())
            endpointPolicy.setMaxBinaryMessageSize(metadata.getBinaryMetadata().maxMessageSize);

        MethodHandle openHandle = metadata.getOpenHandle();
        MethodHandle closeHandle = metadata.getCloseHandle();
        MethodHandle errorHandle = metadata.getErrorHandle();
        MethodHandle pongHandle = metadata.getPongHandle();

        MessageMetadata textMetadata = metadata.getTextMetadata();
        MessageMetadata binaryMetadata = metadata.getBinaryMetadata();

        // TODO: handle parameterized @PathParam entries?

        openHandle = InvokerUtils.bindTo(openHandle, endpoint);
        closeHandle = InvokerUtils.bindTo(closeHandle, endpoint);
        errorHandle = InvokerUtils.bindTo(errorHandle, endpoint);
        pongHandle = InvokerUtils.bindTo(pongHandle, endpoint);

        // TODO: or handle decoders in createMessageSink?
        CompletableFuture<Session> future = futureSession;
        if (future == null)
            future = new CompletableFuture<>();

        String id = String.format("%s-%s-%d", upgradeRequest.getLocalSocketAddress(),
                upgradeRequest.getRemoteSocketAddress(), IDGEN.getAndIncrement());

        return new JavaxWebSocketFrameHandler(
                container,
                endpoint,
                endpointPolicy,
                upgradeRequest, upgradeResponse,
                openHandle, closeHandle, errorHandle,
                textMetadata, binaryMetadata,
                pongHandle,
                id,
                config,
                future);
    }

    @SuppressWarnings("Duplicates")
    public static MessageSink createMessageSink(JavaxWebSocketSession session, MessageMetadata msgMetadata)
    {
        if (msgMetadata == null)
            return null;

        try
        {
            if (DecodedMessageSink.class.isAssignableFrom(msgMetadata.sinkClass))
            {
                MethodHandle ctorHandle = MethodHandles.lookup().findConstructor(msgMetadata.sinkClass,
                        MethodType.methodType(void.class, JavaxWebSocketSession.class, msgMetadata.registeredDecoder.interfaceType, MethodHandle.class));
                Decoder decoder = session.getDecoders().getInstanceOf(msgMetadata.registeredDecoder);
                return (MessageSink) ctorHandle.invoke(session, decoder, msgMetadata.handle);
            }
            else
            {
                MethodHandle ctorHandle = MethodHandles.lookup().findConstructor(msgMetadata.sinkClass,
                        MethodType.methodType(void.class, JavaxWebSocketSession.class, MethodHandle.class));
                return (MessageSink) ctorHandle.invoke(session, msgMetadata.handle);
            }
        }
        catch (NoSuchMethodException e)
        {
            throw new RuntimeException("Missing expected MessageSink constructor found at: " + msgMetadata.sinkClass.getName(), e);
        }
        catch (IllegalAccessException | InstantiationException | InvocationTargetException e)
        {
            throw new RuntimeException("Unable to create MessageSink: " + msgMetadata.sinkClass.getName(), e);
        }
        catch (RuntimeException e)
        {
            throw e;
        }
        catch (Throwable t)
        {
            throw new RuntimeException(t);
        }
    }

    public static MethodHandle wrapNonVoidReturnType(final MethodHandle handle, JavaxWebSocketSession session) throws NoSuchMethodException, IllegalAccessException
    {
        if (handle == null)
            return null;

        if (handle.type().returnType() == Void.TYPE)
            return handle;

        MethodHandle returnFilter = MethodHandles.lookup().findVirtual(JavaxWebSocketSession.class, "filterReturnType", MethodType.methodType(Object.class, Object.class));
        returnFilter = returnFilter.bindTo(session);

        MethodHandle filteredHandle = handle.asType(handle.type().changeReturnType(Object.class));
        filteredHandle = MethodHandles.filterReturnValue(filteredHandle, returnFilter);

        return filteredHandle;
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

    protected JavaxWebSocketFrameHandlerMetadata createEndpointMetadata(Class<? extends javax.websocket.Endpoint> endpointClass, EndpointConfig endpointConfig)
    {
        JavaxWebSocketFrameHandlerMetadata metadata = new JavaxWebSocketFrameHandlerMetadata(endpointConfig);

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

    protected JavaxWebSocketFrameHandlerMetadata discoverJavaxFrameHandlerMetadata(Class<?> endpointClass, JavaxWebSocketFrameHandlerMetadata metadata)
    {
        Method onmethod;

        // OnOpen [0..1]
        onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnOpen.class);
        if (onmethod != null)
        {
            assertSignatureValid(endpointClass, onmethod, OnOpen.class);
            final Arg SESSION = new Arg(Session.class);
            final Arg ENDPOINT_CONFIG = new Arg(EndpointConfig.class);
            MethodHandle methodHandle = InvokerUtils.mutatedInvoker(endpointClass, onmethod, SESSION, ENDPOINT_CONFIG);
            metadata.setOpenHandler(methodHandle, onmethod);
        }

        // OnClose [0..1]
        onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnClose.class);
        if (onmethod != null)
        {
            assertSignatureValid(endpointClass, onmethod, OnClose.class);
            final Arg SESSION = new Arg(Session.class);
            final Arg CLOSE_REASON = new Arg(CloseReason.class);
            MethodHandle methodHandle = InvokerUtils.mutatedInvoker(endpointClass, onmethod, SESSION, CLOSE_REASON);
            metadata.setCloseHandler(methodHandle, onmethod);
        }
        // OnError [0..1]
        onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnError.class);
        if (onmethod != null)
        {
            assertSignatureValid(endpointClass, onmethod, OnError.class);
            final Arg SESSION = new Arg(Session.class);
            final Arg CAUSE = new Arg(Throwable.class).required();
            MethodHandle methodHandle = InvokerUtils.mutatedInvoker(endpointClass, onmethod, SESSION, CAUSE);
            metadata.setErrorHandler(methodHandle, onmethod);
        }

        // OnMessage [0..2]
        Method onMessages[] = ReflectUtils.findAnnotatedMethods(endpointClass, OnMessage.class);
        if (onMessages != null && onMessages.length > 0)
        {
            // The different kind of @OnWebSocketMessage method parameter signatures expected

            Arg textCallingArgs[] = new Arg[]{
                    new Arg(Session.class),
                    new Arg(String.class).required()
            };

            Arg binaryBufferCallingArgs[] = new Arg[]{
                    new Arg(Session.class),
                    new Arg(ByteBuffer.class).required()
            };

            Arg binaryArrayCallingArgs[] = new Arg[]{
                    new Arg(Session.class),
                    new Arg(byte[].class).required(),
                    new Arg(int.class), // offset
                    new Arg(int.class) // length
            };

            Arg inputStreamCallingArgs[] = new Arg[]{
                    new Arg(Session.class),
                    new Arg(InputStream.class).required()
            };

            Arg readerCallingArgs[] = new Arg[]{
                    new Arg(Session.class),
                    new Arg(Reader.class).required()
            };

            List<DecodedArgs> decodedTextCallingArgs = new ArrayList<>();
            List<DecodedArgs> decodedTextStreamCallingArgs = new ArrayList<>();
            List<DecodedArgs> decodedBinaryCallingArgs = new ArrayList<>();
            List<DecodedArgs> decodedBinaryStreamCallingArgs = new ArrayList<>();

            for (AvailableDecoders.RegisteredDecoder decoder : metadata.getAvailableDecoders())
            {
                if (decoder.implementsInterface(Decoder.Text.class))
                {
                    decodedTextCallingArgs.add(
                            new DecodedArgs(decoder,
                                    new Arg(Session.class),
                                    new Arg(decoder.objectType).required()
                            ));
                }

                if (decoder.implementsInterface(Decoder.TextStream.class))
                {
                    decodedTextStreamCallingArgs.add(
                            new DecodedArgs(decoder,
                                    new Arg(Session.class),
                                    new Arg(decoder.objectType).required()
                            ));
                }

                if (decoder.implementsInterface(Decoder.Binary.class))
                {
                    decodedBinaryCallingArgs.add(
                            new DecodedArgs(decoder,
                                    new Arg(Session.class),
                                    new Arg(decoder.objectType).required()
                            ));
                }

                if (decoder.implementsInterface(Decoder.BinaryStream.class))
                {
                    decodedBinaryStreamCallingArgs.add(
                            new DecodedArgs(decoder,
                                    new Arg(Session.class),
                                    new Arg(decoder.objectType).required()
                            ));
                }
            }

            onmessageloop:
            for (Method onMsg : onMessages)
            {
                assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                OnMessage onMessageAnno = onMsg.getAnnotation(OnMessage.class);

                MessageMetadata msgMetadata = new MessageMetadata();
                msgMetadata.maxMessageSize = onMessageAnno.maxMessageSize();

                MethodHandle methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, textCallingArgs);
                if (methodHandle != null)
                {
                    // Normal Text Message
                    assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                    msgMetadata.sinkClass = StringMessageSink.class;
                    msgMetadata.handle = methodHandle;
                    metadata.setTextMetadata(msgMetadata, onMsg);
                    continue onmessageloop;
                }

                methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, binaryBufferCallingArgs);
                if (methodHandle != null)
                {
                    // ByteBuffer Binary Message
                    assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                    msgMetadata.sinkClass = ByteBufferMessageSink.class;
                    msgMetadata.handle = methodHandle;
                    metadata.setBinaryMetadata(msgMetadata, onMsg);
                    continue onmessageloop;
                }

                methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, binaryArrayCallingArgs);
                if (methodHandle != null)
                {
                    // byte[] Binary Message
                    assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                    msgMetadata.sinkClass = ByteArrayMessageSink.class;
                    msgMetadata.handle = methodHandle;
                    metadata.setBinaryMetadata(msgMetadata, onMsg);
                    continue onmessageloop;
                }

                methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, inputStreamCallingArgs);
                if (methodHandle != null)
                {
                    // InputStream Binary Message
                    assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                    msgMetadata.sinkClass = InputStreamMessageSink.class;
                    msgMetadata.handle = methodHandle;
                    metadata.setBinaryMetadata(msgMetadata, onMsg);
                    continue onmessageloop;
                }

                methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, readerCallingArgs);
                if (methodHandle != null)
                {
                    // Reader Text Message
                    assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                    msgMetadata.sinkClass = ReaderMessageSink.class;
                    msgMetadata.handle = methodHandle;
                    metadata.setTextMetadata(msgMetadata, onMsg);
                    continue onmessageloop;
                }

                // == Decoders ==

                // Decoder.Text
                for (DecodedArgs decodedArgs : decodedTextCallingArgs)
                {
                    methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, decodedArgs.args);
                    if (methodHandle != null)
                    {
                        // Decoded Text Message
                        assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                        msgMetadata.sinkClass = DecodedTextMessageSink.class;
                        msgMetadata.handle = methodHandle;
                        msgMetadata.registeredDecoder = decodedArgs.registeredDecoder;
                        metadata.setTextMetadata(msgMetadata, onMsg);
                        continue onmessageloop;
                    }
                }

                // Decoder.Binary
                for (DecodedArgs decodedArgs : decodedBinaryCallingArgs)
                {
                    methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, decodedArgs.args);
                    if (methodHandle != null)
                    {
                        // Decoded Binary Message
                        assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                        msgMetadata.sinkClass = DecodedBinaryMessageSink.class;
                        msgMetadata.handle = methodHandle;
                        msgMetadata.registeredDecoder = decodedArgs.registeredDecoder;
                        metadata.setBinaryMetadata(msgMetadata, onMsg);
                        continue onmessageloop;
                    }
                }

                // Decoder.TextStream
                for (DecodedArgs decodedArgs : decodedTextStreamCallingArgs)
                {
                    methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, decodedArgs.args);
                    if (methodHandle != null)
                    {
                        // Decoded Text Stream
                        assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                        msgMetadata.sinkClass = DecodedTextStreamMessageSink.class;
                        msgMetadata.handle = methodHandle;
                        msgMetadata.registeredDecoder = decodedArgs.registeredDecoder;
                        metadata.setTextMetadata(msgMetadata, onMsg);
                        continue onmessageloop;
                    }
                }

                // Decoder.BinaryStream
                for (DecodedArgs decodedArgs : decodedBinaryStreamCallingArgs)
                {
                    methodHandle = InvokerUtils.optionalMutatedInvoker(endpointClass, onMsg, InvokerUtils.PARAM_IDENTITY, decodedArgs.args);
                    if (methodHandle != null)
                    {
                        // Decoded Binary Stream
                        assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                        msgMetadata.sinkClass = DecodedBinaryStreamMessageSink.class;
                        msgMetadata.handle = methodHandle;
                        msgMetadata.registeredDecoder = decodedArgs.registeredDecoder;
                        metadata.setBinaryMetadata(msgMetadata, onMsg);
                        continue onmessageloop;
                    }
                }

                // Not a valid @OnWebSocketMessage declaration signature
                throw InvalidSignatureException.build(endpointClass, OnMessage.class, onMsg);
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

        if (!OnMessage.class.isAssignableFrom(annotationClass))
        {
            StringBuilder err = new StringBuilder();
            err.append("@").append(annotationClass.getSimpleName());
            err.append(" return must be void: ");
            ReflectUtils.append(err, endpointClass, method);
            throw new InvalidSignatureException(err.toString());
        }
    }

    private static class DecodedArgs
    {
        public final AvailableDecoders.RegisteredDecoder registeredDecoder;
        public final Arg[] args;

        public DecodedArgs(AvailableDecoders.RegisteredDecoder registeredDecoder, Arg... args)
        {
            this.registeredDecoder = registeredDecoder;
            this.args = args;
        }
    }
}
