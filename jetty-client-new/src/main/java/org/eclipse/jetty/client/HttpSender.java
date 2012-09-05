package org.eclipse.jetty.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpSender
{
    private static final Logger LOG = Log.getLogger(HttpSender.class);

    private final HttpGenerator generator = new HttpGenerator();
    private final AtomicReference<HttpExchange> exchange = new AtomicReference<>();
    private final HttpConnection connection;

    private long contentLength;
    private Iterator<ByteBuffer> contentChunks;
    private ByteBuffer header;
    private ByteBuffer chunk;
    private boolean headersComplete;
    private boolean failed;

    public HttpSender(HttpConnection connection)
    {
        this.connection = connection;
    }

    public void send(HttpExchange exchange)
    {
        if (this.exchange.compareAndSet(null, exchange))
        {
            ContentProvider content = exchange.request().content();
            this.contentLength = content == null ? -1 : content.length();
            this.contentChunks = content == null ? Collections.<ByteBuffer>emptyIterator() : content.iterator();
            send();
        }
        else
        {
            throw new IllegalStateException();
        }
    }

    private void send()
    {
        try
        {
            HttpClient client = connection.getHttpClient();
            EndPoint endPoint = connection.getEndPoint();
            ByteBufferPool byteBufferPool = client.getByteBufferPool();
            final Request request = exchange.get().request();
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
                        info = new HttpGenerator.RequestInfo(request.version(), request.headers(), contentLength, request.method().asString(), request.path());
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
                                notifyRequestHeadersComplete(request);
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
                        if (content == null)
                            content = BufferUtil.EMPTY_BUFFER;
                        endPoint.write(null, callback, header, chunk, content);
                        if (callback.pending())
                            return;

                        if (callback.completed())
                        {
                            if (!headersComplete)
                            {
                                headersComplete = true;
                                notifyRequestHeadersComplete(request);
                            }
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
        catch (IOException x)
        {
            LOG.debug(x);
            fail(x);
        }
        finally
        {
            releaseBuffers();
        }
    }

    protected void success()
    {
        // Cleanup first
        generator.reset();
        headersComplete = false;

        // Notify after
        HttpExchange exchange = this.exchange.getAndSet(null);
        LOG.debug("{} succeeded", exchange.request());
        exchange.requestDone(true);

        // It is important to notify *after* we reset because
        // the notification may trigger another request/response
        notifyRequestSuccess(exchange.request());
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
        HttpExchange exchange = this.exchange.getAndSet(null);
        exchange.requestDone(false);

        notifyRequestFailure(exchange.request(), failure);
        notifyResponseFailure(exchange.listener(), failure);
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

    private void notifyRequestHeadersComplete(Request request)
    {
        Request.Listener listener = request.listener();
        try
        {
            if (listener != null)
                listener.onHeaders(request);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    private void notifyRequestSuccess(Request request)
    {
        Request.Listener listener = request.listener();
        try
        {
            if (listener != null)
                listener.onSuccess(request);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    private void notifyRequestFailure(Request request, Throwable failure)
    {
        Request.Listener listener = request.listener();
        try
        {
            if (listener != null)
                listener.onFailure(request, failure);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    private void notifyResponseFailure(Response.Listener listener, Throwable failure)
    {
        try
        {
            if (listener != null)
                listener.onFailure(null, failure);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
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
