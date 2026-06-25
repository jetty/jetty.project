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

package org.eclipse.jetty.quic.common.frames;

import java.util.PriorityQueue;
import java.util.Queue;
import java.util.function.Consumer;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.ResetFrame;
import org.eclipse.jetty.quic.api.frames.StreamFrame;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.quic.util.QuicException;
import org.eclipse.jetty.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// A stream of frames that carry data bytes that
/// must be delivered to applications in order.
///
/// CRYPTO and STREAM frames may arrive out-of-order,
/// within QUIC packets, and this class is responsible
/// for reordering them before delivering them to the
/// application via the provided listener.
public sealed abstract class FrameStream
{
    private static final Logger LOG = LoggerFactory.getLogger(FrameStream.class);

    private final Queue<Frame.WithOffset> frames = new PriorityQueue<>();
    private final Consumer<Frame.WithOffset> listener;
    private long offset;
    private long finalOffset = -1;

    public FrameStream(Consumer<Frame.WithOffset> listener)
    {
        this.listener = listener;
    }

    public void offer(Frame.WithOffset frame)
    {
        long finalSize = switch (frame)
        {
            case ResetFrame resetFrame -> resetFrame.finalSize();
            case StreamFrame streamFrame when streamFrame.isEndStream() -> streamFrame.offset() + streamFrame.length();
            default -> finalOffset;
        };

        // RFC-9000[4.5]: cannot change the final size.
        if (finalOffset >= 0)
        {
            if (finalOffset != finalSize)
                throw new QuicException(ErrorCode.FINAL_SIZE_ERROR, "invalid_final_size", frame.type());
            if (offset == finalOffset)
                return;
        }

        finalOffset = finalSize;

        // Retain the frame because it is stored for later use.
        // When the frame is removed from the queue, it will be released.
        retain(frame);
        frames.offer(frame);

        if (LOG.isDebugEnabled())
            LOG.debug("offered {} on {}", frame, this);

        while (true)
        {
            Frame.WithOffset candidate = frames.peek();
            if (candidate == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("stalling, no data on {}", this);
                return;
            }

            long candidateOffset = candidate.offset();
            if (candidateOffset > offset)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("stalling, data gap on {}", this);
                return;
            }

            if (candidateOffset == offset)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("notifying, data {} on {}", candidate, this);
                frames.poll();
                offset += candidate.length();
                notifyFrame(candidate);
                candidate.close();
                continue;
            }

            long offsetEnd = candidateOffset + candidate.length();
            if (offsetEnd <= offset)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("discarding, data delivered {} on {}", candidate, this);
                frames.poll();
                candidate.close();
                continue;
            }

            switch (candidate)
            {
                case ResetFrame resetFrame ->
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("notifying, reset {} on {}", resetFrame, this);
                    frames.poll();
                    notifyFrame(resetFrame);
                }
                case Frame.WithData dataFrame ->
                {
                    long length = offsetEnd - offset;
                    try (Frame.WithData slice = dataFrame.slice(offset, length))
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("notifying, data slice {} on {}", slice, this);
                        frames.poll();
                        dataFrame.close();
                        offset += length;
                        notifyFrame(slice);
                    }
                }
            }
        }
    }

    private void retain(Frame.WithOffset frame)
    {
        if (frame instanceof Frame.WithData dataFrame)
            dataFrame.accept(RetainableByteBuffer::retain);
    }

    long offset()
    {
        return offset;
    }

    int queueSize()
    {
        return frames.size();
    }

    private void notifyFrame(Frame.WithOffset frame)
    {
        try
        {
            listener.accept(frame);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure while notifying listener {}", listener, x);
        }
    }

    @Override
    public String toString()
    {
        return "%s@%x[offset=%d,queue=%s]".formatted(TypeUtil.toShortName(getClass()), hashCode(), offset, frames.size());
    }

    public static final class Crypto extends FrameStream
    {
        private final EncryptionLevel encryptionLevel;

        public Crypto(EncryptionLevel encryptionLevel, Consumer<Frame.WithOffset> listener)
        {
            super(listener);
            this.encryptionLevel = encryptionLevel;
        }

        @Override
        public String toString()
        {
            return "%s@%x[%s,offset=%d,queue=%s]".formatted(TypeUtil.toShortName(getClass()), hashCode(), encryptionLevel, offset(), queueSize());
        }
    }

    public static final class Stream extends FrameStream
    {
        private final long streamId;

        public Stream(long streamId, Consumer<Frame.WithOffset> listener)
        {
            super(listener);
            this.streamId = streamId;
        }

        @Override
        public String toString()
        {
            return "%s@%x[#%d,offset=%d,queue=%s]".formatted(TypeUtil.toShortName(getClass()), hashCode(), streamId, offset(), queueSize());
        }
    }
}
