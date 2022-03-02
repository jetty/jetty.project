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

package org.eclipse.jetty.ee9.servlets;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.util.thread.AutoLock;

/**
 * <p>A servlet that implements the <a href="http://www.w3.org/TR/eventsource/">event source protocol</a>,
 * also known as "server sent events".</p>
 * <p>This servlet must be subclassed to implement abstract method {@link #newEventSource(HttpServletRequest)}
 * to return an instance of {@link EventSource} that allows application to listen for event source events
 * and to emit event source events.</p>
 * <p>This servlet supports the following configuration parameters:</p>
 * <ul>
 * <li><code>heartBeatPeriod</code>, that specifies the heartbeat period, in seconds, used to check
 * whether the connection has been closed by the client; defaults to 10 seconds.</li>
 * </ul>
 *
 * <p>NOTE: there is currently no support for <code>last-event-id</code>.</p>
 */
public abstract class EventSourceServlet extends HttpServlet
{
    private static final byte[] CRLF = new byte[]{'\r', '\n'};
    private static final byte[] EVENT_FIELD = "event: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DATA_FIELD = "data: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] COMMENT_FIELD = ": ".getBytes(StandardCharsets.UTF_8);

    private ScheduledExecutorService scheduler;
    private int heartBeatPeriod = 10;

    @Override
    public void init() throws ServletException
    {
        String heartBeatPeriodParam = getServletConfig().getInitParameter("heartBeatPeriod");
        if (heartBeatPeriodParam != null)
            heartBeatPeriod = Integer.parseInt(heartBeatPeriodParam);
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public void destroy()
    {
        if (scheduler != null)
            scheduler.shutdown();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        @SuppressWarnings("unchecked")
        Enumeration<String> acceptValues = request.getHeaders("Accept");
        while (acceptValues.hasMoreElements())
        {
            String accept = acceptValues.nextElement();
            if (accept.equals("text/event-stream"))
            {
                EventSource eventSource = newEventSource(request);
                if (eventSource == null)
                {
                    response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                }
                else
                {
                    respond(request, response);
                    AsyncContext async = request.startAsync();
                    // Infinite timeout because the continuation is never resumed,
                    // but only completed on close
                    async.setTimeout(0);
                    EventSourceEmitter emitter = new EventSourceEmitter(eventSource, async);
                    emitter.scheduleHeartBeat();
                    open(eventSource, emitter);
                }
                return;
            }
        }
        super.doGet(request, response);
    }

    protected abstract EventSource newEventSource(HttpServletRequest request);

    protected void respond(HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/event-stream");
        // By adding this header, and not closing the connection,
        // we disable HTTP chunking, and we can use write()+flush()
        // to send data in the text/event-stream protocol
        response.addHeader("Connection", "close");
        response.flushBuffer();
    }

    protected void open(EventSource eventSource, EventSource.Emitter emitter) throws IOException
    {
        eventSource.onOpen(emitter);
    }

    protected class EventSourceEmitter implements EventSource.Emitter, Runnable
    {
        private final AutoLock lock = new AutoLock();
        private final EventSource eventSource;
        private final AsyncContext async;
        private final ServletOutputStream output;
        private Future<?> heartBeat;
        private boolean closed;

        public EventSourceEmitter(EventSource eventSource, AsyncContext async) throws IOException
        {
            this.eventSource = eventSource;
            this.async = async;
            this.output = async.getResponse().getOutputStream();
        }

        @Override
        public void event(String name, String data) throws IOException
        {
            try (AutoLock l = lock.lock())
            {
                output.write(EVENT_FIELD);
                output.write(name.getBytes(StandardCharsets.UTF_8));
                output.write(CRLF);
                data(data);
            }
        }

        @Override
        public void data(String data) throws IOException
        {
            try (AutoLock l = lock.lock())
            {
                BufferedReader reader = new BufferedReader(new StringReader(data));
                String line;
                while ((line = reader.readLine()) != null)
                {
                    output.write(DATA_FIELD);
                    output.write(line.getBytes(StandardCharsets.UTF_8));
                    output.write(CRLF);
                }
                output.write(CRLF);
                flush();
            }
        }

        @Override
        public void comment(String comment) throws IOException
        {
            try (AutoLock l = lock.lock())
            {
                output.write(COMMENT_FIELD);
                output.write(comment.getBytes(StandardCharsets.UTF_8));
                output.write(CRLF);
                output.write(CRLF);
                flush();
            }
        }

        @Override
        public void run()
        {
            // If the other peer closes the connection, the first
            // flush() should generate a TCP reset that is detected
            // on the second flush()
            try
            {
                try (AutoLock l = lock.lock())
                {
                    output.write('\r');
                    flush();
                    output.write('\n');
                    flush();
                }
                // We could write, reschedule heartbeat
                scheduleHeartBeat();
            }
            catch (IOException x)
            {
                // The other peer closed the connection
                close();
                eventSource.onClose();
            }
        }

        protected void flush() throws IOException
        {
            async.getResponse().flushBuffer();
        }

        @Override
        public void close()
        {
            try (AutoLock l = lock.lock())
            {
                closed = true;
                heartBeat.cancel(false);
            }
            async.complete();
        }

        private void scheduleHeartBeat()
        {
            try (AutoLock l = lock.lock())
            {
                if (!closed)
                    heartBeat = scheduler.schedule(this, heartBeatPeriod, TimeUnit.SECONDS);
            }
        }
    }
}
