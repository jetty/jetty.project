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

import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.TypeUtil;

/// A generic QUIC frame carrying a frame type.
///
/// @see WithStreamId
public sealed interface Frame extends AutoCloseable
{
    long type();

    @Override
    default void close()
    {
    }

    abstract sealed class Abstract implements Frame permits
        AckFrame,
        ConnectionCloseFrame,
        CryptoFrame,
        DataBlockedFrame,
        WithStreamId.Abstract,
        HandshakeDoneFrame,
        MaxDataFrame,
        MaxStreamsFrame,
        NewConnectionIdFrame,
        NewTokenFrame,
        PaddingFrame,
        PathChallengeFrame,
        PathResponseFrame,
        PingFrame,
        RetireConnectionIdFrame,
        StreamsBlockedFrame
    {
        private final long type;

        protected Abstract(long type)
        {
            this.type = type;
        }

        @Override
        public long type()
        {
            return type;
        }

        @Override
        public String toString()
        {
            return "%s@%x".formatted(TypeUtil.toShortName(getClass()), hashCode());
        }
    }

    /// A QUIC frame carrying a stream id.
    sealed interface WithStreamId extends Frame
    {
        long streamId();

        abstract sealed class Abstract extends Frame.Abstract implements WithStreamId permits
            ResetStreamFrame,
            StopSendingFrame,
            StreamDataBlockedFrame,
            StreamFrame,
            StreamMaxDataFrame
        {
            private final long streamId;

            protected Abstract(long type, long streamId)
            {
                super(type);
                this.streamId = streamId;
            }

            @Override
            public long streamId()
            {
                return streamId;
            }

            @Override
            public String toString()
            {
                return "%s#%d".formatted(super.toString(), streamId());
            }
        }
    }

    /// A QUIC frame carrying an offset and a length.
    sealed interface WithOffset extends Frame, Comparable<WithOffset> permits ResetStreamFrame, WithData
    {
        long offset();

        long length();

        @Override
        default int compareTo(WithOffset that)
        {
            return Long.compare(offset(), that.offset());
        }
    }

    sealed interface WithData extends WithOffset permits CryptoFrame, StreamFrame
    {
        long remaining();

        WithData slice(long offset, long length);

        void accept(Consumer<RetainableByteBuffer> consumer);

        <T> T map(Function<RetainableByteBuffer, T> mapper);
    }
}
