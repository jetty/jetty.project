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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ManagedObject
public abstract class AbstractFlowControlStrategy implements FlowControlStrategy, Dumpable
{
    protected static final Logger LOG = LoggerFactory.getLogger(FlowControlStrategy.class);

    private final AtomicLong sessionStall = new AtomicLong();
    private final AtomicLong sessionStallTime = new AtomicLong();
    private final Map<IStream, Long> streamsStalls = new ConcurrentHashMap<>();
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
    public void onStreamCreated(IStream stream)
    {
        stream.updateSendWindow(initialStreamSendWindow);
        stream.updateRecvWindow(initialStreamRecvWindow);
    }

    @Override
    public void onStreamDestroyed(IStream stream)
    {
        streamsStalls.remove(stream);
    }

    @Override
    public void updateInitialStreamWindow(ISession session, int initialStreamWindow, boolean local)
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
                ((IStream)stream).updateRecvWindow(delta);
                if (LOG.isDebugEnabled())
                    LOG.debug("Updated initial stream recv window {} -> {} for {}", previousInitialStreamWindow, initialStreamWindow, stream);
            }
            else
            {
                session.onWindowUpdate((IStream)stream, new WindowUpdateFrame(stream.getId(), delta));
            }
        }
    }

    @Override
    public void onWindowUpdate(ISession session, IStream stream, WindowUpdateFrame frame)
    {
        int delta = frame.getWindowDelta();
        if (frame.getStreamId() > 0)
        {
            // The stream may have been removed concurrently.
            if (stream != null)
            {
                int oldSize = stream.updateSendWindow(delta);
                if (LOG.isDebugEnabled())
                    LOG.debug("Updated stream send window {} -> {} for {}", oldSize, oldSize + delta, stream);
                if (oldSize <= 0)
                    onStreamUnstalled(stream);
            }
        }
        else
        {
            int oldSize = session.updateSendWindow(delta);
            if (LOG.isDebugEnabled())
                LOG.debug("Updated session send window {} -> {} for {}", oldSize, oldSize + delta, session);
            if (oldSize <= 0)
                onSessionUnstalled(session);
        }
    }

    @Override
    public void onDataReceived(ISession session, IStream stream, int length)
    {
        int oldSize = session.updateRecvWindow(-length);
        if (LOG.isDebugEnabled())
            LOG.debug("Data received, {} bytes, updated session recv window {} -> {} for {}", length, oldSize, oldSize - length, session);

        if (stream != null)
        {
            oldSize = stream.updateRecvWindow(-length);
            if (LOG.isDebugEnabled())
                LOG.debug("Data received, {} bytes, updated stream recv window {} -> {} for {}", length, oldSize, oldSize - length, stream);
        }
    }

    @Override
    public void windowUpdate(ISession session, IStream stream, WindowUpdateFrame frame)
    {
    }

    @Override
    public void onDataSending(IStream stream, int length)
    {
        if (length == 0)
            return;

        ISession session = stream.getSession();
        int oldSessionWindow = session.updateSendWindow(-length);
        int newSessionWindow = oldSessionWindow - length;
        if (LOG.isDebugEnabled())
            LOG.debug("Sending, session send window {} -> {} for {}", oldSessionWindow, newSessionWindow, session);
        if (newSessionWindow <= 0)
            onSessionStalled(session);

        int oldStreamWindow = stream.updateSendWindow(-length);
        int newStreamWindow = oldStreamWindow - length;
        if (LOG.isDebugEnabled())
            LOG.debug("Sending, stream send window {} -> {} for {}", oldStreamWindow, newStreamWindow, stream);
        if (newStreamWindow <= 0)
            onStreamStalled(stream);
    }

    @Override
    public void onDataSent(IStream stream, int length)
    {
    }

    protected void onSessionStalled(ISession session)
    {
        sessionStall.set(System.nanoTime());
        if (LOG.isDebugEnabled())
            LOG.debug("Session stalled {}", session);
    }

    protected void onStreamStalled(IStream stream)
    {
        streamsStalls.put(stream, System.nanoTime());
        if (LOG.isDebugEnabled())
            LOG.debug("Stream stalled {}", stream);
    }

    protected void onSessionUnstalled(ISession session)
    {
        long stallTime = System.nanoTime() - sessionStall.getAndSet(0);
        sessionStallTime.addAndGet(stallTime);
        if (LOG.isDebugEnabled())
            LOG.debug("Session unstalled after {} ms {}", TimeUnit.NANOSECONDS.toMillis(stallTime), session);
    }

    protected void onStreamUnstalled(IStream stream)
    {
        Long time = streamsStalls.remove(stream);
        if (time != null)
        {
            long stallTime = System.nanoTime() - time;
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
            currentStallTime = System.nanoTime() - currentStallTime;
        return TimeUnit.NANOSECONDS.toMillis(pastStallTime + currentStallTime);
    }

    @ManagedAttribute(value = "The time, in milliseconds, that the streams flow control has stalled", readonly = true)
    public long getStreamsStallTime()
    {
        long pastStallTime = streamsStallTime.get();
        long now = System.nanoTime();
        long currentStallTime = streamsStalls.values().stream().reduce(0L, (result, time) -> now - time);
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
