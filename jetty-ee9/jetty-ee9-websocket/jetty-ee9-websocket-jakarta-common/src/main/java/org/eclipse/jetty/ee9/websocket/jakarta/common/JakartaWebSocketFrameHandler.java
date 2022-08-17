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

package org.eclipse.jetty.ee9.websocket.jakarta.common;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Decoder;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.PongMessage;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.ee9.websocket.jakarta.common.decoders.AvailableDecoders;
import org.eclipse.jetty.ee9.websocket.jakarta.common.decoders.RegisteredDecoder;
import org.eclipse.jetty.ee9.websocket.jakarta.common.messages.DecodedBinaryMessageSink;
import org.eclipse.jetty.ee9.websocket.jakarta.common.messages.DecodedBinaryStreamMessageSink;
import org.eclipse.jetty.ee9.websocket.jakarta.common.messages.DecodedTextMessageSink;
import org.eclipse.jetty.ee9.websocket.jakarta.common.messages.DecodedTextStreamMessageSink;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.exception.ProtocolException;
import org.eclipse.jetty.websocket.core.exception.WebSocketException;
import org.eclipse.jetty.websocket.core.internal.messages.MessageSink;
import org.eclipse.jetty.websocket.core.internal.messages.PartialByteArrayMessageSink;
import org.eclipse.jetty.websocket.core.internal.messages.PartialByteBufferMessageSink;
import org.eclipse.jetty.websocket.core.internal.messages.PartialStringMessageSink;
import org.eclipse.jetty.websocket.core.internal.util.InvokerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JakartaWebSocketFrameHandler implements FrameHandler
{
    private final AutoLock lock = new AutoLock();
    private final Logger logger;
    private final JakartaWebSocketContainer container;
    private final Object endpointInstance;
    private final AtomicBoolean closeNotified = new AtomicBoolean();

    private MethodHandle openHandle;
    private MethodHandle closeHandle;
    private MethodHandle errorHandle;
    private MethodHandle pongHandle;
    private JakartaWebSocketMessageMetadata textMetadata;
    private JakartaWebSocketMessageMetadata binaryMetadata;
    private final UpgradeRequest upgradeRequest;
    private EndpointConfig endpointConfig;
    private final Map<Byte, RegisteredMessageHandler> messageHandlerMap = new HashMap<>();
    private MessageSink textSink;
    private MessageSink binarySink;
    private MessageSink activeMessageSink;
    private JakartaWebSocketSession session;
    private CoreSession coreSession;
    protected byte dataType = OpCode.UNDEFINED;

    public JakartaWebSocketFrameHandler(JakartaWebSocketContainer container,
                                      UpgradeRequest upgradeRequest,
                                        Object endpointInstance,
                                        MethodHandle openHandle, MethodHandle closeHandle, MethodHandle errorHandle,
                                      JakartaWebSocketMessageMetadata textMetadata,
                                      JakartaWebSocketMessageMetadata binaryMetadata,
                                        MethodHandle pongHandle,
                                        EndpointConfig endpointConfig)
    {
        this.logger = LoggerFactory.getLogger(endpointInstance.getClass());

        this.container = container;
        this.upgradeRequest = upgradeRequest;
        if (endpointInstance instanceof ConfiguredEndpoint)
        {
            RuntimeException oops = new RuntimeException("ConfiguredEndpoint needs to be unwrapped");
            logger.warn("Unexpected ConfiguredEndpoint", oops);
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

    public JakartaWebSocketSession getSession()
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
            session = new JakartaWebSocketSession(container, coreSession, this, endpointConfig);
            if (!session.isOpen())
                throw new IllegalStateException("Session is not open");

            openHandle = InvokerUtils.bindTo(openHandle, session, endpointConfig);
            closeHandle = InvokerUtils.bindTo(closeHandle, session);
            errorHandle = InvokerUtils.bindTo(errorHandle, session);
            pongHandle = InvokerUtils.bindTo(pongHandle, session);

            JakartaWebSocketMessageMetadata actualTextMetadata = JakartaWebSocketMessageMetadata.copyOf(textMetadata);
            if (actualTextMetadata != null)
            {
                if (actualTextMetadata.isMaxMessageSizeSet())
                    session.setMaxTextMessageBufferSize(actualTextMetadata.getMaxMessageSize());

                MethodHandle methodHandle = actualTextMetadata.getMethodHandle();
                methodHandle = InvokerUtils.bindTo(methodHandle, endpointInstance, endpointConfig, session);
                methodHandle = JakartaWebSocketFrameHandlerFactory.wrapNonVoidReturnType(methodHandle, session);
                actualTextMetadata.setMethodHandle(methodHandle);

                textSink = JakartaWebSocketFrameHandlerFactory.createMessageSink(session, actualTextMetadata);
                textMetadata = actualTextMetadata;
            }

            JakartaWebSocketMessageMetadata actualBinaryMetadata = JakartaWebSocketMessageMetadata.copyOf(binaryMetadata);
            if (actualBinaryMetadata != null)
            {
                if (actualBinaryMetadata.isMaxMessageSizeSet())
                    session.setMaxBinaryMessageBufferSize(actualBinaryMetadata.getMaxMessageSize());

                MethodHandle methodHandle = actualBinaryMetadata.getMethodHandle();
                methodHandle = InvokerUtils.bindTo(methodHandle, endpointInstance, endpointConfig, session);
                methodHandle = JakartaWebSocketFrameHandlerFactory.wrapNonVoidReturnType(methodHandle, session);
                actualBinaryMetadata.setMethodHandle(methodHandle);

                binarySink = JakartaWebSocketFrameHandlerFactory.createMessageSink(session, actualBinaryMetadata);
                binaryMetadata = actualBinaryMetadata;
            }

            if (openHandle != null)
                openHandle.invoke();

            if (session.isOpen())
                container.notifySessionListeners((listener) -> listener.onJakartaWebSocketSessionOpened(session));

            callback.succeeded();
            coreSession.demand(1);
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

    public void onClose(Frame frame, Callback callback)
    {
        notifyOnClose(CloseStatus.getCloseStatus(frame), callback);
    }

    @Override
    public void onClosed(CloseStatus closeStatus, Callback callback)
    {
        notifyOnClose(closeStatus, callback);
        container.notifySessionListeners((listener) -> listener.onJakartaWebSocketSessionClosed(session));

        // Close AvailableEncoders and AvailableDecoders to call destroy() on any instances of Encoder/Encoder created.
        session.getDecoders().close();
        session.getEncoders().close();
    }

    private void notifyOnClose(CloseStatus closeStatus, Callback callback)
    {
        // Make sure onClose is only notified once.
        if (!closeNotified.compareAndSet(false, true))
        {
            callback.succeeded();
            return;
        }

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

    @Override
    public boolean isDemanding()
    {
        return true;
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

    public JakartaWebSocketMessageMetadata getBinaryMetadata()
    {
        return binaryMetadata;
    }

    public JakartaWebSocketMessageMetadata getTextMetadata()
    {
        return textMetadata;
    }

    public <T> void addMessageHandler(Class<T> clazz, MessageHandler.Partial<T> handler)
    {
        try
        {
            MethodHandle methodHandle = JakartaWebSocketFrameHandlerFactory.getServerMethodHandleLookup()
                .findVirtual(MessageHandler.Partial.class, "onMessage", MethodType.methodType(void.class, Object.class, boolean.class))
                .bindTo(handler);

            JakartaWebSocketMessageMetadata metadata = new JakartaWebSocketMessageMetadata();
            metadata.setMethodHandle(methodHandle);
            byte basicType;
            // MessageHandler.Partial has no decoder support!
            if (byte[].class.isAssignableFrom(clazz))
            {
                basicType = OpCode.BINARY;
                metadata.setSinkClass(PartialByteArrayMessageSink.class);
            }
            else if (ByteBuffer.class.isAssignableFrom(clazz))
            {
                basicType = OpCode.BINARY;
                metadata.setSinkClass(PartialByteBufferMessageSink.class);
            }
            else if (String.class.isAssignableFrom(clazz))
            {
                basicType = OpCode.TEXT;
                metadata.setSinkClass(PartialStringMessageSink.class);
            }
            else
            {
                throw new RuntimeException(
                    "Unable to add " + handler.getClass().getName() + " with type " + clazz + ": only supported types byte[], " + ByteBuffer.class.getName() +
                        ", " + String.class.getName());
            }

            // Register the Metadata as a MessageHandler.
            registerMessageHandler(clazz, handler, basicType, metadata);
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

    public <T> void addMessageHandler(Class<T> clazz, MessageHandler.Whole<T> handler)
    {
        try
        {
            MethodHandle methodHandle = JakartaWebSocketFrameHandlerFactory.getServerMethodHandleLookup()
                .findVirtual(MessageHandler.Whole.class, "onMessage", MethodType.methodType(void.class, Object.class))
                .bindTo(handler);

            if (PongMessage.class.isAssignableFrom(clazz))
            {
                assertBasicTypeNotRegistered(OpCode.PONG, handler);
                this.pongHandle = methodHandle;
                registerMessageHandler(OpCode.PONG, clazz, handler, null);
                return;
            }

            AvailableDecoders availableDecoders = session.getDecoders();
            RegisteredDecoder registeredDecoder = availableDecoders.getFirstRegisteredDecoder(clazz);
            if (registeredDecoder == null)
                throw new IllegalStateException("Unable to find Decoder for type: " + clazz);

            // Create the message metadata specific to the MessageHandler type.
            JakartaWebSocketMessageMetadata metadata = new JakartaWebSocketMessageMetadata();
            metadata.setMethodHandle(methodHandle);
            byte basicType;
            if (registeredDecoder.implementsInterface(Decoder.Binary.class))
            {
                basicType = OpCode.BINARY;
                metadata.setRegisteredDecoders(availableDecoders.getBinaryDecoders(clazz));
                metadata.setSinkClass(DecodedBinaryMessageSink.class);
            }
            else if (registeredDecoder.implementsInterface(Decoder.BinaryStream.class))
            {
                basicType = OpCode.BINARY;
                metadata.setRegisteredDecoders(availableDecoders.getBinaryStreamDecoders(clazz));
                metadata.setSinkClass(DecodedBinaryStreamMessageSink.class);
            }
            else if (registeredDecoder.implementsInterface(Decoder.Text.class))
            {
                basicType = OpCode.TEXT;
                metadata.setRegisteredDecoders(availableDecoders.getTextDecoders(clazz));
                metadata.setSinkClass(DecodedTextMessageSink.class);
            }
            else if (registeredDecoder.implementsInterface(Decoder.TextStream.class))
            {
                basicType = OpCode.TEXT;
                metadata.setRegisteredDecoders(availableDecoders.getTextStreamDecoders(clazz));
                metadata.setSinkClass(DecodedTextStreamMessageSink.class);
            }
            else
            {
                throw new RuntimeException("Unable to add " + handler.getClass().getName() + ": type " + clazz + " is unrecognized by declared decoders");
            }

            // Register the Metadata as a MessageHandler.
            registerMessageHandler(clazz, handler, basicType, metadata);
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

    private void assertBasicTypeNotRegistered(byte basicWebSocketType, MessageHandler replacement)
    {
        Object messageImpl;
        switch (basicWebSocketType)
        {
            case OpCode.TEXT:
                messageImpl = textSink;
                break;
            case OpCode.BINARY:
                messageImpl = binarySink;
                break;
            case OpCode.PONG:
                messageImpl = pongHandle;
                break;
            default:
                throw new IllegalStateException();
        }

        if (messageImpl != null)
        {
            throw new IllegalStateException("Cannot register " + replacement.getClass().getName() +
                ": Basic WebSocket type " + OpCode.name(basicWebSocketType) + " is already registered");
        }
    }

    private void registerMessageHandler(Class<?> clazz, MessageHandler handler, byte basicMessageType, JakartaWebSocketMessageMetadata metadata)
    {
        assertBasicTypeNotRegistered(basicMessageType, handler);
        MessageSink messageSink = JakartaWebSocketFrameHandlerFactory.createMessageSink(session, metadata);
        switch (basicMessageType)
        {
            case OpCode.TEXT:
                this.textSink = registerMessageHandler(OpCode.TEXT, clazz, handler, messageSink);
                this.textMetadata = metadata;
                break;
            case OpCode.BINARY:
                this.binarySink = registerMessageHandler(OpCode.BINARY, clazz, handler, messageSink);
                this.binaryMetadata = metadata;
                break;
            default:
                throw new IllegalStateException();
        }
    }

    private <T> MessageSink registerMessageHandler(byte basicWebSocketMessageType, Class<T> handlerType, MessageHandler handler, MessageSink messageSink)
    {
        try (AutoLock l = lock.lock())
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
        try (AutoLock l = lock.lock())
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
                        throw new IllegalStateException("Invalid MessageHandler type " + OpCode.name(key));
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
            coreSession.demand(1);
            return;
        }

        // Accept the payload into the message sink
        activeMessageSink.accept(frame, callback);
        if (frame.isFin())
            activeMessageSink = null;
    }

    public void onPing(Frame frame, Callback callback)
    {
        coreSession.sendFrame(new Frame(OpCode.PONG).setPayload(frame.getPayload()), Callback.from(() ->
        {
            callback.succeeded();
            coreSession.demand(1);
        }), false);
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
                JakartaWebSocketPongMessage pongMessage = new JakartaWebSocketPongMessage(payload);
                pongHandle.invoke(pongMessage);
            }
            catch (Throwable cause)
            {
                throw new WebSocketException(endpointInstance.getClass().getSimpleName() + " PONG method error: " + cause.getMessage(), cause);
            }
        }
        callback.succeeded();
        coreSession.demand(1);
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

    public UpgradeRequest getUpgradeRequest()
    {
        return upgradeRequest;
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
