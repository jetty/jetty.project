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

package org.eclipse.jetty.websocket.jsr356;

import javax.websocket.Decoder;

/**
 * Metadata for a {@link Decoder}
 */
public class DecoderMetadata
{
    private final Class<?> objType;
    private final Class<? extends Decoder> decoder;
    private final MessageType messageType;
    private final boolean streamed;

    public DecoderMetadata(Class<?> objType, Class<? extends Decoder> decoder, MessageType messageType, boolean streamed)
    {
        this.objType = objType;
        this.decoder = decoder;
        this.messageType = messageType;
        this.streamed = streamed;
    }

    public Class<? extends Decoder> getDecoder()
    {
        return decoder;
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