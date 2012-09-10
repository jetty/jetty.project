//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpSender
{
    private static final Logger LOG = Log.getLogger(HttpSender.class);

    private final HttpGenerator generator = new HttpGenerator();
    private final ResponseNotifier responseNotifier = new ResponseNotifier();
    private final HttpConnection connection;
    private final RequestNotifier requestNotifier;
    private long contentLength;
    private Iterator<ByteBuffer> contentChunks;
    private ByteBuffer header;
    private ByteBuffer chunk;
    private volatile boolean committed;
    private volatile boolean failed;

    public HttpSender(HttpConnection connection)
    {
        this.connection = connection;
        this.requestNotifier = new RequestNotifier(connection.getHttpClient());
    }

    public void send(HttpExchange exchange)
    {
        LOG.debug("Sending {}", exchange.request());
        requestNotifier.notifyBegin(exchange.request());
        ContentProvider content = exchange.request().content();
        this.contentLength = content == null ? -1 : content.length();
        this.contentChunks = content == null ? Collections.<ByteBuffer>emptyIterator() : content.iterator();
        send();
    }

    private void send()
    {
        try
        {
            HttpClient client = connection.getHttpClient();
            EndPoint endPoint = connection.getEndPoint();
            HttpExchange exchange = connection.getExchange();
            ByteBufferPool byteBufferPool = client.getByteBufferPool();
            final Request request = exchange.request();
            HttpGenerator.RequestInfo info = null;
            ByteBuffer content = contentChunks.hasNext() ? contentChunks.next() : BufferUtil.EMPTY_BUFFER;
            boolean lastContent = !contentChunks.hasNext();
            while (true)
            {
                HttpGenerator.Result result = generator.generateRequest(info, header, chunk, content, lastContent);
                switch (result)
                {
                    case NEED_INFO:
                    {
                        String path = request.path();
                        Fields fields = request.params();
                        if (!fields.isEmpty())
                        {
                            path += "?";
                            for (Iterator<Fields.Field> fieldIterator = fields.iterator(); fieldIterator.hasNext();)
                            {
                                Fields.Field field = fieldIterator.next();
                                String[] values = field.values();
                                for (int i = 0; i < values.length; ++i)
                                {
                                    if (i > 0)
                                        path += "&";
                                    path += field.name() + "=";
                                    path += URLEncoder.encode(values[i], "UTF-8");
                                }
                                if (fieldIterator.hasNext())
                                    path += "&";
                            }
                        }
                        info = new HttpGenerator.RequestInfo(request.version(), request.headers(), contentLength, request.method().asString(), path);
                        break;
                    }
                    case NEED_HEADER:
                    {
                        header = byteBufferPool.acquire(client.getRequestBufferSize(), false);
                        break;
                    }
                    case NEED_CHUNK:
                    {
                        chunk = byteBufferPool.acquire(HttpGenerator.CHUNK_SIZE, false);
                        break;
                    }
                    case FLUSH:
                    {
                        StatefulExecutorCallback callback = new StatefulExecutorCallback(client.getExecutor())
                        {
                            @Override
                            protected void pendingCompleted()
                            {
                                if (!committed)
                                    committed(request);
                                send();
                            }

                            @Override
                            protected void failed(Throwable x)
                            {
                                fail(x);
                            }
                        };
                        if (header == null)
                            header = BufferUtil.EMPTY_BUFFER;
                        if (chunk == null)
                            chunk = BufferUtil.EMPTY_BUFFER;
                        endPoint.write(null, callback, header, chunk, content);
                        if (callback.pending())
                            return;

                        if (callback.completed())
                        {
                            if (!committed)
                                committed(request);

                            releaseBuffers();
                            content = contentChunks.hasNext() ? contentChunks.next() : BufferUtil.EMPTY_BUFFER;
                            lastContent = !contentChunks.hasNext();
                        }
                        break;
                    }
                    case SHUTDOWN_OUT:
                    {
                        endPoint.shutdownOutput();
                        break;
                    }
                    case CONTINUE:
                    {
                        break;
                    }
                    case DONE:
                    {
                        if (generator.isEnd() && !failed)
                            success();
                        return;
                    }
                    default:
                    {
                        throw new IllegalStateException("Unknown result " + result);
                    }
                }
            }
        }
        catch (Exception x)
        {
            LOG.debug(x);
            fail(x);
        }
        finally
        {
            releaseBuffers();
        }
    }

    protected void committed(Request request)
    {
        LOG.debug("Committed {}", request);
        committed = true;
        requestNotifier.notifyHeaders(request);
    }

    protected void success()
    {
        // Cleanup first
        generator.reset();
        committed = false;

        // Notify after
        HttpExchange exchange = connection.getExchange();
        Request request = exchange.request();
        LOG.debug("Sent {}", request);

        boolean exchangeCompleted = exchange.requestComplete(true);

        // It is important to notify *after* we reset because
        // the notification may trigger another request/response
        requestNotifier.notifySuccess(request);
        if (exchangeCompleted)
        {
            HttpConversation conversation = exchange.conversation();
            Result result = new Result(request, exchange.response());
            responseNotifier.notifyComplete(conversation.listener(), result);
        }
    }

    protected void fail(Throwable failure)
    {
        // Cleanup first
        BufferUtil.clear(header);
        BufferUtil.clear(chunk);
        releaseBuffers();
        connection.getEndPoint().shutdownOutput();
        generator.abort();
        failed = true;

        // Notify after
        HttpExchange exchange = connection.getExchange();
        Request request = exchange.request();
        LOG.debug("Failed {}", request);

        boolean exchangeCompleted = exchange.requestComplete(false);
        if (!exchangeCompleted && !committed)
            exchangeCompleted = exchange.responseComplete(false);

        requestNotifier.notifyFailure(request, failure);
        if (exchangeCompleted)
        {
            HttpConversation conversation = exchange.conversation();
            Result result = new Result(request, failure, exchange.response());
            responseNotifier.notifyComplete(conversation.listener(), result);
        }
    }

    private void releaseBuffers()
    {
        ByteBufferPool bufferPool = connection.getHttpClient().getByteBufferPool();
        if (!BufferUtil.hasContent(header))
        {
            bufferPool.release(header);
            header = null;
        }
        if (!BufferUtil.hasContent(chunk))
        {
            bufferPool.release(chunk);
            chunk = null;
        }
    }

    private static abstract class StatefulExecutorCallback implements Callback<Void>, Runnable
    {
        private final AtomicReference<State> state = new AtomicReference<>(State.INCOMPLETE);
        private final Executor executor;

        private StatefulExecutorCallback(Executor executor)
        {
            this.executor = executor;
        }

        @Override
        public final void completed(final Void context)
        {
            State previous = state.get();
            while (true)
            {
                if (state.compareAndSet(previous, State.COMPLETE))
                    break;
                previous = state.get();
            }
            if (previous == State.PENDING)
                executor.execute(this);
        }

        @Override
        public final void run()
        {
            pendingCompleted();
        }

        protected abstract void pendingCompleted();

        @Override
        public final void failed(Void context, final Throwable x)
        {
            State previous = state.get();
            while (true)
            {
                if (state.compareAndSet(previous, State.FAILED))
                    break;
                previous = state.get();
            }
            if (previous == State.PENDING)
            {
                executor.execute(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        failed(x);
                    }
                });
            }
            else
            {
                failed(x);
            }
        }

        protected abstract void failed(Throwable x);

        public boolean pending()
        {
            return state.compareAndSet(State.INCOMPLETE, State.PENDING);
        }

        public boolean completed()
        {
            return state.get() == State.COMPLETE;
        }

        public boolean failed()
        {
            return state.get() == State.FAILED;
        }

        private enum State
        {
            INCOMPLETE, PENDING, COMPLETE, FAILED
        }
    }
}
