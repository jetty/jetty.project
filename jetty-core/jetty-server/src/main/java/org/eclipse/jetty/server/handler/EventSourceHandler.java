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

package org.eclipse.jetty.server.handler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link Handler} that implements the
 * <a href="https://html.spec.whatwg.org/multipage/server-sent-events.html">Server-Sent Events</a> protocol,
 * also known as "event source".</p>
 * <p>This handler must be subclassed to implement abstract method {@link #newEventSource(Request)}
 * to return an instance of {@link EventSource} that allows applications to be notified of events
 * and to emit events.</p>
 * <p>Server-Sent Events is a unidirectional protocol: the server can push data to the client,
 * but the client cannot send data to the server. For bidirectional communication, consider
 * using WebSocket instead.</p>
 * <p>The protocol uses the {@code text/event-stream} content type, and supports the following
 * fields: {@code event:}, {@code data:}, {@code id:}, and {@code retry:}. This implementation
 * supports {@code event:}, {@code data:}, and comments (lines starting with {@code :}).</p>
 * <p>Example usage:</p>
 * <pre>{@code
 * EventSourceHandler eventSourceHandler = new EventSourceHandler()
 * {
 *     @Override
 *     protected EventSource newEventSource(Request request)
 *     {
 *         return new EventSource()
 *         {
 *             @Override
 *             public void onOpen(Emitter emitter) throws IOException
 *             {
 *                 emitter.data("connected");
 *             }
 *
 *             @Override
 *             public void onClose()
 *             {
 *             }
 *         };
 *     }
 * };
 * }</pre>
 *
 * @see EventSource
 */
public abstract class EventSourceHandler extends Handler.Abstract
{
    private static final Logger LOG = LoggerFactory.getLogger(EventSourceHandler.class);
    private static final byte[] CRLF = new byte[]{'\r', '\n'};
    private static final byte[] CRLF_CRLF = new byte[]{'\r', '\n', '\r', '\n'};
    private static final byte[] EVENT_FIELD = "event: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DATA_FIELD = "data: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] COMMENT_FIELD = ": ".getBytes(StandardCharsets.UTF_8);

    private Duration heartBeatPeriod = Duration.ofSeconds(10);

    /**
     * @return the heartbeat period
     */
    public Duration getHeartBeatPeriod()
    {
        return heartBeatPeriod;
    }

    /**
     * <p>Sets the heartbeat period.</p>
     * <p>The heartbeat is a newline written to the response to detect
     * when the client has closed the connection.</p>
     *
     * @param heartBeatPeriod the heartbeat period
     */
    public void setHeartBeatPeriod(Duration heartBeatPeriod)
    {
        this.heartBeatPeriod = heartBeatPeriod;
    }

    @Override
    public InvocationType getInvocationType()
    {
        return InvocationType.BLOCKING;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        if (!HttpMethod.GET.is(request.getMethod()))
            return false;

        List<String> accepts = request.getHeaders().getValuesList(HttpHeader.ACCEPT);
        if (accepts.stream().noneMatch(accept -> accept.contains(MimeTypes.Type.TEXT_EVENT_STREAM.asString())))
            return false;

        EventSource eventSource = newEventSource(request);
        if (eventSource == null)
        {
            Response.writeError(request, response, callback, HttpStatus.SERVICE_UNAVAILABLE_503);
            return true;
        }

        respond(response);
        Scheduler scheduler = request.getComponents().getScheduler();
        EventSourceEmitter emitter = new EventSourceEmitter(eventSource, response, callback, scheduler);
        emitter.scheduleHeartBeat();
        open(eventSource, emitter);
        return true;
    }

    /**
     * <p>Creates a new {@link EventSource} for the given request.</p>
     * <p>Subclasses must implement this method to return an {@link EventSource} instance
     * that will be used to handle the event source connection.</p>
     *
     * @param request the HTTP request
     * @return a new {@link EventSource} instance, or {@code null} to reject the request with a 503 status
     */
    protected abstract EventSource newEventSource(Request request);

    /**
     * <p>Writes the response headers for the event source connection.</p>
     * <p>Subclasses may override this method to customize the response headers.</p>
     *
     * @param response the HTTP response
     */
    protected void respond(Response response)
    {
        response.setStatus(HttpStatus.OK_200);
        response.getHeaders().put(MimeTypes.Type.TEXT_EVENT_STREAM_UTF_8.getContentTypeField());
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-cache");
        response.getHeaders().put(HttpHeader.CONNECTION, "close");
    }

    /**
     * <p>Opens the event source connection.</p>
     * <p>Subclasses may override this method to perform custom actions when
     * the event source connection is opened.</p>
     *
     * @param eventSource the event source
     * @param emitter the emitter
     * @throws IOException if an I/O error occurs
     */
    protected void open(EventSource eventSource, Emitter emitter) throws IOException
    {
        eventSource.onOpen(emitter);
    }

    /**
     * <p>The passive half of an event source connection.</p>
     * <p>{@link EventSource} allows applications to be notified of events happening on the connection:
     * the opening of the connection via {@link #onOpen(Emitter)}, and the closing of the connection
     * via {@link #onClose()}.</p>
     *
     * @see Emitter
     */
    public interface EventSource
    {
        /**
         * <p>Callback method invoked when an event source connection is opened.</p>
         *
         * @param emitter the {@link Emitter} instance that allows to operate on the connection
         * @throws IOException if the implementation throws such exception
         */
        void onOpen(Emitter emitter) throws IOException;

        /**
         * <p>Callback method invoked when an event source connection is closed.</p>
         */
        void onClose();
    }

    /**
     * <p>The active half of an event source connection.</p>
     * <p>{@link Emitter} allows applications to operate on the connection by sending events,
     * data, or comments, or by closing the connection.</p>
     * <p>{@link Emitter} instances are fully thread-safe and can be used from multiple threads.</p>
     *
     * @see EventSource
     */
    public interface Emitter
    {
        /**
         * <p>Sends a named event with data to the client.</p>
         * <p>When invoked as: {@code event("foo", "bar")}, the client will receive:</p>
         * <pre>
         * event: foo
         * data: bar
         * </pre>
         *
         * @param name the event name
         * @param data the data to be sent
         * @throws IOException if an I/O error occurs
         * @see #data(String)
         */
        void event(String name, String data) throws IOException;

        /**
         * <p>Sends a default event with data to the client.</p>
         * <p>When invoked as: {@code data("baz")}, the client will receive:</p>
         * <pre>
         * data: baz
         * </pre>
         * <p>When invoked as: {@code data("foo\r\nbar\rbaz\nbax")}, the client will receive:</p>
         * <pre>
         * data: foo
         * data: bar
         * data: baz
         * data: bax
         * </pre>
         *
         * @param data the data to be sent
         * @throws IOException if an I/O error occurs
         */
        void data(String data) throws IOException;

        /**
         * <p>Sends a comment to the client.</p>
         * <p>When invoked as: {@code comment("foo")}, the client will receive:</p>
         * <pre>
         * : foo
         * </pre>
         *
         * @param comment the comment to send
         * @throws IOException if an I/O error occurs
         */
        void comment(String comment) throws IOException;

        /**
         * <p>Closes this event source connection.</p>
         */
        void close();
    }

    private class EventSourceEmitter implements Emitter, Runnable
    {
        private final AutoLock lock = new AutoLock();
        private final EventSource eventSource;
        private final OutputStream outputStream;
        private final Callback callback;
        private final Scheduler scheduler;
        private Scheduler.Task heartBeat;
        private boolean closed;

        private EventSourceEmitter(EventSource eventSource, Response response, Callback callback, Scheduler scheduler) throws IOException
        {
            this.eventSource = eventSource;
            this.outputStream = Content.Sink.asOutputStream(response);
            this.callback = callback;
            this.scheduler = scheduler;
            // Flush to commit the response headers
            outputStream.flush();
        }

        @Override
        public void event(String name, String data) throws IOException
        {
            try (AutoLock l = lock.lock())
            {
                if (closed)
                    throw new IOException("closed");
                outputStream.write(EVENT_FIELD);
                outputStream.write(name.getBytes(StandardCharsets.UTF_8));
                outputStream.write(CRLF);
                lockedData(data);
            }
        }

        @Override
        public void data(String data) throws IOException
        {
            try (AutoLock l = lock.lock())
            {
                if (closed)
                    throw new IOException("closed");
                lockedData(data);
            }
        }

        private void lockedData(String data) throws IOException
        {
            assert lock.isHeldByCurrentThread();

            BufferedReader reader = new BufferedReader(new StringReader(data));
            String line;
            while ((line = reader.readLine()) != null)
            {
                outputStream.write(DATA_FIELD);
                outputStream.write(line.getBytes(StandardCharsets.UTF_8));
                outputStream.write(CRLF);
            }
            outputStream.write(CRLF);
            outputStream.flush();
        }

        @Override
        public void comment(String comment) throws IOException
        {
            try (AutoLock l = lock.lock())
            {
                if (closed)
                    throw new IOException("closed");
                outputStream.write(COMMENT_FIELD);
                outputStream.write(comment.getBytes(StandardCharsets.UTF_8));
                outputStream.write(CRLF_CRLF);
                outputStream.flush();
            }
        }

        @Override
        public void run()
        {
            // If the other peer closes the connection, the first
            // flush() should generate a TCP reset that is detected
            // on the second flush()
            try (AutoLock l = lock.lock())
            {
                if (closed)
                    return;
                outputStream.write('\r');
                outputStream.flush();
                outputStream.write('\n');
                outputStream.flush();

                // We could write, reschedule heartbeat
                scheduleHeartBeat();
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Heartbeat failed", x);

                IO.close(this::close);

                try
                {
                    // The other peer closed the connection
                    eventSource.onClose();
                }
                catch (Throwable t)
                {
                    ExceptionUtil.addSuppressedIfNotAssociated(x, t);
                    if (LOG.isDebugEnabled())
                        LOG.debug("EventSource.onClose() failed", x);
                }
            }
        }

        private void scheduleHeartBeat()
        {
            try (AutoLock l = lock.lock())
            {
                if (!closed)
                    heartBeat = scheduler.schedule(this, heartBeatPeriod);
            }
        }

        @Override
        public void close()
        {
            try (AutoLock l = lock.lock())
            {
                if (closed)
                    return;
                closed = true;
                if (heartBeat != null)
                    heartBeat.cancel();
            }
            callback.succeeded();
        }
    }
}
