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

package org.eclipse.jetty.ee9.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.ContentDecoder;
import org.eclipse.jetty.client.GZIPContentDecoder;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.CountingCallback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.component.Destroyable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Servlet 3.1 asynchronous proxy servlet with capability
 * to intercept and modify request/response content.</p>
 * <p>Both the request processing and the I/O are asynchronous.</p>
 *
 * @see ProxyServlet
 * @see AsyncProxyServlet
 * @see ConnectHandler
 */
public class AsyncMiddleManServlet extends AbstractProxyServlet
{
    private static final String PROXY_REQUEST_CONTENT_COMMITTED_ATTRIBUTE = AsyncMiddleManServlet.class.getName() + ".proxyRequestContentCommitted";
    private static final String CLIENT_TRANSFORMER_ATTRIBUTE = AsyncMiddleManServlet.class.getName() + ".clientTransformer";
    private static final String SERVER_TRANSFORMER_ATTRIBUTE = AsyncMiddleManServlet.class.getName() + ".serverTransformer";
    private static final String CONTINUE_ACTION_ATTRIBUTE = AsyncMiddleManServlet.class.getName() + ".continueAction";
    private static final String WRITE_LISTENER_ATTRIBUTE = AsyncMiddleManServlet.class.getName() + ".writeListener";

    @Override
    protected void service(HttpServletRequest clientRequest, HttpServletResponse proxyResponse) throws ServletException, IOException
    {
        String rewrittenTarget = rewriteTarget(clientRequest);
        if (_log.isDebugEnabled())
        {
            StringBuffer target = clientRequest.getRequestURL();
            if (clientRequest.getQueryString() != null)
                target.append("?").append(clientRequest.getQueryString());
            _log.debug("{} rewriting: {} -> {}", getRequestId(clientRequest), target, rewrittenTarget);
        }
        if (rewrittenTarget == null)
        {
            onProxyRewriteFailed(clientRequest, proxyResponse);
            return;
        }

        Request proxyRequest = newProxyRequest(clientRequest, rewrittenTarget);

        copyRequestHeaders(clientRequest, proxyRequest);

        addProxyHeaders(clientRequest, proxyRequest);

        final AsyncContext asyncContext = clientRequest.startAsync();
        // We do not timeout the continuation, but the proxy request.
        asyncContext.setTimeout(0);
        proxyRequest.timeout(getTimeout(), TimeUnit.MILLISECONDS);

        // If there is content, the send of the proxy request
        // is delayed and performed when the content arrives,
        // to allow optimization of the Content-Length header.
        if (hasContent(clientRequest))
        {
            AsyncRequestContent content = newProxyRequestContent(clientRequest, proxyResponse, proxyRequest);
            proxyRequest.body(content);

            if (expects100Continue(clientRequest))
            {
                // Must delay the call to request.getInputStream()
                // that sends the 100 Continue to the client.
                proxyRequest.attribute(CONTINUE_ACTION_ATTRIBUTE, (Runnable)() ->
                {
                    try
                    {
                        ServletInputStream input = clientRequest.getInputStream();
                        input.setReadListener(newProxyReadListener(clientRequest, proxyResponse, proxyRequest, content));
                    }
                    catch (Throwable failure)
                    {
                        onClientRequestFailure(clientRequest, proxyRequest, proxyResponse, failure);
                    }
                });
                sendProxyRequest(clientRequest, proxyResponse, proxyRequest);
            }
            else
            {
                ServletInputStream input = clientRequest.getInputStream();
                input.setReadListener(newProxyReadListener(clientRequest, proxyResponse, proxyRequest, content));
            }
        }
        else
        {
            sendProxyRequest(clientRequest, proxyResponse, proxyRequest);
        }
    }

    protected AsyncRequestContent newProxyRequestContent(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Request proxyRequest)
    {
        return new ProxyAsyncRequestContent(clientRequest);
    }

    protected ReadListener newProxyReadListener(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Request proxyRequest, AsyncRequestContent content)
    {
        return new ProxyReader(clientRequest, proxyResponse, proxyRequest, content);
    }

    protected ProxyWriter newProxyWriteListener(HttpServletRequest clientRequest, Response proxyResponse)
    {
        return new ProxyWriter(clientRequest, proxyResponse);
    }

    @Override
    protected Response.CompleteListener newProxyResponseListener(HttpServletRequest clientRequest, HttpServletResponse proxyResponse)
    {
        return new ProxyResponseListener(clientRequest, proxyResponse);
    }

