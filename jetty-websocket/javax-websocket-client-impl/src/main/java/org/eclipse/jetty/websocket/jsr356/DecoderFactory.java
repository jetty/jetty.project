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

package org.eclipse.jetty.websocket.jsr356;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.eclipse.jetty.websocket.common.scopes.WebSocketSessionScope;
import org.eclipse.jetty.websocket.jsr356.metadata.DecoderMetadata;
import org.eclipse.jetty.websocket.jsr356.metadata.DecoderMetadataSet;

/**
 * Factory for {@link DecoderMetadata}
 * <p>
 * Relies on search order of parent {@link DecoderFactory} instances as such.
 * <ul>
 * <li>From Static DecoderMetadataSet (based on data in annotations and static EndpointConfig)</li>
 * <li>From Composite DecoderMetadataSet (based static and instance specific EndpointConfig)</li>
 * <li>Container declared DecoderMetadataSet (primitives)</li>
 * </ul>
 */
public class DecoderFactory implements Configurable
{
    public static class Wrapper implements Configurable
    {
        private final Decoder decoder;
        private final DecoderMetadata metadata;

        private Wrapper(Decoder decoder, DecoderMetadata metadata)
        {
            this.decoder = decoder;
            this.metadata = metadata;
        }

        public Decoder getDecoder()
        {
            return decoder;
        }

        public DecoderMetadata getMetadata()
        {
            return metadata;
        }

        @Override
        public void init(EndpointConfig config)
        {
            this.decoder.init(config);
        }
    }

    private static final Logger LOG = Log.getLogger(DecoderFactory.class);

    private final DecoderMetadataSet metadatas;
    private final WebSocketContainerScope containerScope;
    private DecoderFactory parentFactory;
    private Map<Class<?>, Wrapper> activeWrappers;

    public DecoderFactory(WebSocketContainerScope containerScope, DecoderMetadataSet metadatas)
    {
        this(containerScope,metadatas,null);
    }
    
    public DecoderFactory(WebSocketSessionScope sessionScope, DecoderMetadataSet metadatas, DecoderFactory parentFactory)
    {
        this(sessionScope.getContainerScope(),metadatas,parentFactory);
    }

    protected DecoderFactory(WebSocketContainerScope containerScope, DecoderMetadataSet metadatas, DecoderFactory parentFactory)
    {
        Objects.requireNonNull(containerScope,"Container Scope cannot be null");
        this.containerScope = containerScope;
        this.metadatas = metadatas;
        this.activeWrappers = new ConcurrentHashMap<>();
        this.parentFactory = parentFactory;
    }

    public Decoder getDecoderFor(Class<?> type)
    {
        Wrapper wrapper = getWrapperFor(type);
        if (wrapper == null)
        {
            return null;
        }
        return wrapper.decoder;
    }

    public DecoderMetadata getMetadataFor(Class<?> type)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("getMetadataFor({})",type);
        }
        
        DecoderMetadata metadata = metadatas.getMetadataByType(type);

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
                DecoderMetadata metadata = metadatas.getMetadataByType(type);
                if (metadata == null)
                {
                    return null;
                }
                wrapper = newWrapper(metadata);
                // track wrapper
                activeWrappers.put(type,wrapper);
            }

            return wrapper;
        }
    }

    @Override
    public void init(EndpointConfig config)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("init({})",config);
        }
        
        if(!containerScope.isRunning())
        {
            throw new RuntimeException(containerScope.getClass().getName() + " is not running yet");
        }
        
        // Instantiate all declared decoders
        for (DecoderMetadata metadata : metadatas)
        {
            Wrapper wrapper = newWrapper(metadata);
            activeWrappers.put(metadata.getObjectType(),wrapper);
        }

        // Initialize all decoders
        for (Wrapper wrapper : activeWrappers.values())
        {
            wrapper.decoder.init(config);
        }
    }

    public Wrapper newWrapper(DecoderMetadata metadata)
    {
        Class<? extends Decoder> decoderClass = metadata.getCoderClass();
        try
        {
            Decoder decoder = containerScope.getObjectFactory().createInstance(decoderClass);
            return new Wrapper(decoder,metadata);
        }
        catch (InstantiationException | IllegalAccessException e)
        {
            throw new IllegalStateException("Unable to instantiate Decoder: " + decoderClass.getName());
        }
    }
}
