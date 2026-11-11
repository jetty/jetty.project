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

package org.eclipse.jetty.ee9.proxy;

import java.io.IOException;
import java.nio.channels.WritePendingException;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.server.handler.ConnectHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.buffer.ArrayByteBufferPool;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

/**
 * <p>Servlet 3.1 asynchronous proxy servlet.</p>
 * <p>Both the request processing and the I/O are asynchronous.</p>
 *
 * @see ProxyServlet
 * @see AsyncMiddleManServlet
 * @see ConnectHandler
 */
public class AsyncProxyServlet extends ProxyServlet
{
    private static final String WRITE_LISTENER_ATTRIBUTE = AsyncProxyServlet.class.getName() + ".writeListener";

    private ByteBufferPool.Sized bufferPool;

    @Override
    public void init() throws ServletException
    {
        super.init();
        // A small pool to read from the ServletInputStream.
        int capacity = getHttpClient().getRequestBufferSize();
        bufferPool = new ByteBufferPool.Sized(new ArrayByteBufferPool(new int[]{capacity}, 16L * capacity, -1), false, capacity);
    }

    @Override
    protected Request.Content proxyRequestContent(HttpServletRequest request, HttpServletResponse response, Request proxyRequest) throws IOException
    {
        AsyncRequestContent content = new AsyncRequestContent();
        request.getInputStream().setReadListener(newReadListener(request, response, proxyRequest, content));
        return content;
    }

    protected ReadListener newReadListener(HttpServletRequest request, HttpServletResponse response, Request proxyRequest, AsyncRequestContent content)
    {
        return new StreamReader(request, response, proxyRequest, content);
    }

    @Override
    protected void onResponseContent(HttpServletRequest request, HttpServletResponse response, Response proxyResponse, byte[] buffer, int offset, int length, Callback callback)
    {
        try
        {
            if (_log.isDebugEnabled())
                _log.debug("{} proxying content to downstream: {} bytes", getRequestId(request), length);
            StreamWriter writeListener = (StreamWriter)request.getAttribute(WRITE_LISTENER_ATTRIBUTE);
            if (writeListener == null)
            {
                writeListener = newWriteListener(request, proxyResponse);
                request.setAttribute(WRITE_LISTENER_ATTRIBUTE, writeListener);

                // Set the data to write before calling setWriteListener(), because
                // setWriteListener() may trigger the call to onWritePossible() on
                // a different thread and we would have a race.
                writeListener.data(buffer, offset, length, callback);

                // Setting the WriteListener triggers an invocation to onWritePossible().
                response.getOutputStream().setWriteListener(writeListener);
            }
            else
            {
                writeListener.data(buffer, offset, length, callback);
                writeListener.onWritePossible();
            }
        }
        catch (Throwable x)
        {
            callback.failed(x);
            proxyResponse.abort(x);
        }
    }

    protected StreamWriter newWriteListener(HttpServletRequest request, Response proxyResponse)
    {
        return new StreamWriter(request, proxyResponse);
    }

    /**
     * <p>Convenience extension of {@link AsyncProxyServlet} that offers transparent proxy functionalities.</p>
     *
     * @see AbstractProxyServlet.TransparentDelegate
     */
    public static class Transparent extends AsyncProxyServlet
    {
        private final TransparentDelegate delegate = new TransparentDelegate(this);

        @Override
        public void init(ServletConfig config) throws ServletException
        {
            super.init(config);
            delegate.init(config);
        }

        @Override
        protected String rewriteTarget(HttpServletRequest clientRequest)
        {
            return delegate.rewriteTarget(clientRequest);
        }
    }

    protected class StreamReader extends IteratingCallback implements ReadListener
    {
        private final HttpServletRequest request;
        private final Request proxyRequest;
        private final AsyncRequestContent content;

        protected StreamReader(HttpServletRequest request, HttpServletResponse response, Request proxyRequest, AsyncRequestContent content)
        {
            this.request = request;
            this.proxyRequest = proxyRequest;
            this.content = content;
        }

        @Override
        public void onDataAvailable()
        {
            iterate();
        }

        @Override
        public void onAllDataRead()
        {
            if (_log.isDebugEnabled())
                _log.debug("{} proxying content to upstream completed", getRequestId(request));
            content.close();
        }

