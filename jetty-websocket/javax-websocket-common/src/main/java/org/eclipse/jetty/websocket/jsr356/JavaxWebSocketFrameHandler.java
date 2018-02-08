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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import javax.websocket.CloseReason;
import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.PongMessage;
import javax.websocket.Session;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.AbstractPartialFrameHandler;
import org.eclipse.jetty.websocket.common.HandshakeRequest;
import org.eclipse.jetty.websocket.common.HandshakeResponse;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.WebSocketException;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.frames.PongFrame;
import org.eclipse.jetty.websocket.core.io.BatchMode;
import org.eclipse.jetty.websocket.jsr356.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.jsr356.messages.DecodedBinaryMessageSink;
import org.eclipse.jetty.websocket.jsr356.messages.DecodedInputStreamMessageSink;
import org.eclipse.jetty.websocket.jsr356.messages.DecodedReaderMessageSink;
import org.eclipse.jetty.websocket.jsr356.messages.DecodedTextMessageSink;
import org.eclipse.jetty.websocket.jsr356.messages.PartialByteArrayMessageSink;
import org.eclipse.jetty.websocket.jsr356.messages.PartialByteBufferMessageSink;
import org.eclipse.jetty.websocket.jsr356.messages.PartialStringMessageSink;

public class JavaxWebSocketFrameHandler extends AbstractPartialFrameHandler
{
    private final Logger LOG;
    private final JavaxWebSocketContainer container;
    private final Object endpointInstance;
    private final WebSocketPolicy policy;
    private MethodHandle openHandle;
    private MethodHandle closeHandle;
    private MethodHandle errorHandle;
    private MethodHandle textHandle;
    private Class<? extends MessageSink> textSinkClass;
    private MethodHandle binaryHandle;
    private Class<? extends MessageSink> binarySinkClass;
    // TODO: need pingHandle ?
    private MethodHandle pongHandle;
    /**
     * Immutable HandshakeRequest available via Session
     */
    private final HandshakeRequest handshakeRequest;
    /**
     * Immutable HandshakeResponse available via Session
     */
    private final HandshakeResponse handshakeResponse;
    private final String id;
    private final EndpointConfig endpointConfig;
    private final CompletableFuture<Session> futureSession;
    private MessageSink textSink;
    private MessageSink binarySink;
    private MessageSink activeMessageSink;
    private JavaxWebSocketSession session;
    private Map<Byte, RegisteredMessageHandler> messageHandlerMap;

    public JavaxWebSocketFrameHandler(JavaxWebSocketContainer container,
                                      Object endpointInstance, WebSocketPolicy upgradePolicy,
                                      HandshakeRequest handshakeRequest, HandshakeResponse handshakeResponse,
                                      MethodHandle openHandle, MethodHandle closeHandle, MethodHandle errorHandle,
                                      MethodHandle textHandle, MethodHandle binaryHandle,
                                      Class<? extends MessageSink> textSinkClass,
                                      Class<? extends MessageSink> binarySinkClass,
                                      MethodHandle pongHandle,
                                      String id,
                                      EndpointConfig endpointConfig,
                                      CompletableFuture<Session> futureSession)
    {
        this.LOG = Log.getLogger(endpointInstance.getClass());

        this.container = container;
        if (endpointInstance instanceof ConfiguredEndpoint)
        {
            RuntimeException oops = new RuntimeException("ConfiguredEndpoint needs to be unwrapped");
            LOG.warn(oops);
            throw oops;
        }
        this.endpointInstance = endpointInstance;
        this.policy = upgradePolicy;
        this.handshakeRequest = handshakeRequest;
        this.handshakeResponse = handshakeResponse;

        this.openHandle = openHandle;
        this.closeHandle = closeHandle;
        this.errorHandle = errorHandle;
        this.textHandle = textHandle;
        this.binaryHandle = binaryHandle;
        this.textSinkClass = textSinkClass;
        this.binarySinkClass = binarySinkClass;
        this.pongHandle = pongHandle;

        this.id = id;
        this.endpointConfig = endpointConfig;
        this.futureSession = futureSession;
        this.messageHandlerMap = new HashMap<>();
    }

    public Object getEndpoint()
    {
        return endpointInstance;
    }

    public EndpointConfig getEndpointConfig()
    {
        return endpointConfig;
    }

    public WebSocketPolicy getPolicy()
    {
        return this.policy;
    }

    public JavaxWebSocketSession getSession()
    {
        return session;
    }

    public boolean hasTextSink()
    {
        return this.textSink != null;
    }

    public boolean hasBinarySink()
    {
        return this.binarySink != null;
    }

    @Override
    public void onClosed(CloseStatus closeStatus) throws Exception
    {
        // TODO: FrameHandler cleanup?
    }

    @SuppressWarnings("Duplicates")
    @Override
    public void onError(Throwable cause)
    {
        futureSession.completeExceptionally(cause);

        if (errorHandle == null)
        {
            LOG.warn("Unhandled Error: Endpoint " + endpointInstance.getClass().getName() + " missing onError handler", cause);
            return;
        }

        try
        {
            errorHandle.invoke(cause);
        }
        catch (Throwable t)
        {
            WebSocketException wsError = new WebSocketException(endpointInstance.getClass().getName() + " ERROR method error: " + cause.getMessage(), t);
            wsError.addSuppressed(cause);
            throw wsError;
        }
    }