    protected ContentTransformer newClientRequestContentTransformer(HttpServletRequest clientRequest, Request proxyRequest)
    {
        return ContentTransformer.IDENTITY;
    }

    protected ContentTransformer newServerResponseContentTransformer(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Response serverResponse)
    {
        return ContentTransformer.IDENTITY;
    }

    @Override
    protected void onContinue(HttpServletRequest clientRequest, Request proxyRequest)
    {
        super.onContinue(clientRequest, proxyRequest);
        Runnable action = (Runnable)proxyRequest.getAttributes().get(CONTINUE_ACTION_ATTRIBUTE);
        action.run();
    }

    private void transform(ContentTransformer transformer, ByteBuffer input, boolean finished, List<ByteBuffer> output) throws IOException
    {
        try
        {
            transformer.transform(input, finished, output);
        }
        catch (Throwable x)
        {
            _log.info("Exception while transforming {} ", transformer, x);
            throw x;
        }
    }

    int readClientRequestContent(ServletInputStream input, byte[] buffer) throws IOException
    {
        return input.read(buffer);
    }

    void writeProxyResponseContent(ServletOutputStream output, ByteBuffer content) throws IOException
    {
        write(output, content);
    }

    private static void write(OutputStream output, ByteBuffer content) throws IOException
    {
        int length = content.remaining();
        int offset = 0;
        byte[] buffer;
        if (content.hasArray())
        {
            offset = content.arrayOffset();
            buffer = content.array();
        }
        else
        {
            buffer = new byte[length];
            content.get(buffer);
        }
        output.write(buffer, offset, length);
    }

    private void cleanup(HttpServletRequest clientRequest)
    {
        ContentTransformer clientTransformer = (ContentTransformer)clientRequest.getAttribute(CLIENT_TRANSFORMER_ATTRIBUTE);
        if (clientTransformer instanceof Destroyable)
            ((Destroyable)clientTransformer).destroy();
        ContentTransformer serverTransformer = (ContentTransformer)clientRequest.getAttribute(SERVER_TRANSFORMER_ATTRIBUTE);
        if (serverTransformer instanceof Destroyable)
            ((Destroyable)serverTransformer).destroy();
    }

    /**
     * <p>Convenience extension of {@link AsyncMiddleManServlet} that offers transparent proxy functionalities.</p>
     *
     * @see org.eclipse.jetty.proxy.AbstractProxyServlet.TransparentDelegate
     */
    public static class Transparent extends AsyncMiddleManServlet
    {
        private final TransparentDelegate delegate = new TransparentDelegate(this);

        @Override
        public void init(ServletConfig config) throws ServletException
        {
            super.init(config);
            delegate.init(config);
        }

        @Override
        protected String rewriteTarget(HttpServletRequest request)
        {
            return delegate.rewriteTarget(request);
        }
    }

    protected class ProxyReader extends IteratingCallback implements ReadListener
    {
        private final byte[] buffer = new byte[getHttpClient().getRequestBufferSize()];
        private final List<ByteBuffer> buffers = new ArrayList<>();
        private final HttpServletRequest clientRequest;
        private final HttpServletResponse proxyResponse;
        private final Request proxyRequest;
        private final AsyncRequestContent content;
        private final int contentLength;
        private final boolean expects100Continue;
        private int length;

        protected ProxyReader(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Request proxyRequest, AsyncRequestContent content)
        {
            this.clientRequest = clientRequest;
            this.proxyResponse = proxyResponse;
            this.proxyRequest = proxyRequest;
            this.content = content;
            this.contentLength = clientRequest.getContentLength();
            this.expects100Continue = expects100Continue(clientRequest);
        }

        @Override
        public void onDataAvailable()
        {
            iterate();
        }

        @Override
        public void onAllDataRead() throws IOException
        {
            if (!content.isClosed())
            {
                process(BufferUtil.EMPTY_BUFFER, new Callback()
                {
                    @Override
                    public void failed(Throwable x)
                    {
                        onError(x);
                    }
                }, true);
            }

            if (_log.isDebugEnabled())
                _log.debug("{} proxying content to upstream completed", getRequestId(clientRequest));
        }

        @Override
        public void onError(Throwable t)
        {
            cleanup(clientRequest);
            onClientRequestFailure(clientRequest, proxyRequest, proxyResponse, t);
        }

