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

package org.eclipse.jetty.websocket.javax.common;

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
import java.util.function.Function;
import javax.websocket.CloseReason;
import javax.websocket.Decoder;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.PongMessage;
import javax.websocket.Session;

import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.exception.InvalidSignatureException;
import org.eclipse.jetty.websocket.core.exception.InvalidWebSocketException;
import org.eclipse.jetty.websocket.core.internal.messages.MessageSink;
import org.eclipse.jetty.websocket.core.internal.messages.PartialByteArrayMessageSink;
import org.eclipse.jetty.websocket.core.internal.messages.PartialByteBufferMessageSink;
import org.eclipse.jetty.websocket.core.internal.messages.PartialStringMessageSink;
import org.eclipse.jetty.websocket.core.internal.util.InvokerUtils;
import org.eclipse.jetty.websocket.core.internal.util.MethodHolder;
import org.eclipse.jetty.websocket.core.internal.util.ReflectUtils;
import org.eclipse.jetty.websocket.javax.common.decoders.RegisteredDecoder;
import org.eclipse.jetty.websocket.javax.common.messages.AbstractDecodedMessageSink;
import org.eclipse.jetty.websocket.javax.common.messages.DecodedBinaryMessageSink;
import org.eclipse.jetty.websocket.javax.common.messages.DecodedBinaryStreamMessageSink;
import org.eclipse.jetty.websocket.javax.common.messages.DecodedTextMessageSink;
import org.eclipse.jetty.websocket.javax.common.messages.DecodedTextStreamMessageSink;

public abstract class JavaxWebSocketFrameHandlerFactory
{
    static InvokerUtils.Arg[] getArgsFor(Class<?> objectType)
    {
        return new InvokerUtils.Arg[]{new InvokerUtils.Arg(Session.class), new InvokerUtils.Arg(objectType).required()};
    }

    static final InvokerUtils.Arg[] textPartialCallingArgs = new InvokerUtils.Arg[]{
        new InvokerUtils.Arg(Session.class),
        new InvokerUtils.Arg(String.class).required(),
        new InvokerUtils.Arg(boolean.class).required()
    };

    static final InvokerUtils.Arg[] binaryPartialBufferCallingArgs = new InvokerUtils.Arg[]{
        new InvokerUtils.Arg(Session.class),
        new InvokerUtils.Arg(ByteBuffer.class).required(),
        new InvokerUtils.Arg(boolean.class).required()
    };

    static final InvokerUtils.Arg[] binaryPartialArrayCallingArgs = new InvokerUtils.Arg[]{
        new InvokerUtils.Arg(Session.class),
        new InvokerUtils.Arg(byte[].class).required(),
        new InvokerUtils.Arg(boolean.class).required()
    };

    static final InvokerUtils.Arg[] pongCallingArgs = new InvokerUtils.Arg[]{
        new InvokerUtils.Arg(Session.class),
        new InvokerUtils.Arg(PongMessage.class).required()
    };

    protected final JavaxWebSocketContainer container;
    protected final InvokerUtils.ParamIdentifier paramIdentifier;
    protected final WebSocketComponents components;

    public JavaxWebSocketFrameHandlerFactory(JavaxWebSocketContainer container, InvokerUtils.ParamIdentifier paramIdentifier)
    {
        this.container = container;
        this.components = container.getWebSocketComponents();
        this.paramIdentifier = paramIdentifier == null ? InvokerUtils.PARAM_IDENTITY : paramIdentifier;
    }

    public abstract JavaxWebSocketFrameHandlerMetadata getMetadata(Class<?> endpointClass, EndpointConfig endpointConfig);

    public abstract EndpointConfig newDefaultEndpointConfig(Class<?> endpointClass);