    @Override
    public void onOpen(Channel channel) throws Exception
    {
        session = new JavaxWebSocketSession(container, channel, this, handshakeRequest, handshakeResponse, id, endpointConfig);

        openHandle = JavaxWebSocketFrameHandlerFactory.bindTo(openHandle, session, endpointConfig);
        closeHandle = JavaxWebSocketFrameHandlerFactory.bindTo(closeHandle, session);
        errorHandle = JavaxWebSocketFrameHandlerFactory.bindTo(errorHandle, session);
        textHandle = JavaxWebSocketFrameHandlerFactory.bindTo(textHandle, session);
        binaryHandle = JavaxWebSocketFrameHandlerFactory.bindTo(binaryHandle, session);
        pongHandle = JavaxWebSocketFrameHandlerFactory.bindTo(pongHandle, session);

        if (textHandle != null)
        {
            textHandle = JavaxWebSocketFrameHandlerFactory.wrapNonVoidReturnType(textHandle, session);
            textSink = JavaxWebSocketFrameHandlerFactory.createMessageSink(session, textHandle, textSinkClass);
        }

        if (binaryHandle != null)
        {
            binaryHandle = JavaxWebSocketFrameHandlerFactory.wrapNonVoidReturnType(binaryHandle, session);
            binarySink = JavaxWebSocketFrameHandlerFactory.createMessageSink(session, binaryHandle, binarySinkClass);
        }

        if (openHandle != null)
        {
            try
            {
                openHandle.invoke();
            }
            catch (Throwable cause)
            {
                throw new WebSocketException(endpointInstance.getClass().getName() + " OPEN method error: " + cause.getMessage(), cause);
            }
        }

        futureSession.complete(session);
    }

    public Set<MessageHandler> getMessageHandlers()
    {
        if (messageHandlerMap.isEmpty())
        {
            return Collections.emptySet();
        }

        return Collections.unmodifiableSet(messageHandlerMap.values().stream()
                .map((rh) -> rh.getMessageHandler())
                .collect(Collectors.toSet()));

    }

    public Map<Byte, RegisteredMessageHandler> getMessageHandlerMap()
    {
        return messageHandlerMap;
    }

    private void assertBasicTypeNotRegistered(byte basicWebSocketType, MethodHandle handle, String replacement)
    {
        if (handle != null)
        {
            throw new IllegalStateException("Cannot register " + replacement + ": Basic WebSocket type " + OpCode.name(basicWebSocketType) + " is already registered");
        }
    }

