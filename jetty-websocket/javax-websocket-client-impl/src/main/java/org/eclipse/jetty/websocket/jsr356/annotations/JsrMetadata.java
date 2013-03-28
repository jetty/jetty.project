//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;

import org.eclipse.jetty.websocket.jsr356.decoders.Decoders;
import org.eclipse.jetty.websocket.jsr356.encoders.Encoders;

/**
 * Static reference to a specific annotated classes metadata.
 * 
 * @param <T>
 *            the annotation this metadata is based off of
 */
public abstract class JsrMetadata<T extends Annotation>
{
    /**
     * The actual class that this metadata belongs to
     */
    public final Class<?> pojo;

    /**
     * Decoders declared as part of annotations
     */
    public Decoders decoders;

    /**
     * Encoders declared as part of annotations
     */
    public Encoders encoders;

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

    protected JsrMetadata(Class<?> websocket)
    {
        this.pojo = websocket;
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
        /* do nothing */
    }

    public void customizeParamsOnOpen(LinkedList<IJsrParamId> params)
    {
        /* do nothing */
    }

    public abstract T getAnnotation();
}
