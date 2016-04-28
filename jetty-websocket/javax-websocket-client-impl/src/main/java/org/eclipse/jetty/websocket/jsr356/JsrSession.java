//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCode;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.Decoder;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Extension;
import javax.websocket.MessageHandler;
import javax.websocket.MessageHandler.Partial;
import javax.websocket.MessageHandler.Whole;
import javax.websocket.RemoteEndpoint.Async;
import javax.websocket.RemoteEndpoint.Basic;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.message.MessageSink;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;
import org.eclipse.jetty.websocket.jsr356.messages.TextPartialMessage;
import org.eclipse.jetty.websocket.jsr356.metadata.DecoderMetadataSet;
import org.eclipse.jetty.websocket.jsr356.metadata.EncoderMetadataSet;

/**
 * Client Session for the JSR.
 */
public class JsrSession extends WebSocketSession implements javax.websocket.Session, Configurable
{
    private static final Logger LOG = Log.getLogger(JsrSession.class);
    private final ClientContainer container;
    private final String id;
    private final EndpointConfig config;
    private final DecoderFactory decoderFactory;
    private final EncoderFactory encoderFactory;
    private Set<MessageHandler> messageHandlerSet;
    
    private List<Extension> negotiatedExtensions;
    private Map<String, String> pathParameters = new HashMap<>();
    private JsrAsyncRemote asyncRemote;
    private JsrBasicRemote basicRemote;

    public JsrSession(ClientContainer container, String id, URI requestURI, Object websocket, LogicalConnection connection)
    {
        super(container, requestURI, websocket, connection);
        
        this.container = container;
        
        ConfiguredEndpoint cendpoint = (ConfiguredEndpoint)websocket;
        this.config = cendpoint.getConfig();

        DecoderMetadataSet decoderSet = new DecoderMetadataSet();
        EncoderMetadataSet encoderSet = new EncoderMetadataSet();
        // TODO: figure out how to populate the decoderSet / encoderSet
        
        this.id = id;
        this.decoderFactory = new DecoderFactory(this,decoderSet,container.getDecoderFactory());
        this.encoderFactory = new EncoderFactory(this,encoderSet,container.getEncoderFactory());
    }
    
    @Override
    protected void discoverEndpointFunctions(Object obj)
    {
        if(obj instanceof ConfiguredEndpoint)
        {
            throw new IllegalArgumentException("JSR356 Implementation expects a " + ConfiguredEndpoint.class.getName() + " but got: " + obj.getClass().getName());
        }
        
        ConfiguredEndpoint cendpoint = (ConfiguredEndpoint) obj;
        
        // Endpoint
        Object websocket = cendpoint.getEndpoint();
        
        if(websocket instanceof Endpoint)
        {
            Endpoint endpoint = (Endpoint)websocket;
            onOpenFunction = (sess) -> {
                endpoint.onOpen(this,config);
                return null;
            };
            onCloseFunction = (closeinfo) -> {
                CloseCode closeCode = CloseCodes.getCloseCode(closeinfo.getStatusCode());
                CloseReason closeReason = new CloseReason(closeCode,closeinfo.getReason());
                endpoint.onClose(this,closeReason);
                return null;
            };
            onErrorFunction = (cause) -> {
                endpoint.onError(this,cause);
                return null;
            };
        }
        
        // Annotations
        
        Class<?> websocketClass = websocket.getClass();
        ClientEndpoint clientEndpoint = websocketClass.getAnnotation(ClientEndpoint.class);
        if(clientEndpoint != null)
        {
            /*Method onmethod = null;
            
            // @OnOpen [0..1]
            onmethod = ReflectUtils.findAnnotatedMethod(websocketClass,OnOpen.class);
            if(onmethod != null)
            {
                assertNotSet(onOpenFunction,"Open Handler",websocketClass,onmethod);
                onOpenFunction = new JsrOnOpenFunction(this,websocket,onmethod);
            }
            // @OnClose [0..1]
            onmethod = ReflectUtils.findAnnotatedMethod(websocketClass,OnClose.class);
            if(onmethod != null)
            {
                assertNotSet(onCloseFunction,"Close Handler",websocketClass,onmethod);
                onCloseFunction = new JsrOnCloseFunction(this,websocket,onmethod);
            }
            // @OnError [0..1]
            onmethod = ReflectUtils.findAnnotatedMethod(websocketClass,OnError.class);
            if(onmethod != null)
            {
                assertNotSet(onErrorFunction,"Error Handler",websocketClass,onmethod);
                onErrorFunction = new JsrOnErrorFunction(this,websocket,onmethod);
            }
            // @OnMessage [0..2]
            Method onmessages[] = ReflectUtils.findAnnotatedMethods(websocketClass,OnMessage.class);
            if(onmessages != null && onmessages.length > 0)
            {
                for(Method method: onmessages)
                {
                    // Text
                    // TextStream
                    // Binary
                    // BinaryStream
                    // Pong
                }
            }*/
        }
    }
    