        @Override
        protected Action process() throws Exception
        {
            ServletInputStream input = clientRequest.getInputStream();
            while (input.isReady() && !input.isFinished())
            {
                int read = readClientRequestContent(input, buffer);

                if (_log.isDebugEnabled())
                    _log.debug("{} asynchronous read {} bytes on {}", getRequestId(clientRequest), read, input);

                if (read < 0)
                    return Action.SUCCEEDED;

                if (contentLength > 0 && read > 0)
                    length += read;

                ByteBuffer content = read > 0 ? ByteBuffer.wrap(buffer, 0, read) : BufferUtil.EMPTY_BUFFER;
                boolean finished = length == contentLength;
                process(content, this, finished);

                if (read > 0)
                    return Action.SCHEDULED;
            }

            if (input.isFinished())
            {
                if (_log.isDebugEnabled())
                    _log.debug("{} asynchronous read complete on {}", getRequestId(clientRequest), input);
                return Action.SUCCEEDED;
            }
            else
            {
                if (_log.isDebugEnabled())
                    _log.debug("{} asynchronous read pending on {}", getRequestId(clientRequest), input);
                return Action.IDLE;
            }
        }

        private void process(ByteBuffer content, Callback callback, boolean finished) throws IOException
        {
            ContentTransformer transformer = (ContentTransformer)clientRequest.getAttribute(CLIENT_TRANSFORMER_ATTRIBUTE);
            if (transformer == null)
            {
                transformer = newClientRequestContentTransformer(clientRequest, proxyRequest);
                clientRequest.setAttribute(CLIENT_TRANSFORMER_ATTRIBUTE, transformer);
            }

            int contentBytes = content.remaining();

            // Skip transformation for empty non-last buffers.
            if (contentBytes == 0 && !finished)
            {
                callback.succeeded();
                return;
            }

            transform(transformer, content, finished, buffers);

            int newContentBytes = 0;
            int size = buffers.size();
            if (size > 0)
            {
                CountingCallback counter = new CountingCallback(callback, size);
                for (ByteBuffer buffer : buffers)
                {
                    newContentBytes += buffer.remaining();
                    this.content.offer(buffer, counter);
                }
                buffers.clear();
            }

            if (finished)
                this.content.close();

            if (_log.isDebugEnabled())
                _log.debug("{} upstream content transformation {} -> {} bytes", getRequestId(clientRequest), contentBytes, newContentBytes);

            boolean contentCommitted = clientRequest.getAttribute(PROXY_REQUEST_CONTENT_COMMITTED_ATTRIBUTE) != null;
            if (!contentCommitted && (size > 0 || finished))
            {
                clientRequest.setAttribute(PROXY_REQUEST_CONTENT_COMMITTED_ATTRIBUTE, true);
                if (!expects100Continue)
                {
                    proxyRequest.headers(headers -> headers.remove(HttpHeader.CONTENT_LENGTH));
                    sendProxyRequest(clientRequest, proxyResponse, proxyRequest);
                }
            }

            if (size == 0)
                callback.succeeded();
        }

        @Override
        protected void onCompleteFailure(Throwable x)
        {
            onError(x);
        }
    }

    protected class ProxyResponseListener extends Response.Listener.Adapter implements Callback
    {
        private final Callback complete = new CountingCallback(this, 2);
        private final List<ByteBuffer> buffers = new ArrayList<>();
        private final HttpServletRequest clientRequest;
        private final HttpServletResponse proxyResponse;
        private boolean hasContent;
        private long contentLength;
        private long length;
        private Response response;

        protected ProxyResponseListener(HttpServletRequest clientRequest, HttpServletResponse proxyResponse)
        {
            this.clientRequest = clientRequest;
            this.proxyResponse = proxyResponse;
        }

        @Override
        public void onBegin(Response serverResponse)
        {
            response = serverResponse;
            proxyResponse.setStatus(serverResponse.getStatus());
        }

        @Override
        public void onHeaders(Response serverResponse)
        {
            contentLength = serverResponse.getHeaders().getLongField(HttpHeader.CONTENT_LENGTH);
            onServerResponseHeaders(clientRequest, proxyResponse, serverResponse);
        }

        @Override
        public void onContent(final Response serverResponse, ByteBuffer content, final Callback callback)
        {
            try
            {
                int contentBytes = content.remaining();
                if (_log.isDebugEnabled())
                    _log.debug("{} received server content: {} bytes", getRequestId(clientRequest), contentBytes);

                hasContent = true;

                ProxyWriter proxyWriter = (ProxyWriter)clientRequest.getAttribute(WRITE_LISTENER_ATTRIBUTE);
                final boolean committed = proxyWriter != null;
                if (proxyWriter == null)
                {
                    proxyWriter = newProxyWriteListener(clientRequest, serverResponse);
                    clientRequest.setAttribute(WRITE_LISTENER_ATTRIBUTE, proxyWriter);
                }

                ContentTransformer transformer = (ContentTransformer)clientRequest.getAttribute(SERVER_TRANSFORMER_ATTRIBUTE);
                if (transformer == null)
                {
                    transformer = newServerResponseContentTransformer(clientRequest, proxyResponse, serverResponse);
                    clientRequest.setAttribute(SERVER_TRANSFORMER_ATTRIBUTE, transformer);
                }

                length += contentBytes;

                boolean finished = contentLength >= 0 && length == contentLength;
                transform(transformer, content, finished, buffers);

                int newContentBytes = 0;
                int size = buffers.size();
                if (size > 0)
                {
                    Callback counter = size == 1 ? callback : new CountingCallback(callback, size);
                    for (ByteBuffer buffer : buffers)
                    {
                        newContentBytes += buffer.remaining();
                        proxyWriter.offer(buffer, counter);
                    }
                    buffers.clear();
                }
                else
                {
                    proxyWriter.offer(BufferUtil.EMPTY_BUFFER, callback);
                }
                if (finished)
                    proxyWriter.offer(BufferUtil.EMPTY_BUFFER, complete);

                if (_log.isDebugEnabled())
                    _log.debug("{} downstream content transformation {} -> {} bytes", getRequestId(clientRequest), contentBytes, newContentBytes);

                if (committed)
                {
                    proxyWriter.onWritePossible();
                }
                else
                {
                    if (contentLength >= 0)
                        proxyResponse.setContentLength(-1);

                    // Setting the WriteListener triggers an invocation to
                    // onWritePossible(), possibly on a different thread.
                    // We cannot succeed the callback from here, otherwise
                    // we run into a race where the different thread calls
                    // onWritePossible() and succeeding the callback causes
                    // this method to be called again, which also may call
                    // onWritePossible().
                    proxyResponse.getOutputStream().setWriteListener(proxyWriter);
                }
            }
            catch (Throwable x)
            {
                callback.failed(x);
            }
        }

        @Override
        public void onSuccess(final Response serverResponse)
        {
            try
            {
                if (hasContent)
                {
                    // If we had unknown length content, we need to call the
                    // transformer to signal that the content is finished.
                    if (contentLength < 0)
                    {
                        ProxyWriter proxyWriter = (ProxyWriter)clientRequest.getAttribute(WRITE_LISTENER_ATTRIBUTE);
                        ContentTransformer transformer = (ContentTransformer)clientRequest.getAttribute(SERVER_TRANSFORMER_ATTRIBUTE);

                        transform(transformer, BufferUtil.EMPTY_BUFFER, true, buffers);

                        long newContentBytes = 0;
                        int size = buffers.size();
                        if (size > 0)
                        {
                            Callback callback = size == 1 ? complete : new CountingCallback(complete, size);
                            for (ByteBuffer buffer : buffers)
                            {
                                newContentBytes += buffer.remaining();
                                proxyWriter.offer(buffer, callback);
                            }
                            buffers.clear();
                        }
                        else
                        {
                            proxyWriter.offer(BufferUtil.EMPTY_BUFFER, complete);
                        }

                        if (_log.isDebugEnabled())
                            _log.debug("{} downstream content transformation to {} bytes", getRequestId(clientRequest), newContentBytes);

                        proxyWriter.onWritePossible();
                    }
                }
                else
                {
                    complete.succeeded();
                }
            }
            catch (Throwable x)
            {
                complete.failed(x);
            }
        }

        @Override
        public void onComplete(Result result)
        {
            if (result.isSucceeded())
                complete.succeeded();
            else
                complete.failed(result.getFailure());
        }

        @Override
        public void succeeded()
        {
            cleanup(clientRequest);
            onProxyResponseSuccess(clientRequest, proxyResponse, response);
        }

        @Override
        public void failed(Throwable failure)
        {
            cleanup(clientRequest);
            onProxyResponseFailure(clientRequest, proxyResponse, response, failure);
        }
    }

    protected class ProxyWriter implements WriteListener
    {
        private final Queue<Chunk> chunks = new ArrayDeque<>();
        private final HttpServletRequest clientRequest;
        private final Response serverResponse;
        private Chunk chunk;
        private boolean writePending;

        protected ProxyWriter(HttpServletRequest clientRequest, Response serverResponse)
        {
            this.clientRequest = clientRequest;
            this.serverResponse = serverResponse;
        }

        public boolean offer(ByteBuffer content, Callback callback)
        {
            if (_log.isDebugEnabled())
                _log.debug("{} proxying content to downstream: {} bytes {}", getRequestId(clientRequest), content.remaining(), callback);
            return chunks.offer(new Chunk(content, callback));
        }

        @Override
        public void onWritePossible() throws IOException
        {
            ServletOutputStream output = clientRequest.getAsyncContext().getResponse().getOutputStream();

            // If we had a pending write, let's succeed it.
            if (writePending)
            {
                if (_log.isDebugEnabled())
                    _log.debug("{} pending async write complete of {} on {}", getRequestId(clientRequest), chunk, output);
                writePending = false;
                if (succeed(chunk.callback))
                    return;
            }

            int length = 0;
            Chunk chunk = null;
            while (output.isReady())
            {
                if (chunk != null)
                {
                    if (_log.isDebugEnabled())
                        _log.debug("{} async write complete of {} ({} bytes) on {}", getRequestId(clientRequest), chunk, length, output);
                    if (succeed(chunk.callback))
                        return;
                }

                this.chunk = chunk = chunks.poll();
                if (chunk == null)
                    return;

                length = chunk.buffer.remaining();
                if (length > 0)
                    writeProxyResponseContent(output, chunk.buffer);
            }

            if (_log.isDebugEnabled())
                _log.debug("{} async write pending of {} ({} bytes) on {}", getRequestId(clientRequest), chunk, length, output);
            writePending = true;
        }

        private boolean succeed(Callback callback)
        {
            // Succeeding the callback may cause to reenter in onWritePossible()
            // because typically the callback is the one that controls whether the
            // content received from the server has been consumed, so succeeding
            // the callback causes more content to be received from the server,
            // and hence more to be written to the client by onWritePossible().
            // A reentrant call to onWritePossible() performs another write,
            // which may remain pending, which means that the reentrant call
            // to onWritePossible() returns all the way back to just after the
            // succeed of the callback. There, we cannot just loop attempting
            // write, but we need to check whether we are write pending.
            callback.succeeded();
            return writePending;
        }

        @Override
        public void onError(Throwable failure)
        {
            Chunk chunk = this.chunk;
            if (chunk != null)
                chunk.callback.failed(failure);
            else
                serverResponse.abort(failure);
        }
    }

    /**
     * <p>Allows applications to transform upstream and downstream content.</p>
     * <p>Typical use cases of transformations are URL rewriting of HTML anchors
     * (where the value of the {@code href} attribute of &lt;a&gt; elements
     * is modified by the proxy), field renaming of JSON documents, etc.</p>
     * <p>Applications should override {@link #newClientRequestContentTransformer(HttpServletRequest, Request)}
     * and/or {@link #newServerResponseContentTransformer(HttpServletRequest, HttpServletResponse, Response)}
     * to provide the transformer implementation.</p>
     */
    public interface ContentTransformer
    {
        /**
         * The identity transformer that does not perform any transformation.
         */
        public static final ContentTransformer IDENTITY = new IdentityContentTransformer();

        /**
         * <p>Transforms the given input byte buffers into (possibly multiple) byte buffers.</p>
         * <p>The transformation must happen synchronously in the context of a call
         * to this method (it is not supported to perform the transformation in another
         * thread spawned during the call to this method).
         * The transformation may happen or not, depending on the transformer implementation.
         * For example, a buffering transformer may buffer the input aside, and only
         * perform the transformation when the whole input is provided (by looking at the
         * {@code finished} flag).</p>
         * <p>The input buffer will be cleared and reused after the call to this method.
         * Implementations that want to buffer aside the input (or part of it) must copy
         * the input bytes that they want to buffer.</p>
         * <p>Typical implementations:</p>
         * <pre>
         * // Identity transformation (no transformation, the input is copied to the output)
         * public void transform(ByteBuffer input, boolean finished, List&lt;ByteBuffer&gt; output)
         * {
         *     output.add(input);
         * }
         *
         * // Discard transformation (all input is discarded)
         * public void transform(ByteBuffer input, boolean finished, List&lt;ByteBuffer&gt; output)
         * {
         *     // Empty
         * }
         *
         * // Buffering identity transformation (all input is buffered aside until it is finished)
         * public void transform(ByteBuffer input, boolean finished, List&lt;ByteBuffer&gt; output)
         * {
         *     ByteBuffer copy = ByteBuffer.allocate(input.remaining());
         *     copy.put(input).flip();
         *     store(copy);
         *
         *     if (finished)
         *     {
         *         List&lt;ByteBuffer&gt; copies = retrieve();
         *         output.addAll(copies);
         *     }
         * }
         * </pre>
         *
         * @param input the input content to transform (may be of length zero)
         * @param finished whether the input content is finished or more will come
         * @param output where to put the transformed output content
         * @throws IOException in case of transformation failures
         */
        public void transform(ByteBuffer input, boolean finished, List<ByteBuffer> output) throws IOException;
    }

