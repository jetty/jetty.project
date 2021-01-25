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

package org.eclipse.jetty.websocket.jsr356;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.websocket.MessageHandler;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;
import org.eclipse.jetty.websocket.jsr356.metadata.MessageHandlerMetadata;

/**
 * Factory for {@link MessageHandlerMetadata}
 */
public class MessageHandlerFactory
{
    private static final Logger LOG = Log.getLogger(MessageHandlerFactory.class);
    /**
     * Registered MessageHandlers at this level
     */
    private Map<Class<? extends MessageHandler>, List<MessageHandlerMetadata>> registered;

    public MessageHandlerFactory()
    {
        registered = new ConcurrentHashMap<>();
    }

    public List<MessageHandlerMetadata> getMetadata(Class<? extends MessageHandler> handler) throws IllegalStateException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("getMetadata({})", handler);
        }
        List<MessageHandlerMetadata> ret = registered.get(handler);
        if (ret != null)
        {
            return ret;
        }

        return register(handler);
    }

    public List<MessageHandlerMetadata> register(Class<? extends MessageHandler> handler)
    {
        List<MessageHandlerMetadata> metadatas = new ArrayList<>();

        boolean partial = false;

        if (MessageHandler.Partial.class.isAssignableFrom(handler))
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("supports Partial: {}", handler);
            }
            partial = true;
            Class<?> onMessageClass = ReflectUtils.findGenericClassFor(handler, MessageHandler.Partial.class);
            if (LOG.isDebugEnabled())
            {
                LOG.debug("Partial message class: {}", onMessageClass);
            }
            metadatas.add(new MessageHandlerMetadata(handler, onMessageClass, partial));
        }

        if (MessageHandler.Whole.class.isAssignableFrom(handler))
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("supports Whole: {}", handler.getName());
            }
            partial = false;
            Class<?> onMessageClass = ReflectUtils.findGenericClassFor(handler, MessageHandler.Whole.class);
            if (LOG.isDebugEnabled())
            {
                LOG.debug("Whole message class: {}", onMessageClass);
            }
            metadatas.add(new MessageHandlerMetadata(handler, onMessageClass, partial));
        }

        registered.put(handler, metadatas);
        return metadatas;
    }
}
