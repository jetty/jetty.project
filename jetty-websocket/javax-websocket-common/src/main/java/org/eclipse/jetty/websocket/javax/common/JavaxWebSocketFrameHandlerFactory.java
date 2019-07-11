//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.javax.common;

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
import java.util.concurrent.ConcurrentHashMap;
import javax.websocket.CloseReason;
import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.PongMessage;
import javax.websocket.Session;

import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.websocket.javax.common.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.javax.common.messages.ByteArrayMessageSink;
import org.eclipse.jetty.websocket.javax.common.messages.ByteBufferMessageSink;
import org.eclipse.jetty.websocket.javax.common.messages.DecodedBinaryMessageSink;
import org.eclipse.jetty.websocket.javax.common.messages.DecodedBinaryStreamMessageSink;
import org.eclipse.jetty.websocket.javax.common.messages.DecodedMessageSink;
import org.eclipse.jetty.websocket.javax.common.messages.DecodedTextMessageSink;
import org.eclipse.jetty.websocket.javax.common.messages.DecodedTextStreamMessageSink;
import org.eclipse.jetty.websocket.javax.common.messages.InputStreamMessageSink;
import org.eclipse.jetty.websocket.javax.common.messages.PartialByteArrayMessageSink;
import org.eclipse.jetty.websocket.javax.common.messages.PartialByteBufferMessageSink;
import org.eclipse.jetty.websocket.javax.common.messages.PartialStringMessageSink;
import org.eclipse.jetty.websocket.javax.common.messages.ReaderMessageSink;
import org.eclipse.jetty.websocket.javax.common.messages.StringMessageSink;
import org.eclipse.jetty.websocket.javax.common.util.InvalidSignatureException;
import org.eclipse.jetty.websocket.javax.common.util.InvokerUtils;
import org.eclipse.jetty.websocket.javax.common.util.ReflectUtils;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jetty.websocket.javax.common.JavaxWebSocketFrameHandlerMetadata.MessageMetadata;

public abstract class JavaxWebSocketFrameHandlerFactory
{
    private static final MethodHandle FILTER_RETURN_TYPE_METHOD;

    static
    {
        try
        {
            FILTER_RETURN_TYPE_METHOD = MethodHandles.lookup()
                .findVirtual(JavaxWebSocketSession.class, "filterReturnType", MethodType.methodType(Void.TYPE, Object.class));
        }
        catch (Throwable e)
        {
            throw new RuntimeException(e);
        }
    }

    protected final JavaxWebSocketContainer container;
    protected final InvokerUtils.ParamIdentifier paramIdentifier;
    private Map<Class<?>, JavaxWebSocketFrameHandlerMetadata> metadataMap = new ConcurrentHashMap<>();

    public JavaxWebSocketFrameHandlerFactory(JavaxWebSocketContainer container, InvokerUtils.ParamIdentifier paramIdentifier)
    {
        this.container = container;
        this.paramIdentifier = paramIdentifier == null ? InvokerUtils.PARAM_IDENTITY : paramIdentifier;
    }

    public JavaxWebSocketFrameHandlerMetadata getMetadata(Class<?> endpointClass, EndpointConfig endpointConfig)
    {
        JavaxWebSocketFrameHandlerMetadata metadata = metadataMap.get(endpointClass);

        if (metadata == null)
        {
            metadata = createMetadata(endpointClass, endpointConfig);
            metadataMap.put(endpointClass, metadata);
        }

        return metadata;
    }