    @Override
    public <T> void addMessageHandler(Class<T> clazz, Partial<T> handler)
    {
        Objects.requireNonNull(handler, "MessageHandler.Partial cannot be null");
        if (LOG.isDebugEnabled())
        {
            LOG.debug("MessageHandler.Partial class: {}",handler.getClass());
        }
        
        // No decoders for Partial messages per JSR-356 (PFD1 spec)
        
        if(String.class.isAssignableFrom(clazz))
        {
            @SuppressWarnings("unchecked")
            Partial<String> strhandler = (Partial<String>)handler;
            setMessageAppender(MessageType.TEXT, new TextPartialMessage(strhandler));
        }
        else if(ByteBuffer.class.isAssignableFrom(clazz))
        {
            @SuppressWarnings("unchecked")
            Partial<ByteBuffer> bufhandler = (Partial<ByteBuffer>)handler;
//            setMessageAppender(MessageType.BINARY, new BinaryBufferPartialMessage(bufhandler));
        }
        else if(byte[].class.isAssignableFrom(clazz))
        {
            @SuppressWarnings("unchecked")
            Partial<byte[]> arrhandler = (Partial<byte[]>)handler;
//            setMessageAppender(MessageType.BINARY, new BinaryArrayPartialMessage(arrhandler));
        }
        else
        {
            StringBuilder err = new StringBuilder();
            err.append("Unsupported class type for MessageHandler.Partial (only supports <String>, <ByteBuffer>, or <byte[]>): ");
            err.append(clazz.getName());
            throw new IllegalArgumentException(err.toString());
        }
    }
    
    @Override
    public <T> void addMessageHandler(Class<T> clazz, Whole<T> handler)
    {
        Objects.requireNonNull(handler, "MessageHandler.Whole cannot be null");
        if (LOG.isDebugEnabled())
        {
            LOG.debug("MessageHandler.Whole class: {}",handler.getClass());
        }
        
        // Determine Decoder
        DecoderFactory.Wrapper decoderWrapper = decoderFactory.getWrapperFor(clazz);
        if (decoderWrapper == null)
        {
            StringBuilder err = new StringBuilder();
            err.append("Unable to find decoder for type <");
            err.append(clazz.getName());
            err.append("> used in <");
            err.append(handler.getClass().getName());
            err.append(">");
            throw new IllegalStateException(err.toString());
        }
        
        if(decoderWrapper.getMetadata().isStreamed())
        {
            // Streaming 
            if(InputStream.class.isAssignableFrom(clazz))
            {
                // Whole Text Streaming
                @SuppressWarnings("unchecked")
                Whole<Object> streamhandler = (Whole<Object>)handler;
                Decoder.BinaryStream<?> streamdecoder = (Decoder.BinaryStream<?>)decoderWrapper.getDecoder();
//                setMessageAppender(MessageType.TEXT,new JsrInputStreamMessage(streamhandler, streamdecoder, websocket, getExecutor()));
            } 
            else if(Reader.class.isAssignableFrom(clazz))
            {
                // Whole Reader Streaming
                @SuppressWarnings("unchecked")
                Whole<Object> streamhandler = (Whole<Object>)handler;
                Decoder.TextStream<?> streamdecoder = (Decoder.TextStream<?>)decoderWrapper.getDecoder();
//                setMessageAppender(MessageType.BINARY,new JsrReaderMessage(streamhandler, streamdecoder, websocket, getExecutor()));
            }
        }
    }
    
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public void addMessageHandler(MessageHandler handler) throws IllegalStateException
    {
        Objects.requireNonNull(handler, "MessageHandler cannot be null");
        Class<? extends MessageHandler> handlerClass = handler.getClass(); 
        
        if (MessageHandler.Whole.class.isAssignableFrom(handlerClass))
        {
            Class<?> onMessageClass = ReflectUtils.findGenericClassFor(handlerClass,MessageHandler.Whole.class);
            addMessageHandler(onMessageClass,(Whole)handler);
        }
        
        if (MessageHandler.Partial.class.isAssignableFrom(handlerClass))
        {
            Class<?> onMessageClass = ReflectUtils.findGenericClassFor(handlerClass,MessageHandler.Partial.class);
            addMessageHandler(onMessageClass,(Partial)handler);
        }
    }
    
