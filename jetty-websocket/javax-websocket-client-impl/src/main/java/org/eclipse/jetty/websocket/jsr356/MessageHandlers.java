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

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import javax.websocket.MessageHandler;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.jsr356.messages.MessageHandlerMetadata;
import org.eclipse.jetty.websocket.jsr356.messages.MessageHandlerMetadataFactory;
import org.eclipse.jetty.websocket.jsr356.messages.MessageHandlerWrapper;

/**
 * Facade around {@link MessageHandlerMetadataFactory} with {@link MessageType} tracking and enforced JSR-356 PFD1 rules and limits around websocket message
 * type
 */
public class MessageHandlers
{
    private static final Logger LOG = Log.getLogger(MessageHandlers.class);

    /**
     * Factory for MessageHandlerMetadata instances.
     */
    private MessageHandlerMetadataFactory factory;
    /**
     * Array of MessageHandlerWrappers, indexed by {@link MessageType#ordinal()}
     */
    private final MessageHandlerWrapper wrappers[];

    public MessageHandlers()
    {
        this.wrappers = new MessageHandlerWrapper[MessageType.values().length];
    }

    public void add(MessageHandler handler)
    {
        assertFactoryDefined();
        Objects.requireNonNull(handler,"MessageHandler cannot be null");

        synchronized (wrappers)
        {
            for (MessageHandlerMetadata metadata : factory.getMetadata(handler.getClass()))
            {
                MessageType key = metadata.getMessageType();
                MessageHandlerWrapper other = wrappers[key.ordinal()];
                if (other != null)
                {
                    StringBuilder err = new StringBuilder();
                    err.append("Encountered duplicate MessageHandler handling message type <");
                    err.append(metadata.getMessageType().name());
                    err.append(">, ").append(metadata.getHandlerClass().getName());
                    err.append("<");
                    err.append(metadata.getMessageClass().getName());
                    err.append("> and ");
                    err.append(other.getMetadata().getHandlerClass().getName());
                    err.append("<");
                    err.append(other.getMetadata().getMessageClass().getName());
                    err.append("> both implement this message type");
                    throw new IllegalStateException(err.toString());
                }
                else
                {
                    DecoderWrapper decoder = factory.getDecoderWrapper(metadata.getMessageClass());
                    MessageHandlerWrapper wrapper = new MessageHandlerWrapper(handler,metadata,decoder);
                    wrappers[key.ordinal()] = wrapper;
                }
            }
        }
    }

    private void assertFactoryDefined()
    {
        if (this.factory == null)
        {
            throw new IllegalStateException("MessageHandlerMetadataFactory has not been set");
        }
    }

    public MessageHandlerMetadataFactory getFactory()
    {
        return factory;
    }

    public Set<MessageHandler> getUnmodifiableHandlerSet()
    {
        Set<MessageHandler> ret = new HashSet<>();
        for (MessageHandlerWrapper wrapper : wrappers)
        {
            if (wrapper == null)
            {
                // skip empty
                continue;
            }
            ret.add(wrapper.getHandler());
        }
        return Collections.unmodifiableSet(ret);
    }

    public MessageHandlerWrapper getWrapper(MessageType msgType)
    {
        synchronized (wrappers)
        {
            return wrappers[msgType.ordinal()];
        }
    }

    public void remove(MessageHandler handler)
    {
        assertFactoryDefined();

        try
        {
            for (MessageHandlerMetadata metadata : factory.getMetadata(handler.getClass()))
            {
                wrappers[metadata.getMessageType().ordinal()] = null;
            }
        }
        catch (IllegalStateException e)
        {
            LOG.warn("Unable to identify MessageHandler: " + handler.getClass().getName(),e);
        }
    }

    public void setFactory(MessageHandlerMetadataFactory factory)
    {
        this.factory = factory;
    }
}
