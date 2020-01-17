//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.common;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.PongMessage;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.ProtocolException;
import org.eclipse.jetty.websocket.core.WebSocketException;
import org.eclipse.jetty.websocket.javax.common.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.javax.common.messages.DecodedBinaryMessageSink;
import org.eclipse.jetty.websocket.javax.common.messages.DecodedBinaryStreamMessageSink;
import org.eclipse.jetty.websocket.javax.common.messages.DecodedTextMessageSink;
import org.eclipse.jetty.websocket.javax.common.messages.DecodedTextStreamMessageSink;
import org.eclipse.jetty.websocket.javax.common.messages.PartialByteArrayMessageSink;
import org.eclipse.jetty.websocket.javax.common.messages.PartialByteBufferMessageSink;
import org.eclipse.jetty.websocket.javax.common.messages.PartialStringMessageSink;
import org.eclipse.jetty.websocket.javax.common.util.InvokerUtils;

public class JavaxWebSocketFrameHandler implements FrameHandler
{
    private final Logger logger;
    private final JavaxWebSocketContainer container;
    private final Object endpointInstance;
    /**
     * List of configured named variables in the uri-template.
     * <p>
     *     Used to bind uri-template variables, with their values from the upgrade, to the methods
     *     that have declared their interest in these values via {@code @PathParam} annotations.
     * </p>
     * <p>
     *     Can be null if client side, or no named variables were configured on the server side.
     * </p>
     */
    /**
     * The Map of path parameter values that arrived during the server side upgrade process.
     * <p>
     * Used to bind uri-template variables, with their values from the upgrade, to the methods
     * that have declared their interest in these values via {@code @PathParam} annotations.
     * </p>
     * <p>
     * The values are represented as {@link String} and are essentially static for this
     * instance of the the JavaxWebSocketFrameHandler.   They will be converted to the
     * type declared by the {@code @PathParam} annotations following the JSR356 advice
     * to only support String, Java Primitives (or their Boxed version).
     * </p>
     * <p>
     * Can be null if client side, or no named variables were configured on the server side,
     * or the server side component didn't use the {@link org.eclipse.jetty.http.pathmap.UriTemplatePathSpec} for its mapping.
     * </p>
     */
    private MethodHandle openHandle;
    private MethodHandle closeHandle;
    private MethodHandle errorHandle;
    private JavaxWebSocketFrameHandlerMetadata.MessageMetadata textMetadata;
    private JavaxWebSocketFrameHandlerMetadata.MessageMetadata binaryMetadata;
    private MethodHandle pongHandle;

    private UpgradeRequest upgradeRequest;
    private UpgradeResponse upgradeResponse;

    private EndpointConfig endpointConfig;
    private final Map<Byte, RegisteredMessageHandler> messageHandlerMap = new HashMap<>();
    private MessageSink textSink;
    private MessageSink binarySink;
    private MessageSink activeMessageSink;
    private JavaxWebSocketSession session;
    private CoreSession coreSession;

    protected byte dataType = OpCode.UNDEFINED;

    public JavaxWebSocketFrameHandler(JavaxWebSocketContainer container,
                                      Object endpointInstance,
                                      MethodHandle openHandle, MethodHandle closeHandle, MethodHandle errorHandle,
                                      JavaxWebSocketFrameHandlerMetadata.MessageMetadata textMetadata,
                                      JavaxWebSocketFrameHandlerMetadata.MessageMetadata binaryMetadata,
                                      MethodHandle pongHandle,
                                      EndpointConfig endpointConfig)
    {
        this.logger = Log.getLogger(endpointInstance.getClass());

        this.container = container;
        if (endpointInstance instanceof ConfiguredEndpoint)
        {
            RuntimeException oops = new RuntimeException("ConfiguredEndpoint needs to be unwrapped");
            logger.warn(oops);
            throw oops;
        }
        this.endpointInstance = endpointInstance;

        this.openHandle = openHandle;
        this.closeHandle = closeHandle;
        this.errorHandle = errorHandle;
        this.textMetadata = textMetadata;
        this.binaryMetadata = binaryMetadata;
        this.pongHandle = pongHandle;

        this.endpointConfig = endpointConfig;
    }

