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

public abstract class JsrMetadata<T extends Annotation>
{
    public final Class<?> pojo;
    public Decoders decoders;
    public Encoders encoders;

    /**
     * Callable for &#064;{@link OnOpen} annotation
     */
    public ParameterizedMethod onOpen;

    /**
     * Callable for &#064;{@link OnClose} annotation
     */
    public ParameterizedMethod onClose;

    /**
     * Callable for &#064;{@link OnError} annotation
     */
    public ParameterizedMethod onError;

    /**
     * Callable for &#064;{@link OnMessage} annotation dealing with Text Message Format
     */
    public ParameterizedMethod onText;

    /**
     * Callable for &#064;{@link OnMessage} annotation dealing with Binary Message Format
     */
    public ParameterizedMethod onBinary;

    /**
     * Callable for &#064;{@link OnMessage} annotation dealing with Pong Message Format
     */
    public ParameterizedMethod onPong;

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
