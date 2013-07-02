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

package org.eclipse.jetty.websocket.jsr356.metadata;

import javax.websocket.Encoder;

import org.eclipse.jetty.websocket.jsr356.MessageType;

/**
 * Immutable Metadata for a {@link Encoder}
 */
public class EncoderMetadata
{
    /** The Class for the Encoder itself */
    private final Class<? extends Encoder> encoderClass;
    /** The Class that the Encoder declares it encodes */
    private final Class<?> objType;
    /** The Basic type of message the encoder handles */
    private final MessageType messageType;
    /** Flag indicating if Encoder is for streaming (or not) */
    private final boolean streamed;

    public EncoderMetadata(Class<?> objType, Class<? extends Encoder> encoderClass, MessageType messageType, boolean streamed)
    {
        this.objType = objType;
        this.encoderClass = encoderClass;
        this.messageType = messageType;
        this.streamed = streamed;
    }

    public Class<? extends Encoder> getEncoderClass()
    {
        return encoderClass;
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