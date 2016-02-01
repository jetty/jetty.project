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

package org.eclipse.jetty.websocket.jsr356.metadata;

import org.eclipse.jetty.websocket.jsr356.MessageType;

/**
 * The immutable base metadata for a coder ({@link javax.websocket.Decoder} or {@link javax.websocket.Encoder}
 * 
 * @param <T>
 *            the specific type of coder ({@link javax.websocket.Decoder} or {@link javax.websocket.Encoder}
 */
public abstract class CoderMetadata<T>
{
    /** The class for the Coder */
    private final Class<? extends T> coderClass;
    /** The Class that the Decoder declares it decodes */
    private final Class<?> objType;
    /** The Basic type of message the decoder handles */
    private final MessageType messageType;
    /** Flag indicating if Decoder is for streaming (or not) */
    private final boolean streamed;

    public CoderMetadata(Class<? extends T> coderClass, Class<?> objType, MessageType messageType, boolean streamed)
    {
        this.objType = objType;
        this.coderClass = coderClass;
        this.messageType = messageType;
        this.streamed = streamed;
    }

    public Class<? extends T> getCoderClass()
    {
        return this.coderClass;
    }

    public MessageType getMessageType()
    {
        return messageType;
    }

    public Class<?> getObjectType()
    {
        return objType;
    }

    public boolean isStreamed()
    {
        return streamed;
    }
}
