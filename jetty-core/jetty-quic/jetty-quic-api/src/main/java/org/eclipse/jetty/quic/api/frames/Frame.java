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

import org.eclipse.jetty.util.TypeUtil;

/**
 * <p>A generic QUIC frame carrying a frame type.</p>
 *
 * @see WithStreamId
 */
public class Frame
{
    public static final int DEFAULT_MAX_SIZE = 16384;

    private final long frameType;

    public Frame(long frameType)
    {
        this.frameType = frameType;
    }

    public long getFrameType()
    {
        return frameType;
    }

    @Override
    public String toString()
    {
        return "%s@%x".formatted(TypeUtil.toShortName(getClass()), hashCode());
    }

    /**
     * <p>A QUIC frame that carries a stream id.</p>
     */
    public static class WithStreamId extends Frame
    {
        private final long streamId;

        public WithStreamId(long frameType, long streamId)
        {
            super(frameType);
            this.streamId = streamId;
        }

        public long getStreamId()
        {
            return streamId;
        }

        @Override
        public String toString()
        {
            return "%s#%d".formatted(super.toString(), getStreamId());
        }
    }

    public interface WithOffset
    {
        long getOffset();

        long getLength();
    }

    public interface Listener
    {
        void onFrame(Frame frame);
    }
}
