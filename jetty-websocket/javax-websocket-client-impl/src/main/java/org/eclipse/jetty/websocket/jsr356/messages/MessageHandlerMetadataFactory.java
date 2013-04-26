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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.websocket.MessageHandler;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.jsr356.DecoderWrapper;
import org.eclipse.jetty.websocket.jsr356.Decoders;
import org.eclipse.jetty.websocket.jsr356.MessageType;

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

    private Class<?> findOnMessageType(Class<? extends MessageHandler> handlerClass, int paramCount)
    {
        LOG.debug("findOnMessageType({}, {})",handlerClass,paramCount);
        for (Method method : handlerClass.getMethods())
        {
            if ("onMessage".equals(method.getName()))
            {
                LOG.debug("found {}",method);
                // make sure we only look for the onMessage method that is relevant
                Class<?> paramTypes[] = method.getParameterTypes();
                if (paramTypes == null)
                {
                    // skip
                    continue;
                }

                if (paramTypes.length == paramCount)
                {
                    // found the method we are interested in
                    return paramTypes[0];
                }
            }
        }

        return null;
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
            Class<?> onMessageClass = getOnMessagePartialType(handler);
            LOG.debug("Partial message class: {}",onMessageClass);
            MessageType onMessageType = identifyMessageType(onMessageClass);
            LOG.debug("Partial message type: {}",onMessageType);
            ret.add(new MessageHandlerMetadata(handler,onMessageType,onMessageClass,partial));
        }
        if (MessageHandler.Whole.class.isAssignableFrom(handler))
        {
            LOG.debug("supports Whole: {}",handler.getName());
            partial = false;
            Class<?> onMessageClass = getOnMessageType(handler);
            LOG.debug("Whole message class: {}",onMessageClass);
            MessageType onMessageType = identifyMessageType(onMessageClass);
            LOG.debug("Whole message type: {}",onMessageType);
            MessageHandlerMetadata metadata = new MessageHandlerMetadata(handler,onMessageType,onMessageClass,partial);
            ret.add(metadata);
        }
        return ret;
    }

    private Class<?> getOnMessagePartialType(Class<? extends MessageHandler> handlerClass)
    {
        Objects.requireNonNull(handlerClass);

        if (MessageHandler.Partial.class.isAssignableFrom(handlerClass))
        {
            return findOnMessageType(handlerClass,2);
        }

        return null;
    }

    private Class<?> getOnMessageType(Class<? extends MessageHandler> handlerClass)
    {
        Objects.requireNonNull(handlerClass);

        if (MessageHandler.Whole.class.isAssignableFrom(handlerClass))
        {
            return findOnMessageType(handlerClass,1);
        }

        return null;
    }

    private MessageType identifyMessageType(Class<?> onMessageClass) throws IllegalStateException
    {
        DecoderWrapper wrapper = getDecoderWrapper(onMessageClass);
        return wrapper.getMetadata().getMessageType();
    }
}
