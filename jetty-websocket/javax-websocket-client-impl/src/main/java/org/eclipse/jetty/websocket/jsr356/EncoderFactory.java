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

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.eclipse.jetty.websocket.common.scopes.WebSocketSessionScope;
import org.eclipse.jetty.websocket.jsr356.metadata.EncoderMetadata;
import org.eclipse.jetty.websocket.jsr356.metadata.EncoderMetadataSet;

/**
 * Represents all of the declared {@link Encoder}s that the Container is aware of.
 */
public class EncoderFactory implements Configurable
{
    public static class Wrapper implements Configurable
    {
        private final Encoder encoder;
        private final EncoderMetadata metadata;

        private Wrapper(Encoder encoder, EncoderMetadata metadata)
        {
            this.encoder = encoder;
            this.metadata = metadata;
        }

        public Encoder getEncoder()
        {
            return encoder;
        }

        public EncoderMetadata getMetadata()
        {
            return metadata;
        }

        @Override
        public void init(EndpointConfig config)
        {
            this.encoder.init(config);
        }

        @Override
        public void destroy()
        {
            this.encoder.destroy();
        }
    }

    private static final Logger LOG = Log.getLogger(EncoderFactory.class);

    private final EncoderMetadataSet metadatas;
    private final WebSocketContainerScope containerScope;
    private final Map<Class<?>, Wrapper> activeWrappers;
    private final EncoderFactory parentFactory;
    private EndpointConfig endpointConfig;

    public EncoderFactory(WebSocketContainerScope containerScope, EncoderMetadataSet metadatas)
    {
        this(containerScope, metadatas, null);
    }

    public EncoderFactory(WebSocketSessionScope sessionScope, EncoderMetadataSet metadatas, EncoderFactory parentFactory)
    {
        this(sessionScope.getContainerScope(), metadatas, parentFactory);
    }

    protected EncoderFactory(WebSocketContainerScope containerScope, EncoderMetadataSet metadatas, EncoderFactory parentFactory)
    {
        Objects.requireNonNull(containerScope, "Container Scope cannot be null");
        this.containerScope = containerScope;
        this.metadatas = metadatas;
        this.activeWrappers = new ConcurrentHashMap<>();
        this.parentFactory = parentFactory;
    }

    public Encoder getEncoderFor(Class<?> type)
    {
        Wrapper wrapper = getWrapperFor(type);
        if (wrapper == null)
        {
            return null;
        }
        return wrapper.encoder;
    }

    public EncoderMetadata getMetadataFor(Class<?> type)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("getMetadataFor({})", type);
        }
        EncoderMetadata metadata = metadatas.getMetadataByType(type);

        if (metadata != null)
        {
            return metadata;
        }

        if (parentFactory != null)
        {
            return parentFactory.getMetadataFor(type);
        }

        return null;
    }

    public Wrapper getWrapperFor(Class<?> type)
    {
        synchronized (activeWrappers)
        {
            Wrapper wrapper = activeWrappers.get(type);

            // Try parent (if needed)
            if ((wrapper == null) && (parentFactory != null))
            {
                wrapper = parentFactory.getWrapperFor(type);
            }

            if (wrapper == null)
            {
                // Attempt to create Wrapper on demand
                EncoderMetadata metadata = metadatas.getMetadataByType(type);
                if (metadata == null)
                {
                    return null;
                }
                wrapper = newWrapper(metadata);
                // track wrapper
                activeWrappers.put(type, wrapper);
            }

            return wrapper;
        }
    }

    @Override
    public void init(EndpointConfig config)
    {
        this.endpointConfig = config;
        if (LOG.isDebugEnabled())
            LOG.debug("init({})", endpointConfig);

        // Instantiate all declared encoders
        for (EncoderMetadata metadata : metadatas)
        {
            Wrapper wrapper = newWrapper(metadata);
            activeWrappers.put(metadata.getObjectType(), wrapper);
        }
    }

    @Override
    public void destroy()
    {
        for (Wrapper wrapper : activeWrappers.values())
        {
            wrapper.encoder.destroy();
        }

        activeWrappers.clear();
    }

    private Wrapper newWrapper(EncoderMetadata metadata)
    {
        if (endpointConfig == null)
            throw new IllegalStateException("EndpointConfig not set");

        Class<? extends Encoder> encoderClass = metadata.getCoderClass();
        try
        {
            Encoder encoder = containerScope.getObjectFactory().createInstance(encoderClass);
            encoder.init(endpointConfig);
            return new Wrapper(encoder, metadata);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Unable to instantiate Encoder: " + encoderClass.getName(), e);
        }
    }
}
