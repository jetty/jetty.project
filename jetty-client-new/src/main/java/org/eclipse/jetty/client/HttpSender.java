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

class HttpSender
{
    private static final Logger LOG = Log.getLogger(HttpSender.class);

    private final HttpGenerator generator = new HttpGenerator();
    private HttpConversation conversation;
    private long contentLength;
    private Iterator<ByteBuffer> contentChunks;
    private ByteBuffer header;
    private ByteBuffer chunk;
    private boolean requestHeadersComplete;

    public void send(HttpConversation conversation)
    {
        this.conversation = conversation;
        ContentProvider content = conversation.request().content();
        this.contentLength = content == null ? -1 : content.length();
        this.contentChunks = content == null ? Collections.<ByteBuffer>emptyIterator() : content.iterator();
        send();
    }

    private void send()
    {
        try
        {
            HttpConnection connection = conversation.connection();
            EndPoint endPoint = connection.getEndPoint();
            HttpClient client = connection.getHttpClient();
            ByteBufferPool byteBufferPool = client.getByteBufferPool();
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
                        Request request = conversation.request();
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
                                notifyRequestHeadersComplete();
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
                            if (!requestHeadersComplete)
                            {
                                requestHeadersComplete = true;
                                notifyRequestHeadersComplete();
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
                        if (generator.isEnd())
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
        notifyRequestSuccess();
    }

    protected void fail(Throwable x)
    {
        BufferUtil.clear(header);
        BufferUtil.clear(chunk);
        releaseBuffers();
        notifyRequestFailure(x);
        notifyResponseFailure(x);
        conversation.connection().getEndPoint().shutdownOutput();
        generator.abort();
    }

    private void releaseBuffers()
    {
        ByteBufferPool byteBufferPool = conversation.connection().getHttpClient().getByteBufferPool();
        if (!BufferUtil.hasContent(header))
        {
            byteBufferPool.release(header);
            header = null;
        }
        if (!BufferUtil.hasContent(chunk))
        {
            byteBufferPool.release(chunk);
            chunk = null;
        }
    }

    private void notifyRequestHeadersComplete()
    {
        Request request = conversation.request();
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

    private void notifyRequestSuccess()
    {
        Request request = conversation.request();
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

    private void notifyRequestFailure(Throwable x)
    {
        Request request = conversation.request();
        Request.Listener listener = request.listener();
        try
        {
            if (listener != null)
                listener.onFailure(request, x);
        }
        catch (Exception xx)
        {
            LOG.info("Exception while notifying listener " + listener, xx);
        }
    }

    private void notifyResponseFailure(Throwable x)
    {

        Response.Listener listener = conversation.listener();
        try
        {
            if (listener != null)
                listener.onFailure(null, x);
        }
        catch (Exception xx)
        {
            LOG.info("Exception while notifying listener " + listener, xx);
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
