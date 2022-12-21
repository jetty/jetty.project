//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.util.Atomics;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A flow control strategy that accumulates updates and emits window control
 * frames when the accumulated value reaches a threshold.</p>
 * <p>The sender flow control window is represented in the receiver as two
 * buckets: a bigger bucket, initially full, that is drained when data is
 * received, and a smaller bucket, initially empty, that is filled when data is
 * consumed. Only the smaller bucket can refill the bigger bucket.</p>
 * <p>The smaller bucket is defined as a fraction of the bigger bucket.</p>
 * <p>For a more visual representation, see the
 * <a href="http://en.wikipedia.org/wiki/Shishi-odoshi">rocking bamboo fountain</a>,
 * where the bamboo is the smaller bucket and the pool is the bigger bucket.</p>
 * <p>The algorithm works in this way.</p>
 * <p>The initial bigger bucket (BB) capacity is 100, and let's imagine the smaller
 * bucket (SB) being 40% of the bigger bucket: 40.</p>
 * <p>The receiver receives a data frame of 60, so now BB=40; the data frame is
 * passed to the application that consumes 25, so now SB=25. Since SB is not full,
 * no window control frames are emitted.</p>
 * <p>The application consumes other 20, so now SB=45. Since SB is full, its 45
 * are transferred to BB, which is now BB=85, and a window control frame is sent
 * with delta=45.</p>
 * <p>The application consumes the remaining 15, so now SB=15, and no window
 * control frame is emitted.</p>
 * <p>The {@code bufferRatio} controls how often the window control frame is
 * emitted.</p>
 * <p>A {@code bufferRatio=0.0} means that a window control frame is emitted
 * every time the application consumes a data frame. This may result in too many
 * window control frames be emitted, but may allow the sender to avoid stalling.</p>
 * <p>A {@code bufferRatio=1.0} means that a window control frame is emitted
 * only when the application has consumed a whole window. This minimizes the
 * number of window control frames emitted, but may cause the sender to stall,
 * waiting for the window control frame.</p>
 * <p>The default value is {@code bufferRatio=0.5}.</p>
 */
@ManagedObject
public class BufferingFlowControlStrategy extends AbstractFlowControlStrategy
{
    private static final Logger LOG = LoggerFactory.getLogger(BufferingFlowControlStrategy.class);

    private final AtomicInteger maxSessionRecvWindow = new AtomicInteger(DEFAULT_WINDOW_SIZE);
    private final AtomicInteger sessionLevel = new AtomicInteger();
    private final Map<Integer, AtomicInteger> streamLevels = Collections.synchronizedMap(new HashMap<>());
    private float bufferRatio;

    public BufferingFlowControlStrategy(float bufferRatio)
    {
        this(DEFAULT_WINDOW_SIZE, bufferRatio);
    }

    public BufferingFlowControlStrategy(int initialStreamSendWindow, float bufferRatio)
    {
        super(initialStreamSendWindow);
        this.bufferRatio = bufferRatio;
    }

    @ManagedAttribute("The ratio between the receive buffer and the consume buffer")
    public float getBufferRatio()
    {
        return bufferRatio;
    }

    public void setBufferRatio(float bufferRatio)
    {
        this.bufferRatio = bufferRatio;
    }

    @Override
    public void onStreamCreated(Stream stream)
    {
        super.onStreamCreated(stream);
        streamLevels.put(stream.getId(), new AtomicInteger());
    }

    @Override
    public void onStreamDestroyed(Stream stream)
    {
        streamLevels.remove(stream.getId());
        super.onStreamDestroyed(stream);
    }

    @Override
    public void onDataConsumed(Session session, Stream stream, int length)
    {
        if (length <= 0)
            return;

        float ratio = bufferRatio;

        int level = sessionLevel.addAndGet(length);
        int maxLevel = (int)(maxSessionRecvWindow.get() * ratio);
        if (level > maxLevel)
        {
            if (sessionLevel.compareAndSet(level, 0))
            {
                updateRecvWindow(session, level);
                if (LOG.isDebugEnabled())
                    LOG.debug("Data consumed, {} bytes, updated session recv window by {}/{} for {}", length, level, maxLevel, session);
                sendWindowUpdate(session, null, List.of(new WindowUpdateFrame(0, level)));
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Data consumed, {} bytes, concurrent session recv window level {}/{} for {}", length, sessionLevel, maxLevel, session);
            }
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Data consumed, {} bytes, session recv window level {}/{} for {}", length, level, maxLevel, session);
        }

        if (stream != null)
        {
            if (stream.isRemotelyClosed())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Data consumed, {} bytes, ignoring update stream recv window for remotely closed {}", length, stream);
            }
            else
            {
                AtomicInteger streamLevel = streamLevels.get(stream.getId());
                if (streamLevel != null)
                {
                    level = streamLevel.addAndGet(length);
                    maxLevel = (int)(getInitialStreamRecvWindow() * ratio);
                    if (level > maxLevel)
                    {
                        level = streamLevel.getAndSet(0);
                        updateRecvWindow(stream, level);
                        if (LOG.isDebugEnabled())
                            LOG.debug("Data consumed, {} bytes, updated stream recv window by {}/{} for {}", length, level, maxLevel, stream);
                        sendWindowUpdate(session, stream, List.of(new WindowUpdateFrame(stream.getId(), level)));
                    }
                    else
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Data consumed, {} bytes, stream recv window level {}/{} for {}", length, level, maxLevel, stream);
                    }
                }
            }
        }
    }

    @Override
    public void windowUpdate(Session session, Stream stream, WindowUpdateFrame frame)
    {
        super.windowUpdate(session, stream, frame);

        // Window updates cannot be negative.
        // The SettingsFrame.INITIAL_WINDOW_SIZE setting
        // only influences the *stream* window size.
        // Therefore the session window can only be enlarged,
        // and here we keep track of its max value.

        // Updating the max session recv window is done here
        // so that if a peer decides to send a unilateral
        // window update to enlarge the session window,
        // without the corresponding data consumption, here
        // we can track it.
        // Note that it is not perfect, since there is a time
        // window between the session recv window being updated
        // before the window update frame is sent, and the
        // invocation of this method: in between data may arrive
        // and reduce the session recv window size.
        // But eventually the max value will be seen.

        // Note that we cannot avoid the time window described
        // above by updating the session recv window from here
        // because there is a race between the sender and the
        // receiver: the sender may receive a window update and
        // send more data, while this method has not yet been
        // invoked; when the data is received the session recv
        // window may become negative and the connection will
        // be closed (per specification).

        if (frame.getStreamId() == 0)
        {
            int sessionWindow = updateRecvWindow(session, 0);
            Atomics.updateMax(maxSessionRecvWindow, sessionWindow);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[ratio=%.2f,sessionLevel=%s,sessionStallTime=%dms,streamsStallTime=%dms]",
            getClass().getSimpleName(),
            hashCode(),
            bufferRatio,
            sessionLevel,
            getSessionStallTime(),
            getStreamsStallTime());
    }
}