    public Object getEndpoint()
    {
        return endpointInstance;
    }

    public EndpointConfig getEndpointConfig()
    {
        return endpointConfig;
    }

    public JavaxWebSocketSession getSession()
    {
        return session;
    }

    @Override
    public void onOpen(CoreSession coreSession, Callback callback)
    {
        this.coreSession = coreSession;

        try
        {
            // Rewire EndpointConfig to call CoreSession setters if Jetty specific properties are set.
            endpointConfig = getWrappedEndpointConfig();
            session = new JavaxWebSocketSession(container, coreSession, this, endpointConfig);
            openHandle = InvokerUtils.bindTo(openHandle, session, endpointConfig);
            closeHandle = InvokerUtils.bindTo(closeHandle, session);
            errorHandle = InvokerUtils.bindTo(errorHandle, session);
            pongHandle = InvokerUtils.bindTo(pongHandle, session);

            JavaxWebSocketFrameHandlerMetadata.MessageMetadata actualTextMetadata = JavaxWebSocketFrameHandlerMetadata.MessageMetadata.copyOf(textMetadata);
            if (actualTextMetadata != null)
            {
                if (actualTextMetadata.isMaxMessageSizeSet())
                    session.setMaxTextMessageBufferSize(actualTextMetadata.maxMessageSize);

                actualTextMetadata.handle = InvokerUtils.bindTo(actualTextMetadata.handle, endpointInstance, endpointConfig, session);
                actualTextMetadata.handle = JavaxWebSocketFrameHandlerFactory.wrapNonVoidReturnType(actualTextMetadata.handle, session);
                textSink = JavaxWebSocketFrameHandlerFactory.createMessageSink(session, actualTextMetadata);
                textMetadata = actualTextMetadata;
            }

            JavaxWebSocketFrameHandlerMetadata.MessageMetadata actualBinaryMetadata = JavaxWebSocketFrameHandlerMetadata.MessageMetadata.copyOf(binaryMetadata);
            if (actualBinaryMetadata != null)
            {
                if (actualBinaryMetadata.isMaxMessageSizeSet())
                    session.setMaxBinaryMessageBufferSize(actualBinaryMetadata.maxMessageSize);

                actualBinaryMetadata.handle = InvokerUtils.bindTo(actualBinaryMetadata.handle, endpointInstance, endpointConfig, session);
                actualBinaryMetadata.handle = JavaxWebSocketFrameHandlerFactory.wrapNonVoidReturnType(actualBinaryMetadata.handle, session);
                binarySink = JavaxWebSocketFrameHandlerFactory.createMessageSink(session, actualBinaryMetadata);
                binaryMetadata = actualBinaryMetadata;
            }

            if (openHandle != null)
                openHandle.invoke();

            container.notifySessionListeners((listener) -> listener.onJavaxWebSocketSessionOpened(session));
            callback.succeeded();
        }
        catch (Throwable cause)
        {
            Exception wse = new WebSocketException(endpointInstance.getClass().getSimpleName() + " OPEN method error: " + cause.getMessage(), cause);
            callback.failed(wse);
        }
    }

