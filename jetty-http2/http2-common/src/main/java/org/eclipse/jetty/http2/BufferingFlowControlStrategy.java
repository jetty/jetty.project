//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.util.Atomics;
import org.eclipse.jetty.util.Callback;

/**
 * <p>A flow control strategy that accumulates updates and emits window control
 * frames when the accumulated value reaches a threshold.</p>
 * <p>The sender flow control window is represented in the receiver as two
 * buckets: a bigger bucket, initially full, that is drained when data is
 * received, and a smaller bucket, initially empty, that is filled when data is
 * consumed. Only the smaller bucket can refill the bigger bucket.</p>
 * <p>The smaller bucket is defined as a fraction of the bigger bucket.</p>
 * <p>For a more visual representation, see the
 * <a href="http://en.wikipedia.org/wiki/Shishi-odoshi">rocking bamboo fountain</a>.</p>
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
 */
public class BufferingFlowControlStrategy extends AbstractFlowControlStrategy
{
    private final AtomicInteger initialSessionWindow = new AtomicInteger(DEFAULT_WINDOW_SIZE);
    private final AtomicInteger sessionLevel = new AtomicInteger();
    private final Map<IStream, AtomicInteger> streamLevels = new ConcurrentHashMap<>();
    private final float bufferRatio;

    public BufferingFlowControlStrategy(float bufferRatio)
    {
        this(DEFAULT_WINDOW_SIZE, bufferRatio);
    }

    public BufferingFlowControlStrategy(int initialStreamWindow, float bufferRatio)
    {
        super(initialStreamWindow);
        this.bufferRatio = bufferRatio;
    }

    @Override
    public void onNewStream(IStream stream)
    {
        super.onNewStream(stream);
        streamLevels.put(stream, new AtomicInteger());
    }

    @Override
    public void onStreamTerminated(IStream stream)
    {
        streamLevels.remove(stream);
        super.onStreamTerminated(stream);
    }

    @Override
    public void onWindowUpdate(ISession session, IStream stream, WindowUpdateFrame frame)
    {
        // Window updates cannot be negative.
        // The SettingsFrame.INITIAL_WINDOW_SIZE setting only influences
        // the stream initial window size.
        // Therefore the session window can only be enlarged, and here we
        // keep track of its max value.
        super.onWindowUpdate(session, stream, frame);
        if (frame.getStreamId() == 0)
        {
            int sessionWindow = session.updateSendWindow(0);
            Atomics.updateMax(initialSessionWindow, sessionWindow);
        }
    }

    @Override
    public void onDataConsumed(ISession session, IStream stream, int length)
    {
        if (length > 0)
        {
            WindowUpdateFrame windowFrame = null;
            int level = sessionLevel.addAndGet(length);
            int maxLevel = (int)(initialSessionWindow.get() * bufferRatio);
            if (level > maxLevel)
            {
                level = sessionLevel.getAndSet(0);
                session.updateRecvWindow(level);
                if (LOG.isDebugEnabled())
                    LOG.debug("Data consumed, updated session recv window by {} for {}", level, session);
                windowFrame = new WindowUpdateFrame(0, level);
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Data consumed, session recv window level {}/{} for {}", level, maxLevel, session);
            }

            Frame[] windowFrames = Frame.EMPTY_ARRAY;
            if (stream != null)
            {
                if (stream.isClosed())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Data consumed, ignoring update stream recv window by {} for closed {}", length, stream);
                }
                else
                {
                    AtomicInteger streamLevel = streamLevels.get(stream);
                    level = streamLevel.addAndGet(length);
                    maxLevel = (int)(getInitialStreamWindow() * bufferRatio);
                    if (level > maxLevel)
                    {
                        level = streamLevel.getAndSet(0);
                        stream.updateRecvWindow(length);
                        if (LOG.isDebugEnabled())
                            LOG.debug("Data consumed, updated stream recv window by {} for {}", length, stream);
                        WindowUpdateFrame frame = new WindowUpdateFrame(stream.getId(), level);
                        if (windowFrame == null)
                            windowFrame = frame;
                        else
                            windowFrames = new Frame[]{frame};
                    }
                    else
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Data consumed, stream recv window level {}/{} for {}", level, maxLevel, session);
                    }
                }
            }

            if (windowFrame != null)
                session.control(stream, Callback.Adapter.INSTANCE, windowFrame, windowFrames);
        }
    }
}