    public JavaxWebSocketFrameHandler newJavaxWebSocketFrameHandler(Object endpointInstance, UpgradeRequest upgradeRequest)
    {
        Object endpoint;
        EndpointConfig config;

        if (endpointInstance instanceof ConfiguredEndpoint)
        {
            ConfiguredEndpoint configuredEndpoint = (ConfiguredEndpoint)endpointInstance;
            endpoint = configuredEndpoint.getRawEndpoint();
            config = configuredEndpoint.getConfig();
        }
        else
        {
            endpoint = endpointInstance;
            config = newDefaultEndpointConfig(endpoint.getClass());
        }

        JavaxWebSocketFrameHandlerMetadata metadata = getMetadata(endpoint.getClass(), config);
        if (metadata == null)
            return null;

        MethodHolder openHandle = MethodHolder.from(metadata.getOpenHandle());
        MethodHolder closeHandle = MethodHolder.from(metadata.getCloseHandle());
        MethodHolder errorHandle = MethodHolder.from(metadata.getErrorHandle());
        MethodHolder pongHandle = MethodHolder.from(metadata.getPongHandle());

        JavaxWebSocketMessageMetadata textMetadata = JavaxWebSocketMessageMetadata.copyOf(metadata.getTextMetadata());
        JavaxWebSocketMessageMetadata binaryMetadata = JavaxWebSocketMessageMetadata.copyOf(metadata.getBinaryMetadata());

        UriTemplatePathSpec templatePathSpec = metadata.getUriTemplatePathSpec();
        if (templatePathSpec != null)
        {
            String[] namedVariables = templatePathSpec.getVariables();
            Map<String, String> pathParams = templatePathSpec.getPathParams(upgradeRequest.getPathInContext());

            // Handle parameterized @PathParam entries
            openHandle = bindTemplateVariables(openHandle, namedVariables, pathParams);
            closeHandle = bindTemplateVariables(closeHandle, namedVariables, pathParams);
            errorHandle = bindTemplateVariables(errorHandle, namedVariables, pathParams);
            pongHandle = bindTemplateVariables(pongHandle, namedVariables, pathParams);

            if (textMetadata != null)
                textMetadata.setMethodHolder(bindTemplateVariables(textMetadata.getMethodHolder(), namedVariables, pathParams));
            if (binaryMetadata != null)
                binaryMetadata.setMethodHolder(bindTemplateVariables(binaryMetadata.getMethodHolder(), namedVariables, pathParams));
        }

        openHandle = InvokerUtils.bindTo(openHandle, endpoint);
        closeHandle = InvokerUtils.bindTo(closeHandle, endpoint);
        errorHandle = InvokerUtils.bindTo(errorHandle, endpoint);
        pongHandle = InvokerUtils.bindTo(pongHandle, endpoint);

        // Decorate the endpointInstance while we are still upgrading for access to things like HttpSession.
        components.getObjectFactory().decorate(endpoint);

        return new JavaxWebSocketFrameHandler(
            container,
            upgradeRequest,
            endpoint,
            openHandle, closeHandle, errorHandle,
            textMetadata, binaryMetadata,
            pongHandle,
            config);
    }

