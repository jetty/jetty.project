//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Frame;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketContainer;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.exceptions.InvalidWebSocketException;
import org.eclipse.jetty.websocket.common.internal.ByteBufferMessageSink;
import org.eclipse.jetty.websocket.common.internal.PartialByteBufferMessageSink;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.exception.InvalidSignatureException;
import org.eclipse.jetty.websocket.core.messages.InputStreamMessageSink;
import org.eclipse.jetty.websocket.core.messages.PartialStringMessageSink;
import org.eclipse.jetty.websocket.core.messages.ReaderMessageSink;
import org.eclipse.jetty.websocket.core.messages.StringMessageSink;
import org.eclipse.jetty.websocket.core.util.InvokerUtils;
import org.eclipse.jetty.websocket.core.util.ReflectUtils;

/**
 * Factory to create {@link JettyWebSocketFrameHandler} instances suitable for
 * use with jetty-native websocket API.
 * <p>
 * Will create a {@link org.eclipse.jetty.websocket.core.FrameHandler} suitable for use with classes/objects that:
 * </p>
 * <ul>
 * <li>Is &#64;{@link org.eclipse.jetty.websocket.api.annotations.WebSocket} annotated</li>
 * <li>Implements {@link org.eclipse.jetty.websocket.api.Session.Listener}</li>
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
        new InvokerUtils.Arg(ByteBuffer.class).required(),
        new InvokerUtils.Arg(Callback.class).required()
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
        new InvokerUtils.Arg(boolean.class).required(),
        new InvokerUtils.Arg(Callback.class).required()
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
        if (Session.Listener.class.isAssignableFrom(endpointClass))
            return createListenerMetadata(endpointClass);

        WebSocket websocket = endpointClass.getAnnotation(WebSocket.class);
        if (websocket != null)
            return createAnnotatedMetadata(websocket, endpointClass);

        throw new InvalidWebSocketException("Unrecognized WebSocket endpoint: " + endpointClass.getName());
    }

    public JettyWebSocketFrameHandler newJettyFrameHandler(Object endpointInstance)
    {
        JettyWebSocketFrameHandlerMetadata metadata = getMetadata(endpointInstance.getClass());

        // Decorate the endpointInstance while we are still upgrading for access to things like HttpSession.
        components.getObjectFactory().decorate(endpointInstance);

        return new JettyWebSocketFrameHandler(container, endpointInstance, metadata);
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
        metadata.setAutoDemand(Session.Listener.AutoDemanding.class.isAssignableFrom(endpointClass));

        MethodHandles.Lookup lookup = JettyWebSocketFrameHandlerFactory.getServerMethodHandleLookup();

        Method openMethod = findMethod(endpointClass, "onWebSocketOpen", Session.class);
        if (openMethod != null)
        {
            MethodHandle connectHandle = toMethodHandle(lookup, openMethod);
            metadata.setOpenHandle(connectHandle, openMethod);
        }

        Method frameMethod = findMethod(endpointClass, "onWebSocketFrame", Frame.class, Callback.class);
        if (frameMethod != null)
        {
            MethodHandle frameHandle = toMethodHandle(lookup, frameMethod);
            metadata.setFrameHandle(frameHandle, frameMethod);
        }

        Method pingMethod = findMethod(endpointClass, "onWebSocketPing", ByteBuffer.class);
        if (pingMethod != null)
        {
            MethodHandle pingHandle = toMethodHandle(lookup, pingMethod);
            metadata.setPingHandle(pingHandle, pingMethod);
        }

        Method pongMethod = findMethod(endpointClass, "onWebSocketPong", ByteBuffer.class);
        if (pongMethod != null)
        {
            MethodHandle pongHandle = toMethodHandle(lookup, pongMethod);
            metadata.setPongHandle(pongHandle, pongMethod);
        }

        Method partialTextMethod = findMethod(endpointClass, "onWebSocketPartialText", String.class, boolean.class);
        if (partialTextMethod != null)
        {
            MethodHandle partialTextHandle = toMethodHandle(lookup, partialTextMethod);
            metadata.setTextHandle(PartialStringMessageSink.class, partialTextHandle, partialTextMethod);
        }

        Method partialBinaryMethod = findMethod(endpointClass, "onWebSocketPartialBinary", ByteBuffer.class, boolean.class, Callback.class);
        if (partialBinaryMethod != null)
        {
            MethodHandle partialBinaryHandle = toMethodHandle(lookup, partialBinaryMethod);
            metadata.setBinaryHandle(PartialByteBufferMessageSink.class, partialBinaryHandle, partialBinaryMethod);
        }

        Method textMethod = findMethod(endpointClass, "onWebSocketText", String.class);
        if (textMethod != null)
        {
            MethodHandle textHandle = toMethodHandle(lookup, textMethod);
            metadata.setTextHandle(StringMessageSink.class, textHandle, textMethod);
        }

        Method binaryMethod = findMethod(endpointClass, "onWebSocketBinary", ByteBuffer.class, Callback.class);
        if (binaryMethod != null)
        {
            MethodHandle binaryHandle = toMethodHandle(lookup, binaryMethod);
            metadata.setBinaryHandle(ByteBufferMessageSink.class, binaryHandle, binaryMethod);
        }

        Method errorMethod = findMethod(endpointClass, "onWebSocketError", Throwable.class);
        if (errorMethod != null)
        {
            MethodHandle errorHandle = toMethodHandle(lookup, errorMethod);
            metadata.setErrorHandle(errorHandle, errorMethod);
        }

        Method closeMethod = findMethod(endpointClass, "onWebSocketClose", int.class, String.class);
        if (closeMethod != null)
        {
            MethodHandle closeHandle = toMethodHandle(lookup, closeMethod);
            metadata.setCloseHandle(closeHandle, closeMethod);
        }

        return metadata;
    }

    private Method findMethod(Class<?> klass, String name, Class<?>... parameters)
    {
        // Verify if the method is overridden in the endpoint class, to avoid
        // calling all methods of Session.Listener even if they are not overridden.
        Method method = ReflectUtils.findMethod(klass, name, parameters);
        if (method == null)
            return null;
        if (!isOverridden(method))
            return null;
        // The method is overridden, but it may be declared in a non-public
        // class, for example an anonymous class, where it won't be accessible,
        // therefore replace it with the accessible version from Session.Listener.
        if (!Modifier.isPublic(klass.getModifiers()))
            method = ReflectUtils.findMethod(Session.Listener.class, name, parameters);
        return method;
    }

    private boolean isOverridden(Method method)
    {
        return method != null && method.getDeclaringClass() != Session.Listener.class;
    }

    private JettyWebSocketFrameHandlerMetadata createAnnotatedMetadata(WebSocket anno, Class<?> endpointClass)
    {
        JettyWebSocketFrameHandlerMetadata metadata = new JettyWebSocketFrameHandlerMetadata();
        metadata.setAutoDemand(anno.autoDemand());

        MethodHandles.Lookup lookup = getApplicationMethodHandleLookup(endpointClass);
        Method onmethod;

        // OnWebSocketOpen [0..1]
        onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnWebSocketOpen.class);
        if (onmethod != null)
        {
            assertSignatureValid(endpointClass, onmethod, OnWebSocketOpen.class);
            final InvokerUtils.Arg SESSION = new InvokerUtils.Arg(Session.class).required();
            MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, endpointClass, onmethod, SESSION);
            metadata.setOpenHandle(methodHandle, onmethod);
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
            metadata.setCloseHandle(methodHandle, onmethod);
        }

        // OnWebSocketError [0..1]
        onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnWebSocketError.class);
        if (onmethod != null)
        {
            assertSignatureValid(endpointClass, onmethod, OnWebSocketError.class);
            final InvokerUtils.Arg SESSION = new InvokerUtils.Arg(Session.class);
            final InvokerUtils.Arg CAUSE = new InvokerUtils.Arg(Throwable.class).required();
            MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, endpointClass, onmethod, SESSION, CAUSE);
            metadata.setErrorHandle(methodHandle, onmethod);
        }

        // OnWebSocketFrame [0..1]
        onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnWebSocketFrame.class);
        if (onmethod != null)
        {
            assertSignatureValid(endpointClass, onmethod, OnWebSocketFrame.class);
            final InvokerUtils.Arg SESSION = new InvokerUtils.Arg(Session.class);
            final InvokerUtils.Arg FRAME = new InvokerUtils.Arg(Frame.class).required();
            final InvokerUtils.Arg CALLBACK = new InvokerUtils.Arg(Callback.class).required();
            MethodHandle methodHandle = InvokerUtils.mutatedInvoker(lookup, endpointClass, onmethod, SESSION, FRAME, CALLBACK);
            metadata.setFrameHandle(methodHandle, onmethod);
        }

        // OnWebSocketMessage [0..2]
        Method[] onMessages = ReflectUtils.findAnnotatedMethods(endpointClass, OnWebSocketMessage.class);
        if (onMessages != null)
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
                    metadata.setTextHandle(StringMessageSink.class, methodHandle, onMsg);
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

                methodHandle = InvokerUtils.optionalMutatedInvoker(lookup, endpointClass, onMsg, inputStreamCallingArgs);
                if (methodHandle != null)
                {
                    if (!metadata.isAutoDemand())
                        throw new InvalidWebSocketException("InputStream methods require auto-demanding WebSocket endpoints");

                    // InputStream Binary Message
                    assertSignatureValid(endpointClass, onMsg, OnWebSocketMessage.class);
                    metadata.setBinaryHandle(InputStreamMessageSink.class, methodHandle, onMsg);
                    continue;
                }

                methodHandle = InvokerUtils.optionalMutatedInvoker(lookup, endpointClass, onMsg, readerCallingArgs);
                if (methodHandle != null)
                {
                    if (!metadata.isAutoDemand())
                        throw new InvalidWebSocketException("Reader methods require auto-demanding WebSocket endpoints");

                    // Reader Text Message
                    assertSignatureValid(endpointClass, onMsg, OnWebSocketMessage.class);
                    metadata.setTextHandle(ReaderMessageSink.class, methodHandle, onMsg);
                    continue;
                }

                methodHandle = InvokerUtils.optionalMutatedInvoker(lookup, endpointClass, onMsg, textPartialCallingArgs);
                if (methodHandle != null)
                {
                    // Partial Text Message
                    assertSignatureValid(endpointClass, onMsg, OnWebSocketMessage.class);
                    metadata.setTextHandle(PartialStringMessageSink.class, methodHandle, onMsg);
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
