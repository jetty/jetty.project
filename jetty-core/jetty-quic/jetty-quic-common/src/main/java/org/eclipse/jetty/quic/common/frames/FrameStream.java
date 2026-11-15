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
import org.eclipse.jetty.quic.api.frames.CryptoFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.ResetStreamFrame;
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
public abstract sealed class FrameStream
{
    private static final Logger LOG = LoggerFactory.getLogger(FrameStream.class);

    private final Queue<Frame.WithOffset> frames = new PriorityQueue<>();
    private final int queueMaxSize;
    private final Consumer<Frame.WithOffset> listener;
    private long offset;
    private long finalOffset = -1;
    private long lastEndOffset;

    public FrameStream(int queueMaxSize, Consumer<Frame.WithOffset> listener)
    {
        this.queueMaxSize = queueMaxSize;
        this.listener = listener;
    }

    /// Offers a [Frame.WithOffset] to be delivered in sequence.
    public void offer(Frame.WithOffset frame)
    {
        long finalSize = switch (frame)
        {
            case ResetStreamFrame resetFrame -> resetFrame.finalSize();
            case StreamFrame streamFrame when streamFrame.isEndStream() -> streamFrame.endOffset();
            case CryptoFrame _, StreamFrame _ -> -1;
        };

        // RFC-9000 #4.5: final size cannot change.
        if (finalOffset < 0)
        {
            if (finalSize >= 0)
            {
                if (offset > finalSize || lastEndOffset > finalSize)
                    throw new QuicException(ErrorCode.FINAL_SIZE_ERROR, "invalid_final_size", frame.type());
            }
            finalOffset = finalSize;
        }
        else
        {
            if (finalSize < 0)
            {
                if (frame.endOffset() > finalOffset)
                    throw new QuicException(ErrorCode.FINAL_SIZE_ERROR, "invalid_final_size", frame.type());
            }
            else
            {
                if (finalOffset != finalSize)
                    throw new QuicException(ErrorCode.FINAL_SIZE_ERROR, "invalid_final_size", frame.type());
            }

            if (offset == finalOffset)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("discarding, all data delivered {} on {}", frame, this);
                return;
            }
        }

        if (finalSize < 0)
        {
            // Scan the queue and discard duplicates.
            for (Frame.WithOffset element : frames)
            {
                if (element.offset() <= frame.offset() && element.endOffset() >= frame.endOffset())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("discarding, data duplicate {} on {}", frame, this);
                    return;
                }
            }
        }

        if (queueMaxSize > 0 && frames.size() >= queueMaxSize && frame.offset() > offset)
        {
            ErrorCode error = frame instanceof CryptoFrame ? ErrorCode.CRYPTO_BUFFER_EXCEEDED_ERROR : ErrorCode.PROTOCOL_VIOLATION_ERROR;
            throw new QuicException(error, "frame_stream_size_exceeded", frame.type());
        }

        // Retain the frame because it is stored for later use.
        // When the frame is removed from the queue,
        // it will be released by closing it.
        retain(frame);
        frames.offer(frame);
        lastEndOffset = Math.max(lastEndOffset, frame.endOffset());

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

            if (candidate.offset() > offset)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("stalling, data gap on {}", this);
                return;
            }
            else if (candidate.offset() == offset)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("notifying, data {} on {}", candidate, this);
                frames.poll();
                offset += candidate.length();
                notifyFrame(candidate);
                candidate.close();
            }
            else
            {
                if (candidate.endOffset() <= offset)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("discarding, data delivered {} on {}", candidate, this);
                    frames.poll();
                    candidate.close();
                    continue;
                }

                switch (candidate)
                {
                    case Frame.WithData dataFrame ->
                    {
                        dataFrame.skip(offset - dataFrame.offset());
                        long length = candidate.endOffset() - offset;
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
                    case ResetStreamFrame _ ->
                        throw new QuicException(ErrorCode.INTERNAL_ERROR, "invalid_reset_frame", candidate.type());
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

        public Crypto(int queueMaxSize, Consumer<Frame.WithOffset> listener, EncryptionLevel encryptionLevel)
        {
            super(queueMaxSize, listener);
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

        public Stream(int queueMaxSize, Consumer<Frame.WithOffset> listener, long streamId)
        {
            super(queueMaxSize, listener);
            this.streamId = streamId;
        }

        @Override
        public String toString()
        {
            return "%s@%x[#%d,offset=%d,queue=%s]".formatted(TypeUtil.toShortName(getClass()), hashCode(), streamId, offset(), queueSize());
        }
    }
}