    private static class IdentityContentTransformer implements ContentTransformer
    {
        @Override
        public void transform(ByteBuffer input, boolean finished, List<ByteBuffer> output)
        {
            output.add(input);
        }
    }

    public static class GZIPContentTransformer implements ContentTransformer
    {
        private static final Logger logger = LoggerFactory.getLogger(GZIPContentTransformer.class);

        private final List<ByteBuffer> buffers = new ArrayList<>(2);
        private final ContentTransformer transformer;
        private final ContentDecoder decoder;
        private final ByteArrayOutputStream out;
        private final GZIPOutputStream gzipOut;

        public GZIPContentTransformer(ContentTransformer transformer)
        {
            this(null, transformer);
        }

        public GZIPContentTransformer(HttpClient httpClient, ContentTransformer transformer)
        {
            try
            {
                this.transformer = transformer;
                ByteBufferPool byteBufferPool = httpClient == null ? null : httpClient.getByteBufferPool();
                this.decoder = new GZIPContentDecoder(byteBufferPool, GZIPContentDecoder.DEFAULT_BUFFER_SIZE);
                this.out = new ByteArrayOutputStream();
                this.gzipOut = new GZIPOutputStream(out);
            }
            catch (IOException x)
            {
                throw new RuntimeIOException(x);
            }
        }

        @Override
        public void transform(ByteBuffer input, boolean finished, List<ByteBuffer> output) throws IOException
        {
            if (logger.isDebugEnabled())
                logger.debug("Ungzipping {} bytes, finished={}", input.remaining(), finished);

            List<ByteBuffer> decodeds = Collections.emptyList();
            if (!input.hasRemaining())
            {
                if (finished)
                    transformer.transform(input, true, buffers);
            }
            else
            {
                decodeds = new ArrayList<>();
                while (true)
                {
                    ByteBuffer decoded = decoder.decode(input);
                    decodeds.add(decoded);
                    boolean decodeComplete = !input.hasRemaining() && !decoded.hasRemaining();
                    boolean complete = finished && decodeComplete;
                    if (logger.isDebugEnabled())
                        logger.debug("Ungzipped {} bytes, complete={}", decoded.remaining(), complete);
                    if (decoded.hasRemaining() || complete)
                        transformer.transform(decoded, complete, buffers);
                    if (decodeComplete)
                        break;
                }
            }

            if (!buffers.isEmpty() || finished)
            {
                ByteBuffer result = gzip(buffers, finished);
                buffers.clear();
                output.add(result);
            }

            decodeds.forEach(decoder::release);
        }

        private ByteBuffer gzip(List<ByteBuffer> buffers, boolean finished) throws IOException
        {
            for (ByteBuffer buffer : buffers)
            {
                write(gzipOut, buffer);
            }
            if (finished)
                gzipOut.close();
            byte[] gzipBytes = out.toByteArray();
            out.reset();
            return ByteBuffer.wrap(gzipBytes);
        }
    }

    private class ProxyAsyncRequestContent extends AsyncRequestContent
    {
        private final HttpServletRequest clientRequest;

        private ProxyAsyncRequestContent(HttpServletRequest clientRequest)
        {
            this.clientRequest = clientRequest;
        }

        @Override
        public boolean offer(ByteBuffer buffer, Callback callback)
        {
            if (_log.isDebugEnabled())
                _log.debug("{} proxying content to upstream: {} bytes", getRequestId(clientRequest), buffer.remaining());
            return super.offer(buffer, callback);
        }
    }

    private static class Chunk
    {
        private final ByteBuffer buffer;
        private final Callback callback;

        private Chunk(ByteBuffer buffer, Callback callback)
        {
            this.buffer = Objects.requireNonNull(buffer);
            this.callback = Objects.requireNonNull(callback);
        }
    }
}