    public abstract JavaxWebSocketFrameHandlerMetadata createMetadata(Class<?> endpointClass, EndpointConfig endpointConfig);

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
            config = new BasicEndpointConfig();
        }

        JavaxWebSocketFrameHandlerMetadata metadata = getMetadata(endpoint.getClass(), config);
        if (metadata == null)
            return null;

        MethodHandle openHandle = metadata.getOpenHandle();
        MethodHandle closeHandle = metadata.getCloseHandle();
        MethodHandle errorHandle = metadata.getErrorHandle();
        MethodHandle pongHandle = metadata.getPongHandle();

        MessageMetadata textMetadata = MessageMetadata.copyOf(metadata.getTextMetadata());
        MessageMetadata binaryMetadata = MessageMetadata.copyOf(metadata.getBinaryMetadata());

        UriTemplatePathSpec templatePathSpec = metadata.getUriTemplatePathSpec();
        if (templatePathSpec != null)
        {
            String[] namedVariables = templatePathSpec.getVariables();
            Map<String, String> pathParams = templatePathSpec.getPathParams(upgradeRequest.getRequestURI().getRawPath());

            // Handle parameterized @PathParam entries
            openHandle = bindTemplateVariables(openHandle, namedVariables, pathParams);
            closeHandle = bindTemplateVariables(closeHandle, namedVariables, pathParams);
            errorHandle = bindTemplateVariables(errorHandle, namedVariables, pathParams);
            pongHandle = bindTemplateVariables(pongHandle, namedVariables, pathParams);

            if (textMetadata != null)
                textMetadata.handle = bindTemplateVariables(textMetadata.handle, namedVariables, pathParams);
            if (binaryMetadata != null)
                binaryMetadata.handle = bindTemplateVariables(binaryMetadata.handle, namedVariables, pathParams);
        }

        openHandle = InvokerUtils.bindTo(openHandle, endpoint);
        closeHandle = InvokerUtils.bindTo(closeHandle, endpoint);
        errorHandle = InvokerUtils.bindTo(errorHandle, endpoint);
        pongHandle = InvokerUtils.bindTo(pongHandle, endpoint);

        JavaxWebSocketFrameHandler frameHandler = new JavaxWebSocketFrameHandler(
            container,
            endpoint,
            openHandle, closeHandle, errorHandle,
            textMetadata, binaryMetadata,
            pongHandle,
            config);

        return frameHandler;
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
    public static MethodHandle bindTemplateVariables(MethodHandle target, String[] namedVariables, Map<String, String> templateValues)
    {
        if (target == null)
        {
            return null;
        }

        final int IDX = 1;

        MethodHandle retHandle = target;

        if ((templateValues == null) || (templateValues.isEmpty()))
        {
            return retHandle;
        }

        for (String variableName : namedVariables)
        {
            String strValue = templateValues.get(variableName);
            Class<?> type = retHandle.type().parameterType(IDX);
            try
            {
                if (String.class.isAssignableFrom(type))
                {
                    retHandle = MethodHandles.insertArguments(retHandle, IDX, strValue);
                }
                else if (Integer.TYPE.isAssignableFrom(type))
                {
                    int intValue = Integer.parseInt(strValue);
                    retHandle = MethodHandles.insertArguments(retHandle, IDX, intValue);
                }
                else if (Long.TYPE.isAssignableFrom(type))
                {
                    long longValue = Long.parseLong(strValue);
                    retHandle = MethodHandles.insertArguments(retHandle, IDX, longValue);
                }
                else if (Short.TYPE.isAssignableFrom(type))
                {
                    short shortValue = Short.parseShort(strValue);
                    retHandle = MethodHandles.insertArguments(retHandle, IDX, shortValue);
                }
                else if (Float.TYPE.isAssignableFrom(type))
                {
                    float floatValue = Float.parseFloat(strValue);
                    retHandle = MethodHandles.insertArguments(retHandle, IDX, floatValue);
                }
                else if (Double.TYPE.isAssignableFrom(type))
                {
                    double doubleValue = Double.parseDouble(strValue);
                    retHandle = MethodHandles.insertArguments(retHandle, IDX, doubleValue);
                }
                else if (Boolean.TYPE.isAssignableFrom(type))
                {
                    boolean boolValue = Boolean.parseBoolean(strValue);
                    retHandle = MethodHandles.insertArguments(retHandle, IDX, boolValue);
                }
                else if (Character.TYPE.isAssignableFrom(type))
                {
                    if (strValue.length() != 1)
                        throw new IllegalArgumentException("Invalid Size");
                    char charValue = strValue.charAt(0);
                    retHandle = MethodHandles.insertArguments(retHandle, IDX, charValue);
                }
                else if (Byte.TYPE.isAssignableFrom(type))
                {
                    byte[] buf = strValue.getBytes(UTF_8);
                    if (buf.length != 1)
                        throw new IllegalArgumentException("Invalid Size");
                    retHandle = MethodHandles.insertArguments(retHandle, IDX, buf[0]);
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
                return (MessageSink)ctorHandle.invoke(session, decoder, msgMetadata.handle);
            }
            else
            {
                MethodHandle ctorHandle = MethodHandles.lookup().findConstructor(msgMetadata.sinkClass,
                    MethodType.methodType(void.class, JavaxWebSocketSession.class, MethodHandle.class));
                return (MessageSink)ctorHandle.invoke(session, msgMetadata.handle);
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

    public static MethodHandle wrapNonVoidReturnType(MethodHandle handle, JavaxWebSocketSession session) throws NoSuchMethodException, IllegalAccessException
    {
        if (handle == null)
            return null;

        if (handle.type().returnType() == Void.TYPE)
            return handle;

        // Technique from  https://stackoverflow.com/questions/48505787/methodhandle-with-general-non-void-return-filter

        // Change the return type of the to be Object so it will match exact with JavaxWebSocketSession.filterReturnType(Object)
        handle = handle.asType(handle.type().changeReturnType(Object.class));

        // Filter the method return type to a call to JavaxWebSocketSession.filterReturnType() bound to this session
        handle = MethodHandles.filterReturnValue(handle, FILTER_RETURN_TYPE_METHOD.bindTo(session));

        return handle;
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
            final InvokerUtils.Arg SESSION = new InvokerUtils.Arg(Session.class);
            final InvokerUtils.Arg ENDPOINT_CONFIG = new InvokerUtils.Arg(EndpointConfig.class);
            MethodHandle methodHandle = InvokerUtils
                .mutatedInvoker(endpointClass, onmethod, paramIdentifier, metadata.getNamedTemplateVariables(), SESSION, ENDPOINT_CONFIG);
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
                .mutatedInvoker(endpointClass, onmethod, paramIdentifier, metadata.getNamedTemplateVariables(), SESSION, CLOSE_REASON);
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
                .mutatedInvoker(endpointClass, onmethod, paramIdentifier, metadata.getNamedTemplateVariables(), SESSION, CAUSE);
            metadata.setErrorHandler(methodHandle, onmethod);
        }

        // OnMessage [0..2]
        Method[] onMessages = ReflectUtils.findAnnotatedMethods(endpointClass, OnMessage.class);
        if (onMessages != null && onMessages.length > 0)
        {
            // The different kind of @OnMessage method parameter signatures expected
            InvokerUtils.Arg[] textCallingArgs = new InvokerUtils.Arg[]{
                new InvokerUtils.Arg(Session.class),
                new InvokerUtils.Arg(String.class).required()
            };

            InvokerUtils.Arg[] textPartialCallingArgs = new InvokerUtils.Arg[]{
                new InvokerUtils.Arg(Session.class),
                new InvokerUtils.Arg(String.class).required(),
                new InvokerUtils.Arg(boolean.class).required()
            };

            InvokerUtils.Arg[] binaryBufferCallingArgs = new InvokerUtils.Arg[]{
                new InvokerUtils.Arg(Session.class),
                new InvokerUtils.Arg(ByteBuffer.class).required()
            };

            InvokerUtils.Arg[] binaryPartialBufferCallingArgs = new InvokerUtils.Arg[]{
                new InvokerUtils.Arg(Session.class),
                new InvokerUtils.Arg(ByteBuffer.class).required(),
                new InvokerUtils.Arg(boolean.class).required()
            };

            InvokerUtils.Arg[] binaryArrayCallingArgs = new InvokerUtils.Arg[]{
                new InvokerUtils.Arg(Session.class),
                new InvokerUtils.Arg(byte[].class).required()
            };

            InvokerUtils.Arg[] binaryPartialArrayCallingArgs = new InvokerUtils.Arg[]{
                new InvokerUtils.Arg(Session.class),
                new InvokerUtils.Arg(byte[].class).required(),
                new InvokerUtils.Arg(boolean.class).required()
            };

            InvokerUtils.Arg[] inputStreamCallingArgs = new InvokerUtils.Arg[]{
                new InvokerUtils.Arg(Session.class),
                new InvokerUtils.Arg(InputStream.class).required()
            };

            InvokerUtils.Arg[] readerCallingArgs = new InvokerUtils.Arg[]{
                new InvokerUtils.Arg(Session.class),
                new InvokerUtils.Arg(Reader.class).required()
            };

            InvokerUtils.Arg[] pongCallingArgs = new InvokerUtils.Arg[]{
                new InvokerUtils.Arg(Session.class),
                new InvokerUtils.Arg(PongMessage.class).required()
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
                            new InvokerUtils.Arg(Session.class),
                            new InvokerUtils.Arg(decoder.objectType).required()
                        ));
                }

                if (decoder.implementsInterface(Decoder.TextStream.class))
                {
                    decodedTextStreamCallingArgs.add(
                        new DecodedArgs(decoder,
                            new InvokerUtils.Arg(Session.class),
                            new InvokerUtils.Arg(decoder.objectType).required()
                        ));
                }

                if (decoder.implementsInterface(Decoder.Binary.class))
                {
                    decodedBinaryCallingArgs.add(
                        new DecodedArgs(decoder,
                            new InvokerUtils.Arg(Session.class),
                            new InvokerUtils.Arg(decoder.objectType).required()
                        ));
                }

                if (decoder.implementsInterface(Decoder.BinaryStream.class))
                {
                    decodedBinaryStreamCallingArgs.add(
                        new DecodedArgs(decoder,
                            new InvokerUtils.Arg(Session.class),
                            new InvokerUtils.Arg(decoder.objectType).required()
                        ));
                }
            }

            onmessageloop:
            for (Method onMsg : onMessages)
            {
                assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                OnMessage onMessageAnno = onMsg.getAnnotation(OnMessage.class);

                MessageMetadata msgMetadata = new MessageMetadata();
                if (onMessageAnno.maxMessageSize() > Integer.MAX_VALUE)
                {
                    throw new InvalidWebSocketException(
                        String.format("Value too large: %s#%s - @OnMessage.maxMessageSize=%,d > Integer.MAX_VALUE",
                            endpointClass.getName(), onMsg.getName(), onMessageAnno.maxMessageSize()));
                }
                msgMetadata.maxMessageSize = (int)onMessageAnno.maxMessageSize();

                MethodHandle methodHandle = InvokerUtils
                    .optionalMutatedInvoker(endpointClass, onMsg, paramIdentifier, metadata.getNamedTemplateVariables(), textCallingArgs);
                if (methodHandle != null)
                {
                    // Whole Text Message
                    assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                    msgMetadata.sinkClass = StringMessageSink.class;
                    msgMetadata.handle = methodHandle;
                    metadata.setTextMetadata(msgMetadata, onMsg);
                    continue onmessageloop;
                }

                methodHandle = InvokerUtils
                    .optionalMutatedInvoker(endpointClass, onMsg, paramIdentifier, metadata.getNamedTemplateVariables(), textPartialCallingArgs);
                if (methodHandle != null)
                {
                    // Partial Text Message
                    assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                    msgMetadata.sinkClass = PartialStringMessageSink.class;
                    msgMetadata.handle = methodHandle;
                    metadata.setTextMetadata(msgMetadata, onMsg);
                    continue onmessageloop;
                }

                methodHandle = InvokerUtils
                    .optionalMutatedInvoker(endpointClass, onMsg, paramIdentifier, metadata.getNamedTemplateVariables(), binaryBufferCallingArgs);
                if (methodHandle != null)
                {
                    // Whole ByteBuffer Binary Message
                    assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                    msgMetadata.sinkClass = ByteBufferMessageSink.class;
                    msgMetadata.handle = methodHandle;
                    metadata.setBinaryMetadata(msgMetadata, onMsg);
                    continue onmessageloop;
                }

                methodHandle = InvokerUtils
                    .optionalMutatedInvoker(endpointClass, onMsg, paramIdentifier, metadata.getNamedTemplateVariables(), binaryPartialBufferCallingArgs);
                if (methodHandle != null)
                {
                    // Partial ByteBuffer Binary Message
                    assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                    msgMetadata.sinkClass = PartialByteBufferMessageSink.class;
                    msgMetadata.handle = methodHandle;
                    metadata.setBinaryMetadata(msgMetadata, onMsg);
                    continue onmessageloop;
                }

                methodHandle = InvokerUtils
                    .optionalMutatedInvoker(endpointClass, onMsg, paramIdentifier, metadata.getNamedTemplateVariables(), binaryArrayCallingArgs);
                if (methodHandle != null)
                {
                    // Whole byte[] Binary Message
                    assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                    msgMetadata.sinkClass = ByteArrayMessageSink.class;
                    msgMetadata.handle = methodHandle;
                    metadata.setBinaryMetadata(msgMetadata, onMsg);
                    continue onmessageloop;
                }

                methodHandle = InvokerUtils
                    .optionalMutatedInvoker(endpointClass, onMsg, paramIdentifier, metadata.getNamedTemplateVariables(), binaryPartialArrayCallingArgs);
                if (methodHandle != null)
                {
                    // Partial byte[] Binary Message
                    assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                    msgMetadata.sinkClass = PartialByteArrayMessageSink.class;
                    msgMetadata.handle = methodHandle;
                    metadata.setBinaryMetadata(msgMetadata, onMsg);
                    continue onmessageloop;
                }

                methodHandle = InvokerUtils
                    .optionalMutatedInvoker(endpointClass, onMsg, paramIdentifier, metadata.getNamedTemplateVariables(), inputStreamCallingArgs);
                if (methodHandle != null)
                {
                    // InputStream Binary Message
                    assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                    msgMetadata.sinkClass = InputStreamMessageSink.class;
                    msgMetadata.handle = methodHandle;
                    metadata.setBinaryMetadata(msgMetadata, onMsg);
                    continue onmessageloop;
                }

                methodHandle = InvokerUtils
                    .optionalMutatedInvoker(endpointClass, onMsg, paramIdentifier, metadata.getNamedTemplateVariables(), readerCallingArgs);
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
                    methodHandle = InvokerUtils
                        .optionalMutatedInvoker(endpointClass, onMsg, paramIdentifier, metadata.getNamedTemplateVariables(), decodedArgs.args);
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
                    methodHandle = InvokerUtils
                        .optionalMutatedInvoker(endpointClass, onMsg, paramIdentifier, metadata.getNamedTemplateVariables(), decodedArgs.args);
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
                    methodHandle = InvokerUtils
                        .optionalMutatedInvoker(endpointClass, onMsg, paramIdentifier, metadata.getNamedTemplateVariables(), decodedArgs.args);
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
                    methodHandle = InvokerUtils
                        .optionalMutatedInvoker(endpointClass, onMsg, paramIdentifier, metadata.getNamedTemplateVariables(), decodedArgs.args);
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

                // == Pong ==

                methodHandle = InvokerUtils
                    .optionalMutatedInvoker(endpointClass, onMsg, paramIdentifier, metadata.getNamedTemplateVariables(), pongCallingArgs);
                if (methodHandle != null)
                {
                    // Pong Message
                    assertSignatureValid(endpointClass, onMsg, OnMessage.class);
                    metadata.setPongHandle(methodHandle, onMsg);
                    continue onmessageloop;
                }

                // Not a valid @OnMessage declaration signature
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
        public final InvokerUtils.Arg[] args;

        public DecodedArgs(AvailableDecoders.RegisteredDecoder registeredDecoder, InvokerUtils.Arg... args)
        {
            this.registeredDecoder = registeredDecoder;
            this.args = args;
        }
    }
}