        @Override
        public void onError(Throwable t)
        {
            content.fail(t);
        }

        @Override
        protected Action process() throws Exception
        {
            int requestId = _log.isDebugEnabled() ? getRequestId(request) : 0;
            ServletInputStream input = request.getInputStream();

            try (RetainableByteBuffer.Mutable buffer = bufferPool.acquire())
            {
                while (input.isReady())
                {
                    int read = (int)buffer.write(b ->
                    {
                        int p = b.position();
                        int r = input.read(b.array(), b.arrayOffset() + p, b.remaining());
                        if (r > 0)
                            b.position(p + r);
                        return r;
                    });
                    if (_log.isDebugEnabled())
                        _log.debug("{} asynchronous read {} bytes on {}", requestId, read, input);
                    if (read > 0)
                    {
                        if (_log.isDebugEnabled())
                            _log.debug("{} proxying content to upstream: {} bytes", requestId, read);
                        onRequestContent(request, proxyRequest, content, buffer, this);
                        return Action.SCHEDULED;
                    }
                    else if (read < 0)
                    {
                        if (_log.isDebugEnabled())
                            _log.debug("{} asynchronous read complete on {}", requestId, input);
                        return Action.SUCCEEDED;
                    }
                }
            }

            if (_log.isDebugEnabled())
                _log.debug("{} asynchronous read pending on {}", requestId, input);
            return Action.IDLE;
        }

        protected void onRequestContent(HttpServletRequest request, Request proxyRequest, AsyncRequestContent content, RetainableByteBuffer buffer, Callback callback)
        {
            content.offer(false, buffer, callback);
        }

        @Override
        protected void onFailure(Throwable cause)
        {
            onError(cause);
        }

        @Override
        public InvocationType getInvocationType()
        {
            // Reads and writes to AsyncRequestContent are non-blocking.
            return InvocationType.NON_BLOCKING;
        }
    }

    protected class StreamWriter implements WriteListener
    {
        private final HttpServletRequest request;
        private final Response proxyResponse;
        private WriteState state;
        private byte[] buffer;
        private int offset;
        private int length;
        private Callback callback;

        protected StreamWriter(HttpServletRequest request, Response proxyResponse)
        {
            this.request = request;
            this.proxyResponse = proxyResponse;
            this.state = WriteState.IDLE;
        }

        protected void data(byte[] bytes, int offset, int length, Callback callback)
        {
            if (state != WriteState.IDLE)
                throw new WritePendingException();
            this.state = WriteState.READY;
            this.buffer = bytes;
            this.offset = offset;
            this.length = length;
            this.callback = callback;
        }

        @Override
        public void onWritePossible() throws IOException
        {
            int requestId = getRequestId(request);
            ServletOutputStream output = request.getAsyncContext().getResponse().getOutputStream();
            if (state == WriteState.READY)
            {
                // There is data to write.
                if (_log.isDebugEnabled())
                    _log.debug("{} asynchronous write start of {} bytes on {}", requestId, length, output);
                output.write(buffer, offset, length);
                state = WriteState.PENDING;
                if (output.isReady())
                {
                    if (_log.isDebugEnabled())
                        _log.debug("{} asynchronous write of {} bytes completed on {}", requestId, length, output);
                    complete();
                }
                else
                {
                    if (_log.isDebugEnabled())
                        _log.debug("{} asynchronous write of {} bytes pending on {}", requestId, length, output);
                }
            }
            else if (state == WriteState.PENDING)
            {
                // The write blocked but is now complete.
                if (_log.isDebugEnabled())
                    _log.debug("{} asynchronous write of {} bytes completing on {}", requestId, length, output);
                complete();
            }
            else
            {
                throw new IllegalStateException();
            }
        }

        protected void complete()
        {
            buffer = null;
            offset = 0;
            length = 0;
            Callback c = callback;
            callback = null;
            state = WriteState.IDLE;
            // Call the callback only after the whole state has been reset,
            // because the callback may trigger a reentrant call and
            // the state must already be the new one that we reset here.
            c.succeeded();
        }

        @Override
        public void onError(Throwable failure)
        {
            proxyResponse.abort(failure);
        }
    }

    private enum WriteState
    {
        READY, PENDING, IDLE
    }
}
