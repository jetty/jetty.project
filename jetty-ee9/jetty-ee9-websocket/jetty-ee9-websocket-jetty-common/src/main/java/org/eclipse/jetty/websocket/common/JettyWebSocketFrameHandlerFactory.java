//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.common;

import java.io.IOException;
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
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.Frame;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketConnectionListener;
import org.eclipse.jetty.websocket.api.WebSocketContainer;
import org.eclipse.jetty.websocket.api.WebSocketFrameListener;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.api.WebSocketPartialListener;
import org.eclipse.jetty.websocket.api.WebSocketPingPongListener;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.exception.InvalidSignatureException;
import org.eclipse.jetty.websocket.core.exception.InvalidWebSocketException;
import org.eclipse.jetty.websocket.core.internal.messages.ByteArrayMessageSink;
import org.eclipse.jetty.websocket.core.internal.messages.ByteBufferMessageSink;
import org.eclipse.jetty.websocket.core.internal.messages.InputStreamMessageSink;
import org.eclipse.jetty.websocket.core.internal.messages.MessageSink;
import org.eclipse.jetty.websocket.core.internal.messages.PartialByteBufferMessageSink;
import org.eclipse.jetty.websocket.core.internal.messages.PartialStringMessageSink;
import org.eclipse.jetty.websocket.core.internal.messages.ReaderMessageSink;
import org.eclipse.jetty.websocket.core.internal.messages.StringMessageSink;
import org.eclipse.jetty.websocket.core.internal.util.InvokerUtils;
import org.eclipse.jetty.websocket.core.internal.util.ReflectUtils;

/**
 * Factory to create {@link JettyWebSocketFrameHandler} instances suitable for
 * use with jetty-native websocket API.
 * <p>
 * Will create a {@link org.eclipse.jetty.websocket.core.FrameHandler} suitable for use with classes/objects that:
 * </p>
 * <ul>
 * <li>Is &#64;{@link org.eclipse.jetty.websocket.api.annotations.WebSocket} annotated</li>
 * <li>Extends {@link org.eclipse.jetty.websocket.api.WebSocketAdapter}</li>
 * <li>Implements {@link org.eclipse.jetty.websocket.api.WebSocketListener}</li>
 * <li>Implements {@link org.eclipse.jetty.websocket.api.WebSocketConnectionListener}</li>
 * <li>Implements {@link org.eclipse.jetty.websocket.api.WebSocketPartialListener}</li>
 * <li>Implements {@link org.eclipse.jetty.websocket.api.WebSocketPingPongListener}</li>
 * <li>Implements {@link org.eclipse.jetty.websocket.api.WebSocketFrameListener}</li>
 * </ul>
 */
public class JettyWebSocketFrameHandlerFactory extends ContainerLifeCycle
{
    private static final InvokerUtils.Arg[] textCallingArgs = new InvokerUtils.Arg[]{
        new InvokerUtils.Arg(Session.class),
        new InvokerUtils.Arg(String.class).required()
    };

    private static final InvokerUtils.Arg[] binaryBufferCallingArgs = new InvokerUtils.Arg[]{
        new InvokerUtils.Arg(Session.class),
        new InvokerUtils.Arg(ByteBuffer.class).required()
    };

    private static final InvokerUtils.Arg[] binaryArrayCallingArgs = new InvokerUtils.Arg[]{
        new InvokerUtils.Arg(Session.class),
        new InvokerUtils.Arg(byte[].class).required(),
        new InvokerUtils.Arg(int.class), // offset
        new InvokerUtils.Arg(int.class) // length
    };

    private static final InvokerUtils.Arg[] inputStreamCallingArgs = new InvokerUtils.Arg[]{
        new InvokerUtils.Arg(Session.class),
        new InvokerUtils.Arg(InputStream.class).required()
    };

    private static final InvokerUtils.Arg[] readerCallingArgs = new InvokerUtils.Arg[]{
        new InvokerUtils.Arg(Session.class),
        new InvokerUtils.Arg(Reader.class).required()
    };

    private static final InvokerUtils.Arg[] textPartialCallingArgs = new InvokerUtils.Arg[]{
        new InvokerUtils.Arg(Session.class),
        new InvokerUtils.Arg(String.class).required(),
        new InvokerUtils.Arg(boolean.class).required()
    };

