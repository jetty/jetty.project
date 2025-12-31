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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongFunction;

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
    private final Map<Id<?>, Object> parameters;

    public TransportParameters()
    {
        this.parameters = new LinkedHashMap<>();
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
        long id = Ids.newGrease();
        byte[] bytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(bytes);
        put(Ids.create(id, BytesId::new), bytes);
    }

    public int size()
    {
        return parameters.size();
    }

    @Override
    public boolean equals(Object object)
    {
        if (object instanceof TransportParameters that)
            return Objects.equals(parameters, that.parameters);
        return false;
    }

    @Override
    public int hashCode()
    {
        return Objects.hashCode(parameters);
    }

    @Override
    public String toString()
    {
        return "%s@%x[%s]".formatted(TypeUtil.toShortName(getClass()), hashCode(), parameters);
    }

    /**
     * <p>The collection of known QUIC transport parameter IDs.</p>
     */
    public static class Ids
    {
        // The max N that can produce a grease parameter id that fits a VarLenInt.
        private static final long MAX_N = 148764065110560899L;
        private static final Map<Long, Id<?>> ids = new HashMap<>();

        /**
         * <p>The session max idle timeout in milliseconds.</p>
         */
        public static final Id<Long> MAX_IDLE_TIMEOUT = Ids.create(0x01, LongId::new);
        /**
         * <p>The initial session max data.</p>
         * <p>A local peer sends this parameter to inform the remote peer about
         * the max data the local peer is willing to receive on the session.</p>
         */
        public static final Id<Long> INITIAL_MAX_DATA = Ids.create(0x04, LongId::new);
        /**
         * <p>The initial local bidirectional stream max data.</p>
         * <p>A local peer sends this parameter to inform the remote peer about
         * the max data the local peer is willing to receive on local bidirectional streams.</p>
         */
        public static final Id<Long> INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_LOCAL = Ids.create(0x05, LongId::new);
        /**
         * <p>The initial remote bidirectional stream max data.</p>
         * <p>A local peer sends this parameter to inform the remote peer about
         * the max data the local peer is willing to receive on remote bidirectional streams.</p>
         */
        public static final Id<Long> INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE = Ids.create(0x06, LongId::new);
        /**
         * <p>The initial unidirectional stream max data.</p>
         * <p>A local peer sends this parameter to inform the remote peer about
         * the max data the local peer is willing to receive on unidirectional streams.</p>
         */
        public static final Id<Long> INITIAL_MAX_STREAM_DATA_UNIDIRECTIONAL = Ids.create(0x07, LongId::new);
        /**
         * <p>The initial bidirectional max streams.</p>
         * <p>A local peer sends this parameter to inform the remote peer about
         * the max number of streams the local peer is willing to receive.</p>
         */
        public static final Id<Long> INITIAL_MAX_STREAMS_BIDIRECTIONAL = Ids.create(0x08, LongId::new);
        /**
         * <p>The initial unidirectional max streams.</p>
         * <p>A local peer sends this parameter to inform the remote peer about
         * the max number of streams the local peer is willing to receive.</p>
         */
        public static final Id<Long> INITIAL_MAX_STREAMS_UNIDIRECTIONAL = Ids.create(0x09, LongId::new);

        /**
         * <p>Creates a new {@link TransportParameters.Id} with the given id and type.</p>
         *
         * @param id the transport parameter ID
         * @param creator the function that creates the specific {@link TransportParameters.Id}
         * @return a new {@link TransportParameters.Id}
         * @param <R> the type of the {@link TransportParameters.Id}
         * @see #get(long)
         */
        @SuppressWarnings("unchecked")
        public static <R> Id<R> create(long id, LongFunction<Id<R>> creator)
        {
            return (Id<R>)ids.computeIfAbsent(id, creator::apply);
        }

        /**
         * <p>Returns a {@link TransportParameters.Id} with the given id.</p>
         *
         * @param id the transport parameter ID
         * @return the {@link TransportParameters.Id} with the given id, or {@code null}
         * @param <R> the type of the {@link TransportParameters.Id}
         */
        @SuppressWarnings("unchecked")
        public static <R> Id<R> get(long id)
        {
            return (Id<R>)ids.get(id);
        }

        /**
         * @return a new grease transport parameter ID
         * @see #isGrease(long)
         */
        public static long newGrease()
        {
            return 31 * ThreadLocalRandom.current().nextLong(MAX_N + 1) + 27;
        }

        /**
         * <p>Returns whether a transport parameter ID is a <em>grease</em> one, as defined by
         * <a href="https://datatracker.ietf.org/doc/html/rfc9000#name-reserved-transport-paramete">
         *     RFC 9000, section 18.1
     *     </a>.</p>
         *
         * @param id the transport parameter ID to test
         * @return whether the given transport parameter ID is a grease one
         * @see #newGrease()
         */
        public static boolean isGrease(long id)
        {
            return (id - 27) % 31 == 0;
        }
    }

    /**
     * <p>The parameter ID, with the type of the value.</p>
     * <p>For most parameter IDs, the value type is {@code long},
     * but for other parameters, such as tokens or connection IDs,
     * the value type is a {@code byte[]}.</p>
     *
     * @param <T> the type of the value
     */
    public abstract static sealed class Id<T> permits LongId, BytesId
    {
        private final long id;

        protected Id(long id)
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
    }

    public static final class LongId extends Id<Long>
    {
        public LongId(long id)
        {
            super(id);
        }
    }

    public static final class BytesId extends Id<byte[]>
    {
        public BytesId(long id)
        {
            super(id);
        }
    }
}
