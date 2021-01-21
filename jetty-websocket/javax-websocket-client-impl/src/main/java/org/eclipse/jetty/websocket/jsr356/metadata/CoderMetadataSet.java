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

package org.eclipse.jetty.websocket.jsr356.metadata;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;

/**
 * An durable collection of {@link CoderMetadata}.
 * <p>
 * This is a write-only collection, and cannot be modified once initialized.
 *
 * @param <T> The type of coder ({@link javax.websocket.Decoder} or {@link javax.websocket.Encoder}
 * @param <M> The metadata for the coder
 */
public abstract class CoderMetadataSet<T, M extends CoderMetadata<T>> implements Iterable<M>
{
    /**
     * Collection of metadatas
     */
    private final List<M> metadatas;
    /**
     * Collection of declared Coder classes
     */
    private final List<Class<? extends T>> coders;
    /**
     * Mapping of supported Type to metadata list index
     */
    private final Map<Class<?>, Integer> typeMap;
    /**
     * Mapping of Coder class to list of supported metadata
     */
    private final Map<Class<? extends T>, List<Integer>> implMap;

    protected CoderMetadataSet()
    {
        metadatas = new ArrayList<>();
        coders = new ArrayList<>();
        typeMap = new ConcurrentHashMap<>();
        implMap = new ConcurrentHashMap<>();
    }

    public void add(Class<? extends T> coder)
    {
        List<M> metadatas = discover(coder);
        trackMetadata(metadatas);
    }

    public List<M> addAll(Class<? extends T>[] coders)
    {
        List<M> metadatas = new ArrayList<>();

        for (Class<? extends T> coder : coders)
        {
            metadatas.addAll(discover(coder));
        }

        trackMetadata(metadatas);
        return metadatas;
    }

    public List<M> addAll(List<Class<? extends T>> coders)
    {
        List<M> metadatas = new ArrayList<>();

        for (Class<? extends T> coder : coders)
        {
            metadatas.addAll(discover(coder));
        }

        trackMetadata(metadatas);
        return metadatas;
    }

    /**
     * Coder Specific discovery of Metadata for a specific coder.
     *
     * @param coder the coder to discover metadata in.
     * @return the list of metadata discovered
     * @throws InvalidWebSocketException if unable to discover some metadata. Sucha as: a duplicate {@link CoderMetadata#getObjectType()} encountered, , or if unable to find the
     * concrete generic class reference for the coder, or if the provided coder is not valid per spec.
     */
    protected abstract List<M> discover(Class<? extends T> coder);

    public Class<? extends T> getCoder(Class<?> type)
    {
        M metadata = getMetadataByType(type);
        if (metadata == null)
        {
            return null;
        }
        return metadata.getCoderClass();
    }

    public List<Class<? extends T>> getList()
    {
        return coders;
    }

    public List<M> getMetadataByImplementation(Class<? extends T> clazz)
    {
        List<Integer> indexes = implMap.get(clazz);
        if (indexes == null)
        {
            return null;
        }
        List<M> ret = new ArrayList<>();
        for (Integer idx : indexes)
        {
            ret.add(metadatas.get(idx));
        }
        return ret;
    }

    public M getMetadataByType(Class<?> type)
    {
        Integer idx = typeMap.get(type);
        if (idx == null)
        {
            // Quick lookup failed, try slower lookup via isAssignable instead
            idx = getMetadataByAssignableType(type);
            if (idx != null)
            {
                // add new entry map
                typeMap.put(type, idx);
            }
        }

        // If idx is STILL null, we've got no match
        if (idx == null)
        {
            return null;
        }
        return metadatas.get(idx);
    }

    private Integer getMetadataByAssignableType(Class<?> type)
    {
        for (Map.Entry<Class<?>, Integer> entry : typeMap.entrySet())
        {
            if (entry.getKey().isAssignableFrom(type))
            {
                return entry.getValue();
            }
        }

        return null;
    }

    @Override
    public Iterator<M> iterator()
    {
        return metadatas.iterator();
    }

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append(this.getClass().getSimpleName());
        builder.append("[metadatas=");
        builder.append(metadatas.size());
        builder.append(",coders=");
        builder.append(coders.size());
        builder.append("]");
        return builder.toString();
    }

    protected void trackMetadata(List<M> metadatas)
    {
        for (M metadata : metadatas)
        {
            trackMetadata(metadata);
        }
    }

    protected void trackMetadata(M metadata)
    {
        synchronized (metadatas)
        {
            // Validate
            boolean duplicate = false;

            // Is this metadata already declared?
            if (metadatas.contains(metadata))
            {
                duplicate = true;
            }

            // Is this type already declared?
            Class<?> type = metadata.getObjectType();
            if (typeMap.containsKey(type))
            {
                duplicate = true;
            }

            if (duplicate)
            {
                StringBuilder err = new StringBuilder();
                err.append("Duplicate decoder for type: ");
                err.append(type);
                err.append(" (class ").append(metadata.getCoderClass().getName());

                // Get prior one
                M dup = getMetadataByType(type);
                err.append(" duplicates ");
                err.append(dup.getCoderClass().getName());
                err.append(")");
                throw new IllegalStateException(err.toString());
            }

            // Track
            Class<? extends T> coderClass = metadata.getCoderClass();
            int newidx = metadatas.size();
            metadatas.add(metadata);
            coders.add(coderClass);
            typeMap.put(type, newidx);

            List<Integer> indexes = implMap.get(coderClass);
            if (indexes == null)
            {
                indexes = new ArrayList<>();
            }
            if (indexes.contains(newidx))
            {
                // possible duplicate, TODO: how?
            }
            indexes.add(newidx);
            implMap.put(coderClass, indexes);
        }
    }
}