    private static final InvokerUtils.Arg[] binaryPartialBufferCallingArgs = new InvokerUtils.Arg[]{
        new InvokerUtils.Arg(Session.class),
        new InvokerUtils.Arg(ByteBuffer.class).required(),
        new InvokerUtils.Arg(boolean.class).required()
    };

    private static final InvokerUtils.Arg[] binaryPartialArrayCallingArgs = new InvokerUtils.Arg[]{
        new InvokerUtils.Arg(Session.class),
        new InvokerUtils.Arg(byte[].class).required(),
        new InvokerUtils.Arg(boolean.class).required()
    };

    private final WebSocketContainer container;
    private final WebSocketComponents components;
    private final Map<Class<?>, JettyWebSocketFrameHandlerMetadata> metadataMap = new ConcurrentHashMap<>();

    public JettyWebSocketFrameHandlerFactory(WebSocketContainer container, WebSocketComponents components)
    {
        this.container = container;
        this.components = components;
    }

    public WebSocketComponents getWebSocketComponents()
    {
        return components;
    }

    public JettyWebSocketFrameHandlerMetadata getMetadata(Class<?> endpointClass)
    {
        JettyWebSocketFrameHandlerMetadata metadata = metadataMap.get(endpointClass);

        if (metadata == null)
        {
            metadata = createMetadata(endpointClass);
            metadataMap.put(endpointClass, metadata);
        }

        return metadata;
    }

    public JettyWebSocketFrameHandlerMetadata createMetadata(Class<?> endpointClass)
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

    public JettyWebSocketFrameHandler newJettyFrameHandler(Object endpointInstance)
    {
        JettyWebSocketFrameHandlerMetadata metadata = getMetadata(endpointInstance.getClass());

        final MethodHandle openHandle = InvokerUtils.bindTo(metadata.getOpenHandle(), endpointInstance);
        final MethodHandle closeHandle = InvokerUtils.bindTo(metadata.getCloseHandle(), endpointInstance);
        final MethodHandle errorHandle = InvokerUtils.bindTo(metadata.getErrorHandle(), endpointInstance);
        final MethodHandle textHandle = InvokerUtils.bindTo(metadata.getTextHandle(), endpointInstance);
        final MethodHandle binaryHandle = InvokerUtils.bindTo(metadata.getBinaryHandle(), endpointInstance);
        final Class<? extends MessageSink> textSinkClass = metadata.getTextSink();
        final Class<? extends MessageSink> binarySinkClass = metadata.getBinarySink();
        final MethodHandle frameHandle = InvokerUtils.bindTo(metadata.getFrameHandle(), endpointInstance);
        final MethodHandle pingHandle = InvokerUtils.bindTo(metadata.getPingHandle(), endpointInstance);
        final MethodHandle pongHandle = InvokerUtils.bindTo(metadata.getPongHandle(), endpointInstance);
        BatchMode batchMode = metadata.getBatchMode();

        // Decorate the endpointInstance while we are still upgrading for access to things like HttpSession.
        components.getObjectFactory().decorate(endpointInstance);

        return new JettyWebSocketFrameHandler(
            container,
            endpointInstance,
            openHandle, closeHandle, errorHandle,
            textHandle, binaryHandle,
            textSinkClass, binarySinkClass,
            frameHandle, pingHandle, pongHandle,
            batchMode,
            metadata);
    }