    private EndpointConfig getWrappedEndpointConfig()
    {
        final Map<String, Object> listenerMap = new PutListenerMap(this.endpointConfig.getUserProperties(), this::configListener);

        EndpointConfig wrappedConfig;
        if (endpointConfig instanceof ServerEndpointConfig)
        {
            wrappedConfig = new ServerEndpointConfigWrapper((ServerEndpointConfig)endpointConfig)
            {
                @Override
                public Map<String, Object> getUserProperties()
                {
                    return listenerMap;
                }
            };
        }
        else if (endpointConfig instanceof ClientEndpointConfig)
        {
            wrappedConfig = new ClientEndpointConfigWrapper((ClientEndpointConfig)endpointConfig)
            {
                @Override
                public Map<String, Object> getUserProperties()
                {
                    return listenerMap;
                }
            };
        }
        else
        {
            wrappedConfig = new EndpointConfigWrapper(endpointConfig)
            {
                @Override
                public Map<String, Object> getUserProperties()
                {
                    return listenerMap;
                }
            };
        }

        return wrappedConfig;
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        switch (frame.getOpCode())
        {
            case OpCode.TEXT:
                dataType = OpCode.TEXT;
                onText(frame, callback);
                break;
            case OpCode.BINARY:
                dataType = OpCode.BINARY;
                onBinary(frame, callback);
                break;
            case OpCode.CONTINUATION:
                onContinuation(frame, callback);
                break;
            case OpCode.PING:
                onPing(frame, callback);
                break;
            case OpCode.PONG:
                onPong(frame, callback);
                break;
            case OpCode.CLOSE:
                onClose(frame, callback);
                break;
            default:
                callback.failed(new IllegalStateException());
        }

        if (frame.isFin() && !frame.isControlFrame())
            dataType = OpCode.UNDEFINED;
    }

    @Override
    public void onClosed(CloseStatus closeStatus, Callback callback)
    {
        try
        {
            if (closeHandle != null)
            {
                CloseReason closeReason = new CloseReason(CloseReason.CloseCodes.getCloseCode(closeStatus.getCode()), closeStatus.getReason());
                closeHandle.invoke(closeReason);
            }
            callback.succeeded();
        }
        catch (Throwable cause)
        {
            callback.failed(new WebSocketException(endpointInstance.getClass().getSimpleName() + " CLOSE method error: " + cause.getMessage(), cause));
        }

        container.notifySessionListeners((listener) -> listener.onJavaxWebSocketSessionClosed(session));
    }

    @Override
    public void onError(Throwable cause, Callback callback)
    {
        try
        {
            if (errorHandle != null)
                errorHandle.invoke(cause);
            else
                logger.warn("Unhandled Error: " + endpointInstance, cause);
            callback.succeeded();
        }
        catch (Throwable t)
        {
            WebSocketException wsError = new WebSocketException(endpointInstance.getClass().getSimpleName() + " ERROR method error: " + cause.getMessage(), t);
            wsError.addSuppressed(cause);
            callback.failed(wsError);
        }
    }

    public Set<MessageHandler> getMessageHandlers()
    {
        return messageHandlerMap.values().stream()
            .map(RegisteredMessageHandler::getMessageHandler)
            .collect(Collectors.toUnmodifiableSet());
    }

    public Map<Byte, RegisteredMessageHandler> getMessageHandlerMap()
    {
        return messageHandlerMap;
    }

    public JavaxWebSocketFrameHandlerMetadata.MessageMetadata getBinaryMetadata()
    {
        return binaryMetadata;
    }

    public JavaxWebSocketFrameHandlerMetadata.MessageMetadata getTextMetadata()
    {
        return textMetadata;
    }

    private void assertBasicTypeNotRegistered(byte basicWebSocketType, Object messageImpl, String replacement)
    {
        if (messageImpl != null)
        {
            throw new IllegalStateException(
                "Cannot register " + replacement + ": Basic WebSocket type " + OpCode.name(basicWebSocketType) + " is already registered");
        }
    }