    public <T> void addMessageHandler(JavaxWebSocketSession session, Class<T> clazz, MessageHandler.Partial<T> handler)
    {
        try
        {
            // TODO: move methodhandle lookup to container?
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle partialMessageHandler = lookup.findVirtual(MessageHandler.Partial.class, "onMessage", MethodType.methodType(Void.TYPE, Object.class, Boolean.TYPE));
            partialMessageHandler = partialMessageHandler.bindTo(handler);

            // MessageHandler.Partial has no decoder support!
            if (byte[].class.isAssignableFrom(clazz))
            {
                assertBasicTypeNotRegistered(OpCode.BINARY, this.binaryHandle, handler.getClass().getName());
                MessageSink messageSink = new PartialByteArrayMessageSink(session, partialMessageHandler);
                this.binarySink = registerMessageHandler(OpCode.BINARY, clazz, handler, messageSink);
                this.binaryHandle = partialMessageHandler;
            }
            else if (ByteBuffer.class.isAssignableFrom(clazz))
            {
                assertBasicTypeNotRegistered(OpCode.BINARY, this.binaryHandle, handler.getClass().getName());
                MessageSink messageSink = new PartialByteBufferMessageSink(session, partialMessageHandler);
                this.binarySink = registerMessageHandler(OpCode.BINARY, clazz, handler, messageSink);
                this.binaryHandle = partialMessageHandler;
            }
            else if (String.class.isAssignableFrom(clazz))
            {
                assertBasicTypeNotRegistered(OpCode.TEXT, this.textHandle, handler.getClass().getName());
                MessageSink messageSink = new PartialStringMessageSink(session, partialMessageHandler);
                this.textSink = registerMessageHandler(OpCode.TEXT, clazz, handler, messageSink);
                this.textHandle = partialMessageHandler;
            }
            else
            {
                throw new RuntimeException("Unable to add " + handler.getClass().getName() + " with type " + clazz + ": only supported types byte[], " + ByteBuffer.class.getName() + ", " + String.class.getName());
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
            // TODO: move methodhandle lookup to container?
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle wholeMsgMethodHandle = lookup.findVirtual(MessageHandler.Whole.class, "onMessage", MethodType.methodType(Void.TYPE, Object.class));
            wholeMsgMethodHandle = wholeMsgMethodHandle.bindTo(handler);

            AvailableDecoders availableDecoders = session.getDecoders();

            AvailableDecoders.RegisteredDecoder registeredDecoder = availableDecoders.getRegisteredDecoderFor(clazz);
            if (registeredDecoder == null)
            {
                throw new IllegalStateException("Unable to find Decoder for type: " + clazz);
            }

            if (PongMessage.class.isAssignableFrom(clazz))
            {
                assertBasicTypeNotRegistered(OpCode.PONG, this.pongHandle, handler.getClass().getName());
                this.pongHandle = wholeMsgMethodHandle;
                registerMessageHandler(OpCode.PONG, clazz, handler, null);
            }
            else if (registeredDecoder.implementsInterface(Decoder.Binary.class))
            {
                assertBasicTypeNotRegistered(OpCode.BINARY, this.binaryHandle, handler.getClass().getName());
                Decoder.Binary<T> decoder = availableDecoders.getInstanceOf(registeredDecoder);
                MessageSink messageSink = new DecodedBinaryMessageSink(session, decoder, wholeMsgMethodHandle);
                this.binarySink = registerMessageHandler(OpCode.BINARY, clazz, handler, messageSink);
                this.binaryHandle = wholeMsgMethodHandle;
            }
            else if (registeredDecoder.implementsInterface(Decoder.BinaryStream.class))
            {
                assertBasicTypeNotRegistered(OpCode.BINARY, this.binaryHandle, handler.getClass().getName());
                Decoder.BinaryStream<T> decoder = availableDecoders.getInstanceOf(registeredDecoder);
                MessageSink messageSink = new DecodedInputStreamMessageSink(session, decoder, wholeMsgMethodHandle);
                this.binarySink = registerMessageHandler(OpCode.BINARY, clazz, handler, messageSink);
                this.binaryHandle = wholeMsgMethodHandle;
            }
            else if (registeredDecoder.implementsInterface(Decoder.Text.class))
            {
                assertBasicTypeNotRegistered(OpCode.TEXT, this.textHandle, handler.getClass().getName());
                Decoder.Text<T> decoder = availableDecoders.getInstanceOf(registeredDecoder);
                MessageSink messageSink = new DecodedTextMessageSink(session, decoder, wholeMsgMethodHandle);
                this.textSink = registerMessageHandler(OpCode.TEXT, clazz, handler, messageSink);
                this.textHandle = wholeMsgMethodHandle;
            }
            else if (registeredDecoder.implementsInterface(Decoder.TextStream.class))
            {
                assertBasicTypeNotRegistered(OpCode.TEXT, this.textHandle, handler.getClass().getName());
                Decoder.TextStream<T> decoder = availableDecoders.getInstanceOf(registeredDecoder);
                MessageSink messageSink = new DecodedReaderMessageSink(session, decoder, wholeMsgMethodHandle);
                this.textSink = registerMessageHandler(OpCode.TEXT, clazz, handler, messageSink);
                this.textHandle = wholeMsgMethodHandle;
            }
            else
            {
                throw new RuntimeException("Unable to add " + handler.getClass().getName() + ": type " + clazz + " is unrecognized by declared decoders");
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
                        this.textHandle = null;
                        this.textSink = null;
                        this.textSinkClass = null;
                        break;
                    case OpCode.BINARY:
                        this.binaryHandle = null;
                        this.binarySink = null;
                        this.binarySinkClass = null;
                        break;
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
            return;

        // Accept the payload into the message sink
        activeMessageSink.accept(frame, callback);
        if (frame.isFin())
            activeMessageSink = null;
    }

    @Override
    public void onBinary(Frame frame, Callback callback)
    {
        super.onBinary(frame, callback);
        if (activeMessageSink == null)
            activeMessageSink = binarySink;

        acceptMessage(frame, callback);
    }

    @Override
    public void onClose(Frame frame, Callback callback)
    {
        if (closeHandle != null)
        {
            try
            {
                CloseStatus close = CloseFrame.toCloseStatus(frame.getPayload());
                CloseReason closeReason = new CloseReason(CloseReason.CloseCodes.getCloseCode(close.getCode()), close.getReason());
                closeHandle.invoke(closeReason);
            }
            catch (Throwable cause)
            {
                throw new WebSocketException(endpointInstance.getClass().getName() + " CLOSE method error: " + cause.getMessage(), cause);
            }
        }
        callback.succeeded();
    }

    @Override
    public void onPing(Frame frame, Callback callback)
    {
        ByteBuffer payload = BufferUtil.EMPTY_BUFFER;

        if (frame.hasPayload())
        {
            payload = ByteBuffer.allocate(frame.getPayloadLength());
            BufferUtil.put(frame.getPayload(), payload);
        }
        channel.sendFrame(new PongFrame().setPayload(payload), Callback.NOOP, BatchMode.OFF);
        callback.succeeded();
    }

    @Override
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
                throw new WebSocketException(endpointInstance.getClass().getName() + " PONG method error: " + cause.getMessage(), cause);
            }
        }
        callback.succeeded();
    }

    @Override
    public void onText(Frame frame, Callback callback)
    {
        super.onText(frame, callback);
        if (activeMessageSink == null)
            activeMessageSink = textSink;

        acceptMessage(frame, callback);
    }
}