    public static MessageSink createMessageSink(MethodHandle msgHandle, Class<? extends MessageSink> sinkClass, Executor executor, WebSocketSession session)
    {
        if (msgHandle == null)
            return null;
        if (sinkClass == null)
            return null;

        try
        {
            MethodHandles.Lookup lookup = JettyWebSocketFrameHandlerFactory.getServerMethodHandleLookup();
            MethodHandle ctorHandle = lookup.findConstructor(sinkClass,
                MethodType.methodType(void.class, CoreSession.class, MethodHandle.class));
            return (MessageSink)ctorHandle.invoke(session.getCoreSession(), msgHandle);
        }
        catch (NoSuchMethodException e)
        {
            throw new RuntimeException("Missing expected MessageSink constructor found at: " + sinkClass.getName(), e);
        }
        catch (IllegalAccessException | InstantiationException | InvocationTargetException e)
        {
            throw new RuntimeException("Unable to create MessageSink: " + sinkClass.getName(), e);
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

    private JettyWebSocketFrameHandlerMetadata createListenerMetadata(Class<?> endpointClass)
    {
        JettyWebSocketFrameHandlerMetadata metadata = new JettyWebSocketFrameHandlerMetadata();
        MethodHandles.Lookup lookup = JettyWebSocketFrameHandlerFactory.getServerMethodHandleLookup();

        if (!WebSocketConnectionListener.class.isAssignableFrom(endpointClass))
            throw new IllegalArgumentException("Class " + endpointClass + " does not implement " + WebSocketConnectionListener.class);

        Method openMethod = ReflectUtils.findMethod(WebSocketConnectionListener.class, "onWebSocketConnect", Session.class);
        MethodHandle open = toMethodHandle(lookup, openMethod);
        metadata.setOpenHandler(open, openMethod);

        Method closeMethod = ReflectUtils.findMethod(WebSocketConnectionListener.class, "onWebSocketClose", int.class, String.class);
        MethodHandle close = toMethodHandle(lookup, closeMethod);
        metadata.setCloseHandler(close, closeMethod);

        Method errorMethod = ReflectUtils.findMethod(WebSocketConnectionListener.class, "onWebSocketError", Throwable.class);
        MethodHandle error = toMethodHandle(lookup, errorMethod);
        metadata.setErrorHandler(error, errorMethod);

        // Simple Data Listener
        if (WebSocketListener.class.isAssignableFrom(endpointClass))
        {
            Method textMethod = ReflectUtils.findMethod(WebSocketListener.class, "onWebSocketText", String.class);
            MethodHandle text = toMethodHandle(lookup, textMethod);
            metadata.setTextHandler(StringMessageSink.class, text, textMethod);

            Method binaryMethod = ReflectUtils.findMethod(WebSocketListener.class, "onWebSocketBinary", byte[].class, int.class, int.class);
            MethodHandle binary = toMethodHandle(lookup, binaryMethod);
            metadata.setBinaryHandle(ByteArrayMessageSink.class, binary, binaryMethod);
        }

        // Ping/Pong Listener
        if (WebSocketPingPongListener.class.isAssignableFrom(endpointClass))
        {
            Method pongMethod = ReflectUtils.findMethod(WebSocketPingPongListener.class, "onWebSocketPong", ByteBuffer.class);
            MethodHandle pong = toMethodHandle(lookup, pongMethod);
            metadata.setPongHandle(pong, pongMethod);

            Method pingMethod = ReflectUtils.findMethod(WebSocketPingPongListener.class, "onWebSocketPing", ByteBuffer.class);
            MethodHandle ping = toMethodHandle(lookup, pingMethod);
            metadata.setPingHandle(ping, pingMethod);
        }

        // Partial Data / Message Listener
        if (WebSocketPartialListener.class.isAssignableFrom(endpointClass))
        {
            Method textMethod = ReflectUtils.findMethod(WebSocketPartialListener.class, "onWebSocketPartialText", String.class, boolean.class);
            MethodHandle text = toMethodHandle(lookup, textMethod);
            metadata.setTextHandler(PartialStringMessageSink.class, text, textMethod);

            Method binaryMethod = ReflectUtils.findMethod(WebSocketPartialListener.class, "onWebSocketPartialBinary", ByteBuffer.class, boolean.class);
            MethodHandle binary = toMethodHandle(lookup, binaryMethod);
            metadata.setBinaryHandle(PartialByteBufferMessageSink.class, binary, binaryMethod);
        }

        // Frame Listener
        if (WebSocketFrameListener.class.isAssignableFrom(endpointClass))
        {
            Method frameMethod = ReflectUtils.findMethod(WebSocketFrameListener.class, "onWebSocketFrame", Frame.class);
            MethodHandle frame = toMethodHandle(lookup, frameMethod);
            metadata.setFrameHandler(frame, frameMethod);
        }

        return metadata;
    }

    private JettyWebSocketFrameHandlerMetadata createAnnotatedMetadata(WebSocket anno, Class<?> endpointClass)
    {
        JettyWebSocketFrameHandlerMetadata metadata = new JettyWebSocketFrameHandlerMetadata();

        int max = anno.inputBufferSize();
        if (max >= 0)
            metadata.setInputBufferSize(max);
        max = anno.maxBinaryMessageSize();
        if (max >= 0)
            metadata.setMaxBinaryMessageSize(max);
        max = anno.maxTextMessageSize();
        if (max >= 0)
            metadata.setMaxTextMessageSize(max);
        max = anno.idleTimeout();
        if (max >= 0)
            metadata.setIdleTimeout(Duration.ofMillis(max));
        metadata.setBatchMode(anno.batchMode());

        MethodHandles.Lookup lookup = getApplicationMethodHandleLookup(endpointClass);
        Method onmethod;

        // OnWebSocketConnect [0..1]
        onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnWebSocketConnect.class);
        if (onmethod != null)
        {
            assertSignatureValid(endpointClass, onmethod, OnWebSocketConnect.class);
            final InvokerUtils.Arg SESSION = new InvokerUtils.Arg(Session.class).required();
            MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, endpointClass, onmethod, SESSION);
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
            MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, endpointClass, onmethod, SESSION, STATUS_CODE, REASON);
            metadata.setCloseHandler(methodHandle, onmethod);
        }