    public static MessageSink createMessageSink(JavaxWebSocketSession session, JavaxWebSocketMessageMetadata msgMetadata)
    {
        if (msgMetadata == null)
            return null;

        try
        {
            MethodHandles.Lookup lookup = getServerMethodHandleLookup();
            if (AbstractDecodedMessageSink.class.isAssignableFrom(msgMetadata.getSinkClass()))
            {
                MethodHandle ctorHandle = lookup.findConstructor(msgMetadata.getSinkClass(),
                    MethodType.methodType(void.class, CoreSession.class, MethodHolder.class, List.class));
                List<RegisteredDecoder> registeredDecoders = msgMetadata.getRegisteredDecoders();
                return (MessageSink)ctorHandle.invoke(session.getCoreSession(), msgMetadata.getMethodHolder(), registeredDecoders);
            }
            else
            {
                MethodHandle ctorHandle = lookup.findConstructor(msgMetadata.getSinkClass(),
                    MethodType.methodType(void.class, CoreSession.class, MethodHolder.class));
                return (MessageSink)ctorHandle.invoke(session.getCoreSession(), msgMetadata.getMethodHolder());
            }
        }
        catch (NoSuchMethodException e)
        {
            throw new RuntimeException("Missing expected MessageSink constructor found at: " + msgMetadata.getSinkClass().getName(), e);
        }
        catch (IllegalAccessException | InstantiationException | InvocationTargetException e)
        {
            throw new RuntimeException("Unable to create MessageSink: " + msgMetadata.getSinkClass().getName(), e);
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

    static MethodHolder wrapNonVoidReturnType(MethodHolder handle, JavaxWebSocketSession session)
    {
        if (handle == null)
            return null;

        if (handle.returnType() == Void.TYPE)
            return handle;

        return args ->
        {
            session.filterReturnType(handle.invoke(args));
            return null;
        };
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

    protected JavaxWebSocketFrameHandlerMetadata createEndpointMetadata(EndpointConfig endpointConfig)
    {
        JavaxWebSocketFrameHandlerMetadata metadata = new JavaxWebSocketFrameHandlerMetadata(endpointConfig, components);
        MethodHandles.Lookup lookup = getServerMethodHandleLookup();

        Method openMethod = ReflectUtils.findMethod(Endpoint.class, "onOpen", Session.class, EndpointConfig.class);
        MethodHandle open = toMethodHandle(lookup, openMethod);
        metadata.setOpenHandler(open, openMethod);

        Method closeMethod = ReflectUtils.findMethod(Endpoint.class, "onClose", Session.class, CloseReason.class);
        MethodHandle close = toMethodHandle(lookup, closeMethod);
        metadata.setCloseHandler(close, closeMethod);

        Method errorMethod = ReflectUtils.findMethod(Endpoint.class, "onError", Session.class, Throwable.class);
        MethodHandle error = toMethodHandle(lookup, errorMethod);
        metadata.setErrorHandler(error, errorMethod);

        return metadata;
    }

    protected JavaxWebSocketFrameHandlerMetadata discoverJavaxFrameHandlerMetadata(Class<?> endpointClass, JavaxWebSocketFrameHandlerMetadata metadata)
    {
        MethodHandles.Lookup lookup = getApplicationMethodHandleLookup(endpointClass);
        Method onmethod;

        // OnOpen [0..1]
        onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnOpen.class);
        if (onmethod != null)
        {
            assertSignatureValid(endpointClass, onmethod, OnOpen.class);
            final InvokerUtils.Arg SESSION = new InvokerUtils.Arg(Session.class);
            final InvokerUtils.Arg ENDPOINT_CONFIG = new InvokerUtils.Arg(EndpointConfig.class);
            MethodHandle methodHandle = InvokerUtils
                .mutatedInvoker(lookup, endpointClass, onmethod, paramIdentifier, metadata.getNamedTemplateVariables(), SESSION, ENDPOINT_CONFIG);
            metadata.setOpenHandler(methodHandle, onmethod);
        }

        // OnClose [0..1]
        onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnClose.class);
        if (onmethod != null)
        {
            assertSignatureValid(endpointClass, onmethod, OnClose.class);
            final InvokerUtils.Arg SESSION = new InvokerUtils.Arg(Session.class);
            final InvokerUtils.Arg CLOSE_REASON = new InvokerUtils.Arg(CloseReason.class);
            MethodHandle methodHandle = InvokerUtils
                .mutatedInvoker(lookup, endpointClass, onmethod, paramIdentifier, metadata.getNamedTemplateVariables(), SESSION, CLOSE_REASON);
            metadata.setCloseHandler(methodHandle, onmethod);
        }

        // OnError [0..1]
        onmethod = ReflectUtils.findAnnotatedMethod(endpointClass, OnError.class);
        if (onmethod != null)
        {
            assertSignatureValid(endpointClass, onmethod, OnError.class);
            final InvokerUtils.Arg SESSION = new InvokerUtils.Arg(Session.class);
            final InvokerUtils.Arg CAUSE = new InvokerUtils.Arg(Throwable.class).required();
            MethodHandle methodHandle = InvokerUtils
                .mutatedInvoker(lookup, endpointClass, onmethod, paramIdentifier, metadata.getNamedTemplateVariables(), SESSION, CAUSE);
            metadata.setErrorHandler(methodHandle, onmethod);
        }

        // OnMessage [0..2]
        Method[] onMessages = ReflectUtils.findAnnotatedMethods(endpointClass, OnMessage.class);
        if (onMessages != null && onMessages.length > 0)
        {
            for (Method onMsg : onMessages)
            {
                assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                OnMessage onMessageAnno = onMsg.getAnnotation(OnMessage.class);

                long annotationMaxMessageSize = onMessageAnno.maxMessageSize();
                if (annotationMaxMessageSize > Integer.MAX_VALUE)
                {
                    throw new InvalidWebSocketException(String.format("Value too large: %s#%s - @OnMessage.maxMessageSize=%,d > Integer.MAX_VALUE",
                            endpointClass.getName(), onMsg.getName(), annotationMaxMessageSize));
                }

                // Create MessageMetadata and set annotated maxMessageSize if it is not the default value.
                JavaxWebSocketMessageMetadata msgMetadata = new JavaxWebSocketMessageMetadata();
                if (annotationMaxMessageSize != -1)
                    msgMetadata.setMaxMessageSize((int)annotationMaxMessageSize);

                // Function to search for matching MethodHandle for the endpointClass given a signature.
                Function<InvokerUtils.Arg[], MethodHandle> getMethodHandle = (signature) ->
                    InvokerUtils.optionalMutatedInvoker(lookup, endpointClass, onMsg, paramIdentifier, metadata.getNamedTemplateVariables(), signature);

                // Try to match from available decoders (includes primitive types).
                if (matchDecoders(onMsg, metadata, msgMetadata, getMethodHandle))
                    continue;

                // No decoders matched try partial signatures and pong signatures.
                if (matchOnMessage(onMsg, metadata, msgMetadata, getMethodHandle))
                    continue;

                // Not a valid @OnMessage declaration signature.
                throw InvalidSignatureException.build(endpointClass, OnMessage.class, onMsg);
            }
        }

        return metadata;
    }

    private boolean matchOnMessage(Method onMsg, JavaxWebSocketFrameHandlerMetadata metadata, JavaxWebSocketMessageMetadata msgMetadata,
                                   Function<InvokerUtils.Arg[], MethodHandle> getMethodHandle)
    {
        // Partial Text Message.
        MethodHandle methodHandle = getMethodHandle.apply(textPartialCallingArgs);
        if (methodHandle != null)
        {
            msgMetadata.setSinkClass(PartialStringMessageSink.class);
            msgMetadata.setMethodHolder(MethodHolder.from(methodHandle));
            metadata.setTextMetadata(msgMetadata, onMsg);
            return true;
        }

        // Partial ByteBuffer Binary Message.
        methodHandle = getMethodHandle.apply(binaryPartialBufferCallingArgs);
        if (methodHandle != null)
        {
            msgMetadata.setSinkClass(PartialByteBufferMessageSink.class);
            msgMetadata.setMethodHolder(MethodHolder.from(methodHandle));
            metadata.setBinaryMetadata(msgMetadata, onMsg);
            return true;
        }

        // Partial byte[] Binary Message.
        methodHandle = getMethodHandle.apply(binaryPartialArrayCallingArgs);
        if (methodHandle != null)
        {
            msgMetadata.setSinkClass(PartialByteArrayMessageSink.class);
            msgMetadata.setMethodHolder(MethodHolder.from(methodHandle));
            metadata.setBinaryMetadata(msgMetadata, onMsg);
            return true;
        }

        // Pong Message.
        MethodHandle pongHandle = getMethodHandle.apply(pongCallingArgs);
        if (pongHandle != null)
        {
            metadata.setPongHandle(pongHandle, onMsg);
            return true;
        }

        return false;
    }

    private boolean matchDecoders(Method onMsg, JavaxWebSocketFrameHandlerMetadata metadata, JavaxWebSocketMessageMetadata msgMetadata,
                                  Function<InvokerUtils.Arg[], MethodHandle> getMethodHandle)
    {
        // Get the first decoder match.
        RegisteredDecoder firstDecoder = metadata.getAvailableDecoders().stream()
            .filter(registeredDecoder -> getMethodHandle.apply(getArgsFor(registeredDecoder.objectType)) != null)
            .findFirst()
            .orElse(null);
        if (firstDecoder == null)
            return false;

        // Assemble a list of matching decoders which implement the interface type of the first matching decoder found.
        List<RegisteredDecoder> decoders = new ArrayList<>();
        Class<? extends Decoder> interfaceType = firstDecoder.interfaceType;
        metadata.getAvailableDecoders().stream().filter(decoder ->
            decoder.interfaceType.equals(interfaceType) && (getMethodHandle.apply(getArgsFor(decoder.objectType)) != null))
            .forEach(decoders::add);
        msgMetadata.setRegisteredDecoders(decoders);

        // Get the general methodHandle which applies to all the decoders in the list.
        Class<?> objectType = firstDecoder.objectType;
        for (RegisteredDecoder decoder : decoders)
        {
            if (decoder.objectType.isAssignableFrom(objectType))
                objectType = decoder.objectType;
        }
        MethodHandle methodHandle = getMethodHandle.apply(getArgsFor(objectType));
        msgMetadata.setMethodHolder(MethodHolder.from(methodHandle));

        // Set the sinkClass and then set the MessageMetadata on the FrameHandlerMetadata
        if (interfaceType.equals(Decoder.Text.class))
        {
            msgMetadata.setSinkClass(DecodedTextMessageSink.class);
            metadata.setTextMetadata(msgMetadata, onMsg);
        }
        else if (interfaceType.equals(Decoder.Binary.class))
        {
            msgMetadata.setSinkClass(DecodedBinaryMessageSink.class);
            metadata.setBinaryMetadata(msgMetadata, onMsg);
        }
        else if (interfaceType.equals(Decoder.TextStream.class))
        {
            msgMetadata.setSinkClass(DecodedTextStreamMessageSink.class);
            metadata.setTextMetadata(msgMetadata, onMsg);
        }
        else if (interfaceType.equals(Decoder.BinaryStream.class))
        {
            msgMetadata.setSinkClass(DecodedBinaryStreamMessageSink.class);
            metadata.setBinaryMetadata(msgMetadata, onMsg);
        }

        return true;
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

    /**
     * Bind the URI Template Variables to their provided values, converting to the type
     * that the MethodHandle target has declared.
     *
     * <p>
     * Conversion follows the JSR356 rules for @PathParam and only supports
     * String, Primitive Types (and their Boxed version)
     * </p>
     *
     * @param target the target MethodHandle to work with.  This is assumed to contain a
     * {@link MethodHandle#type()} where all of the initial parameters are the same
     * parameters as found in the provided {@code namedVariables} array.
     * @param namedVariables the array of named variables.  Can be null.
     * @param templateValues the Map of template values (Key to Value), must have same number of entries that {@code namedVariables} has.
     * @return a MethodHandle where all of the {@code namedVariables} in the target type
     * have been statically assigned a converted value (and removed from the resulting {@link MethodHandle#type()}, or null if
     * no {@code target} MethodHandle was provided.
     */
    public static MethodHolder bindTemplateVariables(MethodHolder target, String[] namedVariables, Map<String, String> templateValues)
    {
        if (target == null)
        {
            return null;
        }

        final int IDX = 1;

        MethodHolder retHandle = target;

        if ((templateValues == null) || (templateValues.isEmpty()))
        {
            return retHandle;
        }

        for (String variableName : namedVariables)
        {
            String strValue = templateValues.get(variableName);
            Class<?> type = retHandle.parameterType(IDX);
            try
            {
                if (String.class.isAssignableFrom(type))
                {
                    retHandle = retHandle.bindTo(strValue, IDX);
                }
                else if (Integer.class.isAssignableFrom(type) || Integer.TYPE.isAssignableFrom(type))
                {
                    Integer intValue = Integer.parseInt(strValue);
                    retHandle = retHandle.bindTo(intValue, IDX);
                }
                else if (Long.class.isAssignableFrom(type) || Long.TYPE.isAssignableFrom(type))
                {
                    Long longValue = Long.parseLong(strValue);
                    retHandle = retHandle.bindTo(longValue, IDX);
                }
                else if (Short.class.isAssignableFrom(type) || Short.TYPE.isAssignableFrom(type))
                {
                    Short shortValue = Short.parseShort(strValue);
                    retHandle = retHandle.bindTo(shortValue, IDX);
                }
                else if (Float.class.isAssignableFrom(type) || Float.TYPE.isAssignableFrom(type))
                {
                    Float floatValue = Float.parseFloat(strValue);
                    retHandle = retHandle.bindTo(floatValue, IDX);
                }
                else if (Double.class.isAssignableFrom(type) || Double.TYPE.isAssignableFrom(type))
                {
                    Double doubleValue = Double.parseDouble(strValue);
                    retHandle = retHandle.bindTo(doubleValue, IDX);
                }
                else if (Boolean.class.isAssignableFrom(type) || Boolean.TYPE.isAssignableFrom(type))
                {
                    Boolean boolValue = Boolean.parseBoolean(strValue);
                    retHandle = retHandle.bindTo(boolValue, IDX);
                }
                else if (Character.class.isAssignableFrom(type) || Character.TYPE.isAssignableFrom(type))
                {
                    if (strValue.length() != 1)
                        throw new IllegalArgumentException("Invalid Size");
                    Character charValue = strValue.charAt(0);
                    retHandle = retHandle.bindTo(charValue, IDX);
                }
                else if (Byte.class.isAssignableFrom(type) || Byte.TYPE.isAssignableFrom(type))
                {
                    Byte b = Byte.parseByte(strValue);
                    retHandle = retHandle.bindTo(b, IDX);
                }
                else
                {
                    throw new IllegalStateException("Unsupported Type: " + type);
                }
            }
            catch (NumberFormatException e)
            {
                throw new IllegalArgumentException("Cannot convert String value <" + strValue + "> to type <" + type + ">: " + e.getMessage(), e);
            }
            catch (IllegalArgumentException e)
            {
                throw new IllegalArgumentException("Cannot convert String value <" + strValue + "> to type <" + type + ">: " + e.getMessage());
            }
        }

        return retHandle;
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
     * @param lookupClass the desired lookup class for the new lookup object.
     * @return a lookup object to be used to find methods on the lookupClass.
     */
    public static MethodHandles.Lookup getApplicationMethodHandleLookup(Class<?> lookupClass)
    {
        return MethodHandles.publicLookup().in(lookupClass);
    }
}
