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

import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.websocket.Decoder;
import javax.websocket.DeploymentException;
import javax.websocket.EndpointConfig;

import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.DecoderMetadataFactory.DefaultsDecoderFactory;
import org.eclipse.jetty.websocket.jsr356.utils.MethodUtils;

/**
 * The collection of decoder instances declared for an Endpoint.
 * <p>
 * Decoder classes can arrive from:
 * <ul>
 * <li>{@link EndpointConfig#getDecoders()}</li>
 * <li>&#064ClientEndpoint.decoders()</li>
 * <li>&#064ServerEndpoint.decoders()</li>
 * </ul>
 * <p>
 * This class is also responsible for tracking the lifecycle of all the decoders.
 */
public class Decoders
{
    private final DecoderMetadataFactory metadataFactory;

    /**
     * Map of Object Type to Decoder
     */
    private final Map<Class<?>, DecoderWrapper> decoderMap = new ConcurrentHashMap<>();

    /**
     * Decoder Classes from {@link EndpointConfig#getDecoders()}
     * 
     * @param metadataFactory
     *            the factory to create {@link DecoderMetadata} references
     * @param config
     *            the endpoint config with the decoder configuration
     * 
     * @throws DeploymentException
     *             if unable to instantiate decoders
     */
    public Decoders(DecoderMetadataFactory metadataFactory, EndpointConfig config) throws DeploymentException
    {
        Objects.requireNonNull(metadataFactory,"DecoderMetadataFactory cannot be null");
        this.metadataFactory = metadataFactory;

        for (Class<? extends Decoder> decoder : config.getDecoders())
        {
            addAllMetadata(decoder);
        }
    }

    public void add(DecoderWrapper wrapper) throws IllegalStateException
    {
        // Check for duplicate object types
        Class<?> key = wrapper.getMetadata().getObjectType();
        if (decoderMap.containsKey(key))
        {
            DecoderWrapper other = decoderMap.get(key);
            StringBuilder err = new StringBuilder();
            err.append("Encountered duplicate Decoder handling type <");
            err.append(MethodUtils.toString(key));
            err.append(">, ").append(wrapper.getMetadata().getDecoder().getName());
            err.append(" and ").append(other.getMetadata().getDecoder().getName());
            err.append(" both implement this type");
            throw new IllegalStateException(err.toString());
        }

        decoderMap.put(key,wrapper);
    }

    private DecoderWrapper addAllMetadata(Class<? extends Decoder> decoder) throws IllegalStateException
    {
        DecoderWrapper wrapper = null;

        for (DecoderMetadata metadata : metadataFactory.getMetadata(decoder))
        {
            Decoder decoderImpl;
            try
            {
                decoderImpl = decoder.newInstance();
                wrapper = new DecoderWrapper(decoderImpl,metadata);
                add(wrapper);
            }
            catch (InstantiationException | IllegalAccessException cause)
            {
                throw new IllegalStateException("Unable to instantiate Decoder: " + decoder.getName(),cause);
            }
        }

        return wrapper;
    }

    public Decoder getDecoder(Class<?> type) throws DeploymentException
    {
        return getDecoderWrapper(type).getDecoder();
    }

    public DecoderWrapper getDecoderWrapper(Class<?> type) throws IllegalStateException
    {
        Objects.requireNonNull(type,"Type cannot be null");
        DecoderWrapper wrapper = decoderMap.get(type);
        if (wrapper == null)
        {
            // try DEFAULT implementations
            Class<? extends Decoder> defaultDecoder = DefaultsDecoderFactory.INSTANCE.getDecoder(type);
            wrapper = addAllMetadata(defaultDecoder);
        }

        // simple lookup, return it
        if (wrapper != null)
        {
            return wrapper;
        }

        // Slow mode, test isAssignable on each key
        for (Entry<Class<?>, DecoderWrapper> entry : decoderMap.entrySet())
        {
            Class<?> key = entry.getKey();
            if (key.isAssignableFrom(type))
            {
                // we found a hit, return it
                return entry.getValue();
            }
        }

        throw new InvalidSignatureException("Unable to find appropriate Decoder for type: " + type);
    }

    public Set<Class<?>> keySet()
    {
        return decoderMap.keySet();
    }

    public Collection<DecoderWrapper> wrapperSet()
    {
        return decoderMap.values();
    }
}
