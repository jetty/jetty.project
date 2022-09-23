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

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.http2.internal.HTTP2Session;
import org.eclipse.jetty.http2.internal.HTTP2Stream;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ManagedObject
public abstract class AbstractFlowControlStrategy implements FlowControlStrategy, Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractFlowControlStrategy.class);

    private final AtomicLong sessionStall = new AtomicLong();
    private final AtomicLong sessionStallTime = new AtomicLong();
    private final Map<Stream, Long> streamsStalls = new ConcurrentHashMap<>();
    private final AtomicLong streamsStallTime = new AtomicLong();
    private int initialStreamSendWindow;
    private int initialStreamRecvWindow;

    public AbstractFlowControlStrategy(int initialStreamSendWindow)
    {
        this.initialStreamSendWindow = initialStreamSendWindow;
        this.initialStreamRecvWindow = DEFAULT_WINDOW_SIZE;
    }

    @ManagedAttribute(value = "The initial size of stream's flow control send window", readonly = true)
    public int getInitialStreamSendWindow()
    {
        return initialStreamSendWindow;
    }

    @ManagedAttribute(value = "The initial size of stream's flow control receive window", readonly = true)
    public int getInitialStreamRecvWindow()
    {
        return initialStreamRecvWindow;
    }

    @Override
    public void onStreamCreated(Stream stream)
    {
        updateSendWindow(stream, getInitialStreamSendWindow());
        updateRecvWindow(stream, getInitialStreamRecvWindow());
    }

    @Override
    public void onStreamDestroyed(Stream stream)
    {
        streamsStalls.remove(stream);
    }

    @Override
    public void updateInitialStreamWindow(Session session, int initialStreamWindow, boolean local)
    {
        int previousInitialStreamWindow;
        if (local)
        {
            previousInitialStreamWindow = getInitialStreamRecvWindow();
            this.initialStreamRecvWindow = initialStreamWindow;
        }
        else
        {
            previousInitialStreamWindow = getInitialStreamSendWindow();
            this.initialStreamSendWindow = initialStreamWindow;
        }
        int delta = initialStreamWindow - previousInitialStreamWindow;
        if (delta == 0)
            return;

        // SPEC: updates of the initial window size only affect stream windows, not session's.
        for (Stream stream : session.getStreams())
        {
            if (local)
            {
                updateRecvWindow(stream, delta);
                if (LOG.isDebugEnabled())
                    LOG.debug("Updated initial stream recv window {} -> {} for {}", previousInitialStreamWindow, initialStreamWindow, stream);
            }
            else
            {
                updateWindow(session, stream, new WindowUpdateFrame(stream.getId(), delta));
            }
        }
    }

    @Override
    public void onWindowUpdate(Session session, Stream stream, WindowUpdateFrame frame)
    {
        int delta = frame.getWindowDelta();
        if (frame.getStreamId() > 0)
        {
            // The stream may have been removed concurrently.
            if (stream != null)
            {
                int oldSize = updateSendWindow(stream, delta);
                if (LOG.isDebugEnabled())
                    LOG.debug("Updated stream send window {} -> {} for {}", oldSize, oldSize + delta, stream);
                if (oldSize <= 0)
                    onStreamUnstalled(stream);
            }
        }
        else
        {
            int oldSize = updateSendWindow(session, delta);
            if (LOG.isDebugEnabled())
                LOG.debug("Updated session send window {} -> {} for {}", oldSize, oldSize + delta, session);
            if (oldSize <= 0)
                onSessionUnstalled(session);
        }
    }

    @Override
    public void onDataReceived(Session session, Stream stream, int length)
    {
        int oldSize = updateRecvWindow(session, -length);
        if (LOG.isDebugEnabled())
            LOG.debug("Data received, {} bytes, updated session recv window {} -> {} for {}", length, oldSize, oldSize - length, session);

        if (stream != null)
        {
            oldSize = updateRecvWindow(stream, -length);
            if (LOG.isDebugEnabled())
                LOG.debug("Data received, {} bytes, updated stream recv window {} -> {} for {}", length, oldSize, oldSize - length, stream);
        }
    }

    @Override
    public void windowUpdate(Session session, Stream stream, WindowUpdateFrame frame)
    {
    }

    @Override
    public void onDataSending(Stream stream, int length)
    {
        if (length == 0)
            return;

        Session session = stream.getSession();
        int oldSessionWindow = updateSendWindow(session, -length);
        int newSessionWindow = oldSessionWindow - length;
        if (LOG.isDebugEnabled())
            LOG.debug("Sending, session send window {} -> {} for {}", oldSessionWindow, newSessionWindow, session);
        if (newSessionWindow <= 0)
            onSessionStalled(session);

        int oldStreamWindow = updateSendWindow(stream, -length);
        int newStreamWindow = oldStreamWindow - length;
        if (LOG.isDebugEnabled())
            LOG.debug("Sending, stream send window {} -> {} for {}", oldStreamWindow, newStreamWindow, stream);
        if (newStreamWindow <= 0)
            onStreamStalled(stream);
    }

    @Override
    public void onDataSent(Stream stream, int length)
    {
    }

    protected void updateWindow(Session session, Stream stream, WindowUpdateFrame frame)
    {
        ((HTTP2Session)session).onWindowUpdate((HTTP2Stream)stream, frame);
    }

    protected int updateRecvWindow(Session session, int value)
    {
        return ((HTTP2Session)session).updateRecvWindow(value);
    }

    protected int updateSendWindow(Session session, int value)
    {
        return ((HTTP2Session)session).updateSendWindow(value);
    }

    protected int updateRecvWindow(Stream stream, int value)
    {
        return ((HTTP2Stream)stream).updateRecvWindow(value);
    }

    protected int updateSendWindow(Stream stream, int value)
    {
        return ((HTTP2Stream)stream).updateSendWindow(value);
    }

    protected void sendWindowUpdate(Session session, Stream stream, List<WindowUpdateFrame> frames)
    {
        ((HTTP2Session)session).frames((HTTP2Stream)stream, frames, Callback.NOOP);
    }

    protected void onSessionStalled(Session session)
    {
        sessionStall.set(NanoTime.now());
        if (LOG.isDebugEnabled())
            LOG.debug("Session stalled {}", session);
    }

    protected void onStreamStalled(Stream stream)
    {
        streamsStalls.put(stream, NanoTime.now());
        if (LOG.isDebugEnabled())
            LOG.debug("Stream stalled {}", stream);
    }

    protected void onSessionUnstalled(Session session)
    {
        long stallTime = NanoTime.since(sessionStall.getAndSet(0));
        sessionStallTime.addAndGet(stallTime);
        if (LOG.isDebugEnabled())
            LOG.debug("Session unstalled after {} ms {}", TimeUnit.NANOSECONDS.toMillis(stallTime), session);
    }

    protected void onStreamUnstalled(Stream stream)
    {
        Long time = streamsStalls.remove(stream);
        if (time != null)
        {
            long stallTime = NanoTime.since(time);
            streamsStallTime.addAndGet(stallTime);
            if (LOG.isDebugEnabled())
                LOG.debug("Stream unstalled after {} ms {}", TimeUnit.NANOSECONDS.toMillis(stallTime), stream);
        }
    }

    @ManagedAttribute(value = "The time, in milliseconds, that the session flow control has stalled", readonly = true)
    public long getSessionStallTime()
    {
        long pastStallTime = sessionStallTime.get();
        long currentStallTime = sessionStall.get();
        if (currentStallTime != 0)
            currentStallTime = NanoTime.since(currentStallTime);
        return TimeUnit.NANOSECONDS.toMillis(pastStallTime + currentStallTime);
    }

    @ManagedAttribute(value = "The time, in milliseconds, that the streams flow control has stalled", readonly = true)
    public long getStreamsStallTime()
    {
        long pastStallTime = streamsStallTime.get();
        long now = NanoTime.now();
        long currentStallTime = streamsStalls.values().stream().reduce(0L, (result, time) -> NanoTime.elapsed(time, now));
        return TimeUnit.NANOSECONDS.toMillis(pastStallTime + currentStallTime);
    }

    @ManagedOperation(value = "Resets the statistics", impact = "ACTION")
    public void reset()
    {
        sessionStallTime.set(0);
        streamsStallTime.set(0);
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        out.append(toString()).append(System.lineSeparator());
    }
}
