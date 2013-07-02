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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.websocket.Decoder;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.metadata.DecoderMetadata;
import org.eclipse.jetty.websocket.jsr356.utils.ReflectUtils;

/**
 * Factory for {@link DecoderMetadata}
 * <p>
 * Relies on search order of parent {@link DecoderFactory} instances as such.
 * <ul>
 * <li>Endpoint declared decoders</li>
 * <li>EndpointConfig declared decoders</li>
 * <li>Container declared decoders (primitives)</li>
 * </ul>
 */
public class DecoderFactory
{
    private static final Logger LOG = Log.getLogger(DecoderFactory.class);
    /** Decoders by Type */
    private final Map<Class<?>, DecoderMetadata> typeMap;
    /** Registered Decoders at this level */
    private Map<Class<? extends Decoder>, List<DecoderMetadata>> registered;
    /** Parent Factory */
    private DecoderFactory parentFactory;

    public DecoderFactory()
    {
        this.typeMap = new ConcurrentHashMap<>();
        this.registered = new ConcurrentHashMap<>();
    }

    public DecoderFactory(DecoderFactory parentFactory)
    {
        this();
        this.parentFactory = parentFactory;
    }

    private Class<?> getDecoderMessageClass(Class<? extends Decoder> decoder, Class<?> interfaceClass)
    {
        Class<?> decoderClass = ReflectUtils.findGenericClassFor(decoder,interfaceClass);
        if (decoderClass == null)
        {
            StringBuilder err = new StringBuilder();
            err.append("Invalid type declared for interface ");
            err.append(interfaceClass.getName());
            err.append(" on class ");
            err.append(decoder);
            throw new IllegalArgumentException(err.toString());
        }
        return decoderClass;
    }

    /**
     * Get the list of registered decoder classes.
     * <p>
     * Includes all decoders at this level and above.
     * 
     * @return the list of registered decoder classes.
     */
    public List<Class<? extends Decoder>> getList()
    {
        List<Class<? extends Decoder>> decoders = new ArrayList<>();
        decoders.addAll(registered.keySet());
        if (parentFactory != null)
        {
            decoders.addAll(parentFactory.getList());
        }
        return decoders;
    }

    public List<DecoderMetadata> getMetadata(Class<? extends Decoder> decoder)
    {
        LOG.debug("getMetadata({})",decoder);
        List<DecoderMetadata> ret = registered.get(decoder);

        if (ret != null)
        {
            return ret;
        }

        // Not found, Try parent factory (if declared)
        if (parentFactory != null)
        {
            ret = parentFactory.registered.get(decoder);
            if (ret != null)
            {
                return ret;
            }
        }

        return register(decoder);
    }

    public DecoderMetadata getMetadataFor(Class<?> type)
    {
        DecoderMetadata metadata = typeMap.get(type);
        if (metadata == null)
        {
            if (parentFactory != null)
            {
                return parentFactory.getMetadataFor(type);
            }
        }
        return metadata;
    }

    public DecoderWrapper getWrapperFor(Class<?> type)
    {
        DecoderMetadata metadata = getMetadataFor(type);
        if (metadata != null)
        {
            return newWrapper(metadata);
        }
        return null;
    }

    public DecoderWrapper newWrapper(DecoderMetadata metadata)
    {
        Class<? extends Decoder> decoderClass = metadata.getDecoderClass();
        try
        {
            Decoder decoder = decoderClass.newInstance();
            return new DecoderWrapper(decoder,metadata);
        }
        catch (InstantiationException | IllegalAccessException e)
        {
            throw new IllegalStateException("Unable to instantiate Decoder: " + decoderClass.getName());
        }
    }

    public List<DecoderMetadata> register(Class<? extends Decoder> decoder)
    {
        List<DecoderMetadata> metadatas = new ArrayList<>();

        if (Decoder.Binary.class.isAssignableFrom(decoder))
        {
            Class<?> objType = getDecoderMessageClass(decoder,Decoder.Binary.class);
            metadatas.add(new DecoderMetadata(objType,decoder,MessageType.BINARY,false));
        }
        if (Decoder.BinaryStream.class.isAssignableFrom(decoder))
        {
            Class<?> objType = getDecoderMessageClass(decoder,Decoder.BinaryStream.class);
            metadatas.add(new DecoderMetadata(objType,decoder,MessageType.BINARY,true));
        }
        if (Decoder.Text.class.isAssignableFrom(decoder))
        {
            Class<?> objType = getDecoderMessageClass(decoder,Decoder.Text.class);
            metadatas.add(new DecoderMetadata(objType,decoder,MessageType.TEXT,false));
        }
        if (Decoder.TextStream.class.isAssignableFrom(decoder))
        {
            Class<?> objType = getDecoderMessageClass(decoder,Decoder.TextStream.class);
            metadatas.add(new DecoderMetadata(objType,decoder,MessageType.TEXT,true));
        }

        if (!ReflectUtils.isDefaultConstructable(decoder))
        {
            throw new InvalidSignatureException("Decoder must have public, no-args constructor: " + decoder.getName());
        }

        if (metadatas.size() <= 0)
        {
            throw new InvalidSignatureException("Not a valid Decoder class: " + decoder.getName());
        }

        return trackMetadata(decoder,metadatas);
    }

    public void register(Class<?> typeClass, Class<? extends Decoder> decoderClass, MessageType msgType, boolean streamed)
    {
        List<DecoderMetadata> metadatas = new ArrayList<>();
        metadatas.add(new DecoderMetadata(typeClass,decoderClass,msgType,streamed));
        trackMetadata(decoderClass,metadatas);
    }

    public List<DecoderMetadata> registerAll(Class<? extends Decoder>[] decoders)
    {
        List<DecoderMetadata> metadatas = new ArrayList<>();

        for (Class<? extends Decoder> decoder : decoders)
        {
            metadatas.addAll(register(decoder));
        }

        return metadatas;
    }

    private List<DecoderMetadata> trackMetadata(Class<? extends Decoder> decoder, List<DecoderMetadata> metadatas)
    {
        for (DecoderMetadata metadata : metadatas)
        {
            trackType(metadata);
        }

        LOG.debug("Registered {} with [{} entries]",decoder.getName(),metadatas.size());
        registered.put(decoder,metadatas);
        return metadatas;
    }

    private void trackType(DecoderMetadata metadata)
    {
        Class<?> type = metadata.getObjectType();
        if (typeMap.containsKey(type))
        {
            StringBuilder err = new StringBuilder();
            err.append("Duplicate decoder for type: ");
            err.append(type);
            err.append(" (class ").append(metadata.getDecoderClass().getName());
            DecoderMetadata dup = typeMap.get(type);
            err.append(" duplicates ");
            err.append(dup.getDecoderClass().getName());
            err.append(")");
            throw new IllegalStateException(err.toString());
        }

        typeMap.put(type,metadata);
    }
}
