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

public class TransportParameters implements Iterable<Map.Entry<TransportParameters.Id<?>, Object>>
{
    // The max N that can produce a grease parameter id that fits a VarLenInt.
    private static final long MAX_N = 148764065110560899L;

    /**
     * <p>The session max idle timeout in milliseconds.</p>
     */
    public static final Id<Long> MAX_IDLE_TIMEOUT = new LongId(0x01);
    /**
     * <p>The initial session max data.</p>
     * <p>A local peer sends this parameter to inform the remote peer about
     * the max data the local peer is willing to receive on the session.</p>
     */
    public static final Id<Long> INITIAL_MAX_DATA = new LongId(0x04);
    /**
     * <p>The initial local bidirectional stream max data.</p>
     * <p>A local peer sends this parameter to inform the remote peer about
     * the max data the local peer is willing to receive on local bidirectional streams.</p>
     */
    public static final Id<Long> INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_LOCAL = new LongId(0x05);
    /**
     * <p>The initial remote bidirectional stream max data.</p>
     * <p>A local peer sends this parameter to inform the remote peer about
     * the max data the local peer is willing to receive on remote bidirectional streams.</p>
     */
    public static final Id<Long> INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE = new LongId(0x06);
    /**
     * <p>The initial unidirectional stream max data.</p>
     * <p>A local peer sends this parameter to inform the remote peer about
     * the max data the local peer is willing to receive on unidirectional streams.</p>
     */
    public static final Id<Long> INITIAL_MAX_STREAM_DATA_UNIDIRECTIONAL = new LongId(0x07);
    /**
     * <p>The initial bidirectional max streams.</p>
     * <p>A local peer sends this parameter to inform the remote peer about
     * the max number of streams the local peer is willing to receive.</p>
     */
    public static final Id<Long> INITIAL_MAX_STREAMS_BIDIRECTIONAL = new LongId(0x08);
    /**
     * <p>The initial unidirectional max streams.</p>
     * <p>A local peer sends this parameter to inform the remote peer about
     * the max number of streams the local peer is willing to receive.</p>
     */
    public static final Id<Long> INITIAL_MAX_STREAMS_UNIDIRECTIONAL = new LongId(0x09);

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

    public <T> T get(Id<T> id)
    {
        Object value = parameters.get(id);
        return id.cast(value);
    }

    public <T> T put(Id<T> id, T value)
    {
        return id.cast(parameters.put(id, value));
    }

    public <T> T putIfAbsent(Id<T> id, T value)
    {
        return id.cast(parameters.putIfAbsent(id, value));
    }

    public void putGreaseParameter()
    {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long id = 31 * random.nextLong(MAX_N + 1) + 27;
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        put(new BytesId(id), bytes);
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

    public abstract static class Id<T>
    {
        private final long id;

        public Id(long id)
        {
            this.id = id;
        }

        public long getId()
        {
            return id;
        }

        public abstract T cast(Object value);

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
            return "%d".formatted(id);
        }
    }

    public static class BytesId extends Id<byte[]>
    {
        public BytesId(long id)
        {
            super(id);
        }

        @Override
        public byte[] cast(Object value)
        {
            return (byte[])value;
        }
    }

    public static class LongId extends Id<Long>
    {
        public LongId(long id)
        {
            super(id);
        }

        @Override
        public Long cast(Object value)
        {
            return (Long)value;
        }
    }
}
