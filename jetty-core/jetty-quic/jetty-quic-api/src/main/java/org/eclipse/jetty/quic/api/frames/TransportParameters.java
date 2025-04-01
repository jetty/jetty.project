//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.api.frames;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jetty.util.TypeUtil;

/**
 * <p>The QUIC transport parameters as a map {@code ID -> value}.</p>
 * <p>The type of the value depends on the parameter ID; for most
 * parameters, the value type is a {@code long}, but for other
 * parameters, such as tokens or connection IDs, the value type
 * is a {@code byte[]}.</p>
 */
public class TransportParameters implements Iterable<Map.Entry<TransportParameters.Id<?>, Object>>
{
    // The max N that can produce a grease parameter id that fits a VarLenInt.
    private static final long MAX_N = 148764065110560899L;

    /**
     * <p>The session max idle timeout in milliseconds.</p>
     */
    public static final Id<Long> MAX_IDLE_TIMEOUT = Id.from(0x01);
    /**
     * <p>The initial session max data.</p>
     * <p>A local peer sends this parameter to inform the remote peer about
     * the max data the local peer is willing to receive on the session.</p>
     */
    public static final Id<Long> INITIAL_MAX_DATA = Id.from(0x04);
    /**
     * <p>The initial local bidirectional stream max data.</p>
     * <p>A local peer sends this parameter to inform the remote peer about
     * the max data the local peer is willing to receive on local bidirectional streams.</p>
     */
    public static final Id<Long> INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_LOCAL = Id.from(0x05);
    /**
     * <p>The initial remote bidirectional stream max data.</p>
     * <p>A local peer sends this parameter to inform the remote peer about
     * the max data the local peer is willing to receive on remote bidirectional streams.</p>
     */
    public static final Id<Long> INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE = Id.from(0x06);
    /**
     * <p>The initial unidirectional stream max data.</p>
     * <p>A local peer sends this parameter to inform the remote peer about
     * the max data the local peer is willing to receive on unidirectional streams.</p>
     */
    public static final Id<Long> INITIAL_MAX_STREAM_DATA_UNIDIRECTIONAL = Id.from(0x07);
    /**
     * <p>The initial bidirectional max streams.</p>
     * <p>A local peer sends this parameter to inform the remote peer about
     * the max number of streams the local peer is willing to receive.</p>
     */
    public static final Id<Long> INITIAL_MAX_STREAMS_BIDIRECTIONAL = Id.from(0x08);
    /**
     * <p>The initial unidirectional max streams.</p>
     * <p>A local peer sends this parameter to inform the remote peer about
     * the max number of streams the local peer is willing to receive.</p>
     */
    public static final Id<Long> INITIAL_MAX_STREAMS_UNIDIRECTIONAL = Id.from(0x09);

    private final Map<Id<?>, Object> parameters;

    public TransportParameters()
    {
        this.parameters = new HashMap<>();
    }

    @Override
    public Iterator<Map.Entry<Id<?>, Object>> iterator()
    {
        return parameters.entrySet().iterator();
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Id<T> id)
    {
        Object value = parameters.get(id);
        return (T)value;
    }

    @SuppressWarnings("unchecked")
    public <T> T put(Id<T> id, T value)
    {
        return (T)parameters.put(id, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T putIfAbsent(Id<T> id, T value)
    {
        return (T)parameters.putIfAbsent(id, value);
    }

    public void putGreaseParameter()
    {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long id = 31 * random.nextLong(MAX_N + 1) + 27;
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        put(Id.from(id), bytes);
    }

    public int size()
    {
        return parameters.size();
    }

    @Override
    public String toString()
    {
        return "%s@%x[%s]".formatted(getClass().getSimpleName(), hashCode(), parameters);
    }

    /**
     * <p>The parameter ID with the type of the value.</p>
     * <p>For most parameter IDs, the value type is {@code long},
     * but for other parameters, such as tokens or connection IDs,
     * the value type is a {@code byte[]}.</p>
     *
     * @param <T> the type of the value
     */
    public static class Id<T>
    {
        @SuppressWarnings("unchecked")
        public static <R> Id<R> from(long id)
        {
            return (Id<R>)Ids.ids.computeIfAbsent(id, Id::new);
        }

        private final long id;

        private Id(long id)
        {
            this.id = id;
        }

        public long getId()
        {
            return id;
        }

        @Override
        public int hashCode()
        {
            return Long.hashCode(id);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj instanceof Id<?> that)
                return id == that.id;
            return false;
        }

        @Override
        public String toString()
        {
            return "%s[%d]".formatted(TypeUtil.toShortName(getClass()), id);
        }

        private static class Ids
        {
            private static final Map<Long, Id<?>> ids = new HashMap<>();
        }
    }
}
