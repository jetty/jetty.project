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

import java.util.ArrayList;
import java.util.List;

import javax.websocket.MessageHandler;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.jsr356.DecoderWrapper;
import org.eclipse.jetty.websocket.jsr356.Decoders;
import org.eclipse.jetty.websocket.jsr356.MessageType;
import org.eclipse.jetty.websocket.jsr356.utils.ReflectUtils;

/**
 * Creates {@link MessageHandlerMetadata} objects from a provided {@link MessageHandler} classes.
 */
public class MessageHandlerMetadataFactory
{
    private static final Logger LOG = Log.getLogger(MessageHandlerMetadataFactory.class);
    private final Decoders decoders;

    public MessageHandlerMetadataFactory(Decoders decoders)
    {
        this.decoders = decoders;
    }

    public DecoderWrapper getDecoderWrapper(Class<?> onMessageClass)
    {
        return decoders.getDecoderWrapper(onMessageClass);
    }

    public List<MessageHandlerMetadata> getMetadata(Class<? extends MessageHandler> handler) throws IllegalStateException
    {
        List<MessageHandlerMetadata> ret = new ArrayList<>();
        boolean partial = false;
        if (MessageHandler.Partial.class.isAssignableFrom(handler))
        {
            LOG.debug("supports Partial: {}",handler);
            partial = true;
            Class<?> onMessageClass = ReflectUtils.findGenericClassFor(handler,MessageHandler.Partial.class);
            LOG.debug("Partial message class: {}",onMessageClass);
            MessageType onMessageType = identifyMessageType(onMessageClass);
            LOG.debug("Partial message type: {}",onMessageType);
            ret.add(new MessageHandlerMetadata(handler,onMessageType,onMessageClass,partial));
        }
        if (MessageHandler.Whole.class.isAssignableFrom(handler))
        {
            LOG.debug("supports Whole: {}",handler.getName());
            partial = false;
            Class<?> onMessageClass = ReflectUtils.findGenericClassFor(handler,MessageHandler.Whole.class);
            LOG.debug("Whole message class: {}",onMessageClass);
            MessageType onMessageType = identifyMessageType(onMessageClass);
            LOG.debug("Whole message type: {}",onMessageType);
            MessageHandlerMetadata metadata = new MessageHandlerMetadata(handler,onMessageType,onMessageClass,partial);
            ret.add(metadata);
        }
        return ret;
    }

    private MessageType identifyMessageType(Class<?> onMessageClass) throws IllegalStateException
    {
        DecoderWrapper wrapper = getDecoderWrapper(onMessageClass);
        return wrapper.getMetadata().getMessageType();
    }
}