    public <T> void addMessageHandler(JavaxWebSocketSession session, Class<T> clazz, MessageHandler.Partial<T> handler)
    {
        try
        {
            // TODO: move methodhandle lookup to container?
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle partialMessageHandler = lookup
                .findVirtual(MessageHandler.Partial.class, "onMessage", MethodType.methodType(void.class, Object.class, boolean.class));
            partialMessageHandler = partialMessageHandler.bindTo(handler);

            // MessageHandler.Partial has no decoder support!
            if (byte[].class.isAssignableFrom(clazz))
            {
                assertBasicTypeNotRegistered(OpCode.BINARY, this.binaryMetadata, handler.getClass().getName());
                MessageSink messageSink = new PartialByteArrayMessageSink(session, partialMessageHandler);
                this.binarySink = registerMessageHandler(OpCode.BINARY, clazz, handler, messageSink);
                JavaxWebSocketFrameHandlerMetadata.MessageMetadata metadata = new JavaxWebSocketFrameHandlerMetadata.MessageMetadata();
                metadata.handle = partialMessageHandler;
                metadata.sinkClass = PartialByteArrayMessageSink.class;
                this.binaryMetadata = metadata;
            }
            else if (ByteBuffer.class.isAssignableFrom(clazz))
            {
                assertBasicTypeNotRegistered(OpCode.BINARY, this.binaryMetadata, handler.getClass().getName());
                MessageSink messageSink = new PartialByteBufferMessageSink(session, partialMessageHandler);
                this.binarySink = registerMessageHandler(OpCode.BINARY, clazz, handler, messageSink);
                JavaxWebSocketFrameHandlerMetadata.MessageMetadata metadata = new JavaxWebSocketFrameHandlerMetadata.MessageMetadata();
                metadata.handle = partialMessageHandler;
                metadata.sinkClass = PartialByteBufferMessageSink.class;
                this.binaryMetadata = metadata;
            }
            else if (String.class.isAssignableFrom(clazz))
            {
                assertBasicTypeNotRegistered(OpCode.TEXT, this.textMetadata, handler.getClass().getName());
                MessageSink messageSink = new PartialStringMessageSink(session, partialMessageHandler);
                this.textSink = registerMessageHandler(OpCode.TEXT, clazz, handler, messageSink);
                JavaxWebSocketFrameHandlerMetadata.MessageMetadata metadata = new JavaxWebSocketFrameHandlerMetadata.MessageMetadata();
                metadata.handle = partialMessageHandler;
                metadata.sinkClass = PartialStringMessageSink.class;
                this.textMetadata = metadata;
            }
            else
            {
                throw new RuntimeException(
                    "Unable to add " + handler.getClass().getName() + " with type " + clazz + ": only supported types byte[], " + ByteBuffer.class.getName() +
                        ", " + String.class.getName());
            }
        }
        catch (NoSuchMethodException e)
        {
            throw new IllegalStateException("Unable to find method", e);
        }
        catch (IllegalAccessException e)
        {
            throw new IllegalStateException("Unable to access " + handler.getClass().getName(), e);
        }
    }

