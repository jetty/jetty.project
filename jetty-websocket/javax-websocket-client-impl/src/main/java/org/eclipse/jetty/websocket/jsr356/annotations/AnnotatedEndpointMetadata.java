//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.jsr356.annotations;

import java.lang.annotation.Annotation;
import java.util.LinkedList;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;

import org.eclipse.jetty.websocket.jsr356.metadata.DecoderMetadata;
import org.eclipse.jetty.websocket.jsr356.metadata.DecoderMetadataSet;
import org.eclipse.jetty.websocket.jsr356.metadata.EncoderMetadataSet;
import org.eclipse.jetty.websocket.jsr356.metadata.EndpointMetadata;

/**
 * Static reference to a specific annotated classes metadata.
 *
 * @param <T> the annotation this metadata is based off of
 * @param <C> the endpoint configuration this is based off of
 */
public abstract class AnnotatedEndpointMetadata<T extends Annotation, C extends EndpointConfig> implements EndpointMetadata
{
    /**
     * Callable for &#064;{@link OnOpen} annotation.
     */
    public OnOpenCallable onOpen;

    /**
     * Callable for &#064;{@link OnClose} annotation
     */
    public OnCloseCallable onClose;

    /**
     * Callable for &#064;{@link OnError} annotation
     */
    public OnErrorCallable onError;

    /**
     * Callable for &#064;{@link OnMessage} annotation dealing with Text Message Format
     */
    public OnMessageTextCallable onText;

    /**
     * Callable for &#064;{@link OnMessage} annotation dealing with Text Streaming Message Format
     */
    public OnMessageTextStreamCallable onTextStream;

    /**
     * Callable for &#064;{@link OnMessage} annotation dealing with Binary Message Format
     */
    public OnMessageBinaryCallable onBinary;

    /**
     * Callable for &#064;{@link OnMessage} annotation dealing with Binary Streaming Message Format
     */
    public OnMessageBinaryStreamCallable onBinaryStream;

    /**
     * Callable for &#064;{@link OnMessage} annotation dealing with Pong Message Format
     */
    public OnMessagePongCallable onPong;

    private final Class<?> endpointClass;
    private DecoderMetadataSet decoders;
    private EncoderMetadataSet encoders;
    private long maxTextMessageSize = -1;
    private long maxBinaryMessageSize = -1;

    protected AnnotatedEndpointMetadata(Class<?> endpointClass)
    {
        this.endpointClass = endpointClass;
        this.decoders = new DecoderMetadataSet();
        this.encoders = new EncoderMetadataSet();
    }

    public void customizeParamsOnClose(LinkedList<IJsrParamId> params)
    {
        /* do nothing */
    }

    public void customizeParamsOnError(LinkedList<IJsrParamId> params)
    {
        /* do nothing */
    }

    public void customizeParamsOnMessage(LinkedList<IJsrParamId> params)
    {
        for (DecoderMetadata metadata : decoders)
        {
            params.add(new JsrParamIdDecoder(metadata));
        }
    }

    public void customizeParamsOnOpen(LinkedList<IJsrParamId> params)
    {
        /* do nothing */
    }

    public abstract T getAnnotation();

    public abstract C getConfig();

    @Override
    public long maxBinaryMessageSize()
    {
        return maxBinaryMessageSize;
    }

    @Override
    public long maxTextMessageSize()
    {
        return maxTextMessageSize;
    }

    @Override
    public DecoderMetadataSet getDecoders()
    {
        return decoders;
    }

    @Override
    public EncoderMetadataSet getEncoders()
    {
        return encoders;
    }

    @Override
    public Class<?> getEndpointClass()
    {
        return endpointClass;
    }

    public void setMaxBinaryMessageSize(long maxBinaryMessageSize)
    {
        this.maxBinaryMessageSize = maxBinaryMessageSize;
    }

    public void setMaxTextMessageSize(long maxTextMessageSize)
    {
        this.maxTextMessageSize = maxTextMessageSize;
    }
}