    private void setMessageAppender(MessageType type, MessageSink appender)
    {
//        synchronized(messageAppenders)
//        {
//            MessageSink other = messageAppenders[type.ordinal()];
//            if (other != null)
//            {
//                StringBuilder err = new StringBuilder();
//                err.append("Encountered duplicate MessageHandler handling for ");
//                err.append(type.name()).append(" type messages.  ");
//                err.append(wrapper.getMetadata().getObjectType().getName());
//                err.append(">, ").append(metadata.getHandlerClass().getName());
//                err.append("<");
//                err.append(metadata.getMessageClass().getName());
//                err.append("> and ");
//                err.append(other.getMetadata().getHandlerClass().getName());
//                err.append("<");
//                err.append(other.getMetadata().getMessageClass().getName());
//                err.append("> both implement this message type");
//                throw new IllegalStateException(err.toString());
//            }
//        }
    }


    private void addMessageAppender(Class<?> clazz, MessageHandler handler)
    {
//        synchronized(messageAppenders)
//        {
//            // TODO Auto-generated method stub
//        }
    }

    private void addMessageHandlerWrapper(Class<?> msgClazz, MessageHandler handler) throws IllegalStateException
    {
//        Objects.requireNonNull(handler, "MessageHandler cannot be null");
//
//        synchronized (wrappers)
//        {
//            for (MessageHandlerMetadata metadata : messageHandlerFactory.getMetadata(msgClazz))
//            {
//                DecoderFactory.Wrapper wrapper = decoderFactory.getWrapperFor(metadata.getMessageClass());
//                if (wrapper == null)
//                {
//                    StringBuilder err = new StringBuilder();
//                    err.append("Unable to find decoder for type <");
//                    err.append(metadata.getMessageClass().getName());
//                    err.append("> used in <");
//                    err.append(metadata.getHandlerClass().getName());
//                    err.append(">");
//                    throw new IllegalStateException(err.toString());
//                }
//
//                MessageType key = wrapper.getMetadata().getMessageType();
//                MessageHandlerWrapper other = wrappers[key.ordinal()];
//                if (other != null)
//                {
//                    StringBuilder err = new StringBuilder();
//                    err.append("Encountered duplicate MessageHandler handling message type <");
//                    err.append(wrapper.getMetadata().getObjectType().getName());
//                    err.append(">, ").append(metadata.getHandlerClass().getName());
//                    err.append("<");
//                    err.append(metadata.getMessageClass().getName());
//                    err.append("> and ");
//                    err.append(other.getMetadata().getHandlerClass().getName());
//                    err.append("<");
//                    err.append(other.getMetadata().getMessageClass().getName());
//                    err.append("> both implement this message type");
//                    throw new IllegalStateException(err.toString());
//                }
//                else
//                {
//                    MessageHandlerWrapper handlerWrapper = new MessageHandlerWrapper(handler,metadata,wrapper);
//                    wrappers[key.ordinal()] = handlerWrapper;
//                }
//            }
//
//            // Update handlerSet
//            updateMessageHandlerSet();
//        }
    }
    
    @Override
    public void close(CloseReason closeReason) throws IOException
    {
        close(closeReason.getCloseCode().getCode(),closeReason.getReasonPhrase());
    }

    @Override
    public Async getAsyncRemote()
    {
        if (asyncRemote == null)
        {
            asyncRemote = new JsrAsyncRemote(this);
        }
        return asyncRemote;
    }

    @Override
    public Basic getBasicRemote()
    {
        if (basicRemote == null)
        {
            basicRemote = new JsrBasicRemote(this);
        }
        return basicRemote;
    }

    @Override
    public WebSocketContainer getContainer()
    {
        return this.container;
    }

    public DecoderFactory getDecoderFactory()
    {
        return decoderFactory;
    }

    public EncoderFactory getEncoderFactory()
    {
        return encoderFactory;
    }