    public <T> void addMessageHandler(JavaxWebSocketSession session, Class<T> clazz, MessageHandler.Whole<T> handler)
    {
        try
        {
            // TODO: move MethodHandle lookup to container?
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle wholeMsgMethodHandle = lookup.findVirtual(MessageHandler.Whole.class, "onMessage", MethodType.methodType(void.class, Object.class));
            wholeMsgMethodHandle = wholeMsgMethodHandle.bindTo(handler);

            if (PongMessage.class.isAssignableFrom(clazz))
            {
                assertBasicTypeNotRegistered(OpCode.PONG, this.pongHandle, handler.getClass().getName());
                this.pongHandle = wholeMsgMethodHandle;
                registerMessageHandler(OpCode.PONG, clazz, handler, null);
            }
            else
            {
                AvailableDecoders availableDecoders = session.getDecoders();

                AvailableDecoders.RegisteredDecoder registeredDecoder = availableDecoders.getRegisteredDecoderFor(clazz);
                if (registeredDecoder == null)
                {
                    throw new IllegalStateException("Unable to find Decoder for type: " + clazz);
                }

                JavaxWebSocketFrameHandlerMetadata.MessageMetadata metadata = new JavaxWebSocketFrameHandlerMetadata.MessageMetadata();
                metadata.handle = wholeMsgMethodHandle;
                metadata.registeredDecoder = registeredDecoder;

                if (registeredDecoder.implementsInterface(Decoder.Binary.class))
                {
                    assertBasicTypeNotRegistered(OpCode.BINARY, this.binaryMetadata, handler.getClass().getName());
                    Decoder.Binary<T> decoder = availableDecoders.getInstanceOf(registeredDecoder);
                    MessageSink messageSink = new DecodedBinaryMessageSink(session, decoder, wholeMsgMethodHandle);
                    metadata.sinkClass = messageSink.getClass();
                    this.binarySink = registerMessageHandler(OpCode.BINARY, clazz, handler, messageSink);
                    this.binaryMetadata = metadata;
                }
                else if (registeredDecoder.implementsInterface(Decoder.BinaryStream.class))
                {
                    assertBasicTypeNotRegistered(OpCode.BINARY, this.binaryMetadata, handler.getClass().getName());
                    Decoder.BinaryStream<T> decoder = availableDecoders.getInstanceOf(registeredDecoder);
                    MessageSink messageSink = new DecodedBinaryStreamMessageSink(session, decoder, wholeMsgMethodHandle);
                    metadata.sinkClass = messageSink.getClass();
                    this.binarySink = registerMessageHandler(OpCode.BINARY, clazz, handler, messageSink);
                    this.binaryMetadata = metadata;
                }
                else if (registeredDecoder.implementsInterface(Decoder.Text.class))
                {
                    assertBasicTypeNotRegistered(OpCode.TEXT, this.textMetadata, handler.getClass().getName());
                    Decoder.Text<T> decoder = availableDecoders.getInstanceOf(registeredDecoder);
                    MessageSink messageSink = new DecodedTextMessageSink(session, decoder, wholeMsgMethodHandle);
                    metadata.sinkClass = messageSink.getClass();
                    this.textSink = registerMessageHandler(OpCode.TEXT, clazz, handler, messageSink);
                    this.textMetadata = metadata;
                }
                else if (registeredDecoder.implementsInterface(Decoder.TextStream.class))
                {
                    assertBasicTypeNotRegistered(OpCode.TEXT, this.textMetadata, handler.getClass().getName());
                    Decoder.TextStream<T> decoder = availableDecoders.getInstanceOf(registeredDecoder);
                    MessageSink messageSink = new DecodedTextStreamMessageSink(session, decoder, wholeMsgMethodHandle);
                    metadata.sinkClass = messageSink.getClass();
                    this.textSink = registerMessageHandler(OpCode.TEXT, clazz, handler, messageSink);
                    this.textMetadata = metadata;
                }
                else
                {
                    throw new RuntimeException("Unable to add " + handler.getClass().getName() + ": type " + clazz + " is unrecognized by declared decoders");
                }
            }
        }
        catch (NoSuchMethodException e)
        {
            throw new IllegalStateException("Unable to find method", e);
        }
        catch (IllegalAccessException e)
        {
            throw new IllegalStateException("Unable to access " + handler.getClass().getName(), e);
        }
    }

    private <T> MessageSink registerMessageHandler(byte basicWebSocketMessageType, Class<T> handlerType, MessageHandler handler, MessageSink messageSink)
    {
        synchronized (messageHandlerMap)
        {
            RegisteredMessageHandler registeredHandler = messageHandlerMap.get(basicWebSocketMessageType);
            if (registeredHandler != null)
            {
                throw new IllegalStateException(String.format("Cannot register %s: Basic WebSocket type %s is already registered to %s",
                    handler.getClass().getName(),
                    OpCode.name(basicWebSocketMessageType),
                    registeredHandler.getMessageHandler().getClass().getName()
                ));
            }

            registeredHandler = new RegisteredMessageHandler(basicWebSocketMessageType, handlerType, handler);
            getMessageHandlerMap().put(registeredHandler.getWebsocketMessageType(), registeredHandler);
            return messageSink;
        }
    }

    public void removeMessageHandler(MessageHandler handler)
    {
        synchronized (messageHandlerMap)
        {
            Optional<Map.Entry<Byte, RegisteredMessageHandler>> optionalEntry = messageHandlerMap.entrySet().stream()
                .filter((entry) -> entry.getValue().getMessageHandler().equals(handler))
                .findFirst();

            if (optionalEntry.isPresent())
            {
                byte key = optionalEntry.get().getKey();
                messageHandlerMap.remove(key);
                switch (key)
                {
                    case OpCode.PONG:
                        this.pongHandle = null;
                        break;
                    case OpCode.TEXT:
                        this.textMetadata = null;
                        this.textSink = null;
                        break;
                    case OpCode.BINARY:
                        this.binaryMetadata = null;
                        this.binarySink = null;
                        break;
                    default:
                        break; // TODO ISE?
                }
            }
        }
    }

