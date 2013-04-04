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

package org.eclipse.jetty.websocket.jsr356.messages;

import javax.websocket.MessageHandler;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.jsr356.MessageType;

/**
 * An immutable holder for {@link MessageHandler} metadata, representing a single interface on a message handling class.
 * <p>
 * A message handling class can contain more than 1 valid {@link MessageHandler} interface, this will result in multiple {@link MessageHandlerMetadata}
 * instances, each tracking one of the {@link MessageHandler} interfaces declared.
 */
public class MessageHandlerMetadata
{
    /**
     * The implemented MessageHandler class.
     * <p>
     * Commonly a end-user provided class, with 1 or more implemented {@link MessageHandler} interfaces
     */
    private final Class<? extends MessageHandler> handlerClass;
    /**
     * Indicator if this is a {@link MessageHandler.Partial} or {@link MessageHandler.Whole} interface.
     * <p>
     * True for MessageHandler.Partial, other wise its a MessageHandler.Whole
     */
    private final boolean isPartialSupported;
    /**
     * The class type that this specific interface's generic implements.
     * <p>
     * Or said another way, the first parameter type on this interface's onMessage() method.
     */
    private final Class<?> messageClass;
    /**
     * The 'websocket message type' used for registration limits per JSR-356 / PFD1 section 2.1.3 Receiving Messages
     */
    private final MessageType messageType;

    protected MessageHandlerMetadata(Class<? extends MessageHandler> handlerClass, MessageType messageType, Class<?> messageClass, boolean partial)
    {
        this.handlerClass = handlerClass;
        this.isPartialSupported = partial;
        this.messageClass = messageClass;
        this.messageType = messageType;
    }

    /**
     * Make equals/hashcode only use messageType (for best result with {@link Session#getMessageHandlers()})
     */
    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (obj == null)
        {
            return false;
        }
        if (getClass() != obj.getClass())
        {
            return false;
        }
        MessageHandlerMetadata other = (MessageHandlerMetadata)obj;
        if (messageType != other.messageType)
        {
            return false;
        }
        return true;
    }

    public Class<? extends MessageHandler> getHandlerClass()
    {
        return handlerClass;
    }

    public Class<?> getMessageClass()
    {
        return messageClass;
    }

    public MessageType getMessageType()
    {
        return messageType;
    }

    /**
     * Make equals/hashcode only use messageType (for best result with {@link Session#getMessageHandlers()})
     */
    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = (prime * result) + ((messageType == null)?0:messageType.hashCode());
        return result;
    }

    public boolean isPartialSupported()
    {
        return isPartialSupported;
    }
}
