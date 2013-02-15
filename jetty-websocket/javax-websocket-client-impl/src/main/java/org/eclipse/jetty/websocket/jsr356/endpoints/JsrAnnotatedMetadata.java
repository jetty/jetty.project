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

package org.eclipse.jetty.websocket.jsr356.endpoints;

import javax.websocket.WebSocketClose;
import javax.websocket.WebSocketError;
import javax.websocket.WebSocketMessage;
import javax.websocket.WebSocketOpen;

import org.eclipse.jetty.websocket.common.events.annotated.CallableMethod;

/**
 * Represents the metadata associated with Annotation discovery of a specific class.
 */
public class JsrAnnotatedMetadata
{
    /**
     * Callable for &#064;{@link WebSocketOpen} annotation
     */
    public CallableMethod onOpen;
    /**
     * Callable for &#064;{@link WebSocketClose} annotation
     */
    public CallableMethod onClose;
    /**
     * Callable for &#064;{@link WebSocketError} annotation
     */
    public CallableMethod onError;
    /**
     * Callable for &#064;{@link WebSocketMessage} annotation dealing with Text Message Format
     */
    public CallableMethod onText;
    /**
     * Callable for &#064;{@link WebSocketMessage} annotation dealing with Binary Message Format
     */
    public CallableMethod onBinary;
    /**
     * Callable for &#064;{@link WebSocketMessage} annotation dealing with Pong Message Format
     */
    public CallableMethod onPong;
}