    public String toString()
    {
        StringBuilder ret = new StringBuilder();
        ret.append(this.getClass().getSimpleName());
        ret.append('@').append(Integer.toHexString(this.hashCode()));
        ret.append("[endpoint=");
        if (endpointInstance == null)
        {
            ret.append("<null>");
        }
        else
        {
            ret.append(endpointInstance.getClass().getName());
        }
        ret.append(']');
        return ret.toString();
    }

    private void acceptMessage(Frame frame, Callback callback)
    {
        // No message sink is active
        if (activeMessageSink == null)
        {
            callback.succeeded();
            return;
        }

        // Accept the payload into the message sink
        activeMessageSink.accept(frame, callback);
        if (frame.isFin())
            activeMessageSink = null;
    }

    public void onClose(Frame frame, Callback callback)
    {
        callback.succeeded();
    }

    public void onPing(Frame frame, Callback callback)
    {
        ByteBuffer payload = BufferUtil.copy(frame.getPayload());
        coreSession.sendFrame(new Frame(OpCode.PONG).setPayload(payload), Callback.NOOP, false);
        callback.succeeded();
    }

    public void onPong(Frame frame, Callback callback)
    {
        if (pongHandle != null)
        {
            try
            {
                ByteBuffer payload = frame.getPayload();
                if (payload == null)
                    payload = BufferUtil.EMPTY_BUFFER;

                // Use JSR356 PongMessage interface
                JavaxWebSocketPongMessage pongMessage = new JavaxWebSocketPongMessage(payload);

                pongHandle.invoke(pongMessage);
            }
            catch (Throwable cause)
            {
                throw new WebSocketException(endpointInstance.getClass().getSimpleName() + " PONG method error: " + cause.getMessage(), cause);
            }
        }
        callback.succeeded();
    }

    public void onText(Frame frame, Callback callback)
    {
        if (activeMessageSink == null)
            activeMessageSink = textSink;

        acceptMessage(frame, callback);
    }

    public void onBinary(Frame frame, Callback callback)
    {
        if (activeMessageSink == null)
            activeMessageSink = binarySink;

        acceptMessage(frame, callback);
    }

    public void onContinuation(Frame frame, Callback callback)
    {
        switch (dataType)
        {
            case OpCode.TEXT:
                onText(frame, callback);
                break;
            case OpCode.BINARY:
                onBinary(frame, callback);
                break;
            default:
                throw new ProtocolException("Unable to process continuation during dataType " + dataType);
        }
    }

    public void setUpgradeRequest(UpgradeRequest upgradeRequest)
    {
        this.upgradeRequest = upgradeRequest;
    }

    public void setUpgradeResponse(UpgradeResponse upgradeResponse)
    {
        this.upgradeResponse = upgradeResponse;
    }

    public UpgradeRequest getUpgradeRequest()
    {
        return upgradeRequest;
    }

    public UpgradeResponse getUpgradeResponse()
    {
        return upgradeResponse;
    }

    private void configListener(String key, Object value)
    {
        if (!key.startsWith("org.eclipse.jetty.websocket."))
            return;

        switch (key)
        {
            case "org.eclipse.jetty.websocket.autoFragment":
                coreSession.setAutoFragment((Boolean)value);
                break;

            case "org.eclipse.jetty.websocket.maxFrameSize":
                coreSession.setMaxFrameSize((Long)value);
                break;

            case "org.eclipse.jetty.websocket.outputBufferSize":
                coreSession.setOutputBufferSize((Integer)value);
                break;

            case "org.eclipse.jetty.websocket.inputBufferSize":
                coreSession.setInputBufferSize((Integer)value);
                break;
        }
    }
}