    public EndpointConfig getEndpointConfig()
    {
        return config;
    }

//    public EndpointMetadata getEndpointMetadata()
//    {
//        return metadata;
//    }

    @Override
    public String getId()
    {
        return this.id;
    }

    @Override
    public int getMaxBinaryMessageBufferSize()
    {
        return getPolicy().getMaxBinaryMessageSize();
    }

    @Override
    public long getMaxIdleTimeout()
    {
        return getPolicy().getIdleTimeout();
    }

    @Override
    public int getMaxTextMessageBufferSize()
    {
        return getPolicy().getMaxTextMessageSize();
    }

    @Override
    public Set<MessageHandler> getMessageHandlers()
    {
        // Always return copy of set, as it is common to iterate and remove from the real set.
        return new HashSet<MessageHandler>(messageHandlerSet);
    }

    @Override
    public List<Extension> getNegotiatedExtensions()
    {
        if (negotiatedExtensions == null)
        {
            negotiatedExtensions = new ArrayList<Extension>();
            for (ExtensionConfig cfg : getUpgradeResponse().getExtensions())
            {
                negotiatedExtensions.add(new JsrExtension(cfg));
            }
        }
        return negotiatedExtensions;
    }

    @Override
    public String getNegotiatedSubprotocol()
    {
        String acceptedSubProtocol = getUpgradeResponse().getAcceptedSubProtocol();
        if (acceptedSubProtocol == null)
        {
            return "";
        }
        return acceptedSubProtocol;
    }

    @Override
    public Set<Session> getOpenSessions()
    {
        return container.getOpenSessions();
    }

    @Override
    public Map<String, String> getPathParameters()
    {
        return Collections.unmodifiableMap(pathParameters);
    }

    @Override
    public String getQueryString()
    {
        return getUpgradeRequest().getRequestURI().getQuery();
    }

    @Override
    public Map<String, List<String>> getRequestParameterMap()
    {
        return getUpgradeRequest().getParameterMap();
    }

    @Override
    public Principal getUserPrincipal()
    {
        return getUpgradeRequest().getUserPrincipal();
    }

    @Override
    public Map<String, Object> getUserProperties()
    {
        return config.getUserProperties();
    }

    @Override
    public void init(EndpointConfig config)
    {
        // Initialize encoders
        encoderFactory.init(config);
        // Initialize decoders
        decoderFactory.init(config);
    }

    @Override
    public void removeMessageHandler(MessageHandler handler)
    {
//        synchronized (wrappers)
//        {
//            try
//            {
//                for (MessageHandlerMetadata metadata : messageHandlerFactory.getMetadata(handler.getClass()))
//                {
//                    DecoderMetadata decoder = decoderFactory.getMetadataFor(metadata.getMessageClass());
//                    MessageType key = decoder.getMessageType();
//                    wrappers[key.ordinal()] = null;
//                }
//                updateMessageHandlerSet();
//            }
//            catch (IllegalStateException e)
//            {
//                LOG.warn("Unable to identify MessageHandler: " + handler.getClass().getName(),e);
//            }
//        }
    }
    
    public MessageSink newMessageAppenderFor(MessageType text)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void setMaxBinaryMessageBufferSize(int length)
    {
        getPolicy().setMaxBinaryMessageSize(length);
        getPolicy().setMaxBinaryMessageBufferSize(length);
    }

    @Override
    public void setMaxIdleTimeout(long milliseconds)
    {
        getPolicy().setIdleTimeout(milliseconds);
        super.setIdleTimeout(milliseconds);
    }

    @Override
    public void setMaxTextMessageBufferSize(int length)
    {
        getPolicy().setMaxTextMessageSize(length);
        getPolicy().setMaxTextMessageBufferSize(length);
    }

    public void setPathParameters(Map<String, String> pathParams)
    {
        this.pathParameters.clear();
        if (pathParams != null)
        {
            this.pathParameters.putAll(pathParams);
        }
    }

    private void updateMessageHandlerSet()
    {
        messageHandlerSet.clear();
//        for (MessageHandlerWrapper wrapper : wrappers)
//        {
//            if (wrapper == null)
//            {
//                // skip empty
//                continue;
//            }
//            messageHandlerSet.add(wrapper.getHandler());
//        }
    }

    @Override
    public BatchMode getBatchMode()
    {
        // JSR 356 specification mandates default batch mode to be off.
        return BatchMode.OFF;
    }

}