        // OnWebSocketError [0..1]
        onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnWebSocketError.class);
        if (onmethod != null)
        {
            assertSignatureValid(endpointClass, onmethod, OnWebSocketError.class);
            final InvokerUtils.Arg SESSION = new InvokerUtils.Arg(Session.class);
            final InvokerUtils.Arg CAUSE = new InvokerUtils.Arg(Throwable.class).required();
            MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, endpointClass, onmethod, SESSION, CAUSE);
            metadata.setErrorHandler(methodHandle, onmethod);
        }

        // OnWebSocketFrame [0..1]
        onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnWebSocketFrame.class);
        if (onmethod != null)
        {
            assertSignatureValid(endpointClass, onmethod, OnWebSocketFrame.class);
            final InvokerUtils.Arg SESSION = new InvokerUtils.Arg(Session.class);
            final InvokerUtils.Arg FRAME = new InvokerUtils.Arg(Frame.class).required();
            MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, endpointClass, onmethod, SESSION, FRAME);
            metadata.setFrameHandler(methodHandle, onmethod);
        }

        // OnWebSocketMessage [0..2]
        Method[] onMessages = ReflectUtils.findAnnotatedMethods(endpointClass, OnWebSocketMessage.class);
        if (onMessages != null && onMessages.length > 0)
        {
            // The different kind of @OnWebSocketMessage method parameter signatures expected
            for (Method onMsg : onMessages)
            {
                assertSignatureValid(endpointClass, onMsg, OnWebSocketMessage.class);

                MethodHandle methodHandle = InvokerUtils.optionalMutatedInvoker(lookup, endpointClass, onMsg, textCallingArgs);
                if (methodHandle != null)
                {
                    // Normal Text Message
                    assertSignatureValid(endpointClass, onMsg, OnWebSocketMessage.class);
                    metadata.setTextHandler(StringMessageSink.class, methodHandle, onMsg);
                    continue;
                }

                methodHandle = InvokerUtils.optionalMutatedInvoker(lookup, endpointClass, onMsg, binaryBufferCallingArgs);
                if (methodHandle != null)
                {
                    // ByteBuffer Binary Message
                    assertSignatureValid(endpointClass, onMsg, OnWebSocketMessage.class);
                    metadata.setBinaryHandle(ByteBufferMessageSink.class, methodHandle, onMsg);
                    continue;
                }

                methodHandle = InvokerUtils.optionalMutatedInvoker(lookup, endpointClass, onMsg, binaryArrayCallingArgs);
                if (methodHandle != null)
                {
                    // byte[] Binary Message
                    assertSignatureValid(endpointClass, onMsg, OnWebSocketMessage.class);
                    metadata.setBinaryHandle(ByteArrayMessageSink.class, methodHandle, onMsg);
                    continue;
                }

                methodHandle = InvokerUtils.optionalMutatedInvoker(lookup, endpointClass, onMsg, inputStreamCallingArgs);
                if (methodHandle != null)
                {
                    // InputStream Binary Message
                    assertSignatureValid(endpointClass, onMsg, OnWebSocketMessage.class);
                    metadata.setBinaryHandle(InputStreamMessageSink.class, methodHandle, onMsg);
                    continue;
                }

                methodHandle = InvokerUtils.optionalMutatedInvoker(lookup, endpointClass, onMsg, readerCallingArgs);
                if (methodHandle != null)
                {
                    // Reader Text Message
                    assertSignatureValid(endpointClass, onMsg, OnWebSocketMessage.class);
                    metadata.setTextHandler(ReaderMessageSink.class, methodHandle, onMsg);
                    continue;
                }

                methodHandle = InvokerUtils.optionalMutatedInvoker(lookup, endpointClass, onMsg, textPartialCallingArgs);
                if (methodHandle != null)
                {
                    // Partial Text Message
                    assertSignatureValid(endpointClass, onMsg, OnWebSocketMessage.class);
                    metadata.setTextHandler(PartialStringMessageSink.class, methodHandle, onMsg);
                    continue;
                }

                methodHandle = InvokerUtils.optionalMutatedInvoker(lookup, endpointClass, onMsg, binaryPartialBufferCallingArgs);
                if (methodHandle != null)
                {
                    // Partial ByteBuffer Message
                    assertSignatureValid(endpointClass, onMsg, OnWebSocketMessage.class);
                    metadata.setBinaryHandle(PartialByteBufferMessageSink.class, methodHandle, onMsg);
                    continue;
                }

                // Not a valid @OnWebSocketMessage declaration signature
                throw InvalidSignatureException.build(endpointClass, OnWebSocketMessage.class, onMsg);
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

    /**
     * <p>
     * Gives a {@link MethodHandles.Lookup} instance to be used to find methods in server classes.
     * For lookups on application classes use {@link #getApplicationMethodHandleLookup(Class)} instead.
     * </p>
     * <p>
     * This uses the caller sensitive {@link MethodHandles#lookup()}, this will allow MethodHandle access
     * to server classes we need to use and will give access permissions to private methods as well.
     * </p>
     *
     * @return a lookup object to be used to find methods on server classes.
     */
    public static MethodHandles.Lookup getServerMethodHandleLookup()
    {
        return MethodHandles.lookup();
    }

    /**
     * <p>
     * Gives a {@link MethodHandles.Lookup} instance to be used to find public methods in application classes.
     * For lookups on server classes use {@link #getServerMethodHandleLookup()} instead.
     * </p>
     * <p>
     * This uses {@link MethodHandles#publicLookup()} as we only need access to public method of the lookupClass.
     * To look up a method on the lookupClass, it must be public and the class must be accessible from this
     * module, so if the lookupClass is in a JPMS module it must be exported so that the public methods
     * of the lookupClass are accessible outside of the module.
     * </p>
     * <p>
     * The {@link java.lang.invoke.MethodHandles.Lookup#in(Class)} allows us to search specifically
     * in the endpoint Class to avoid any potential linkage errors which could occur if the same
     * class is present in multiple web apps. Unlike using {@link MethodHandles#publicLookup()}
     * using {@link MethodHandles#lookup()} with {@link java.lang.invoke.MethodHandles.Lookup#in(Class)}
     * will cause the lookup to lose its public access to the lookup class if they are in different modules.
     * </p>
     * <p>
     * {@link MethodHandles#privateLookupIn(Class, MethodHandles.Lookup)} is also unsuitable because it
     * requires the caller module to read the target module, and the target module to open reflective
     * access to the lookupClasses private methods. This is possible but requires extra configuration
     * to provide private access which is not necessary for the purpose of accessing the public methods.
     * </p>
     *
     * @param lookupClass the desired lookup class for the new lookup object.
     * @return a lookup object to be used to find methods on the lookupClass.
     */
    public static MethodHandles.Lookup getApplicationMethodHandleLookup(Class<?> lookupClass)
    {
        return MethodHandles.publicLookup().in(lookupClass);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent, metadataMap);
    }
}
