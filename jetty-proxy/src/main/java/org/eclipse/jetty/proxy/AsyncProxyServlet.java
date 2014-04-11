//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.proxy;

import java.io.IOException;
import java.nio.ByteBuffer;
import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.util.Callback;

public class AsyncProxyServlet extends ProxyServlet
{
    private static final String WRITE_LISTENER_ATTRIBUTE = AsyncProxyServlet.class.getName() + ".writeListener";

    @Override
    protected ContentProvider proxyRequestContent(AsyncContext asyncContext, final int requestId) throws IOException
    {
        ServletInputStream input = asyncContext.getRequest().getInputStream();
        DeferredContentProvider provider = new DeferredContentProvider();
        input.setReadListener(new StreamReader(input, requestId, provider));
        return provider;
    }

    @Override
    protected void onResponseContent(HttpServletRequest request, HttpServletResponse response, Response proxyResponse, byte[] buffer, int offset, int length, Callback callback) throws IOException
    {
        StreamWriter writeListener = (StreamWriter)request.getAttribute(WRITE_LISTENER_ATTRIBUTE);
        if (writeListener == null)
        {
            writeListener = new StreamWriter(request.getAsyncContext());
            request.setAttribute(WRITE_LISTENER_ATTRIBUTE, writeListener);
            response.getOutputStream().setWriteListener(writeListener);
        }
        _log.debug("{} proxying content to downstream: {} bytes", getRequestId(request), length);
        if (writeListener.data(buffer, offset, length, callback))
            writeListener.onWritePossible();
        else
            ;// TODO: fail callback
    }

    private class StreamReader implements ReadListener, Callback
    {
        private final byte[] buffer = new byte[512];
        private final ServletInputStream input;
        private final int requestId;
        private final DeferredContentProvider provider;

        public StreamReader(ServletInputStream input, int requestId, DeferredContentProvider provider)
        {
            this.input = input;
            this.requestId = requestId;
            this.provider = provider;
        }

        @Override
        public void onDataAvailable() throws IOException
        {
            _log.debug("Asynchronous read start from {}", input);

            // First check for isReady() because it has
            // side effects, and then for isFinished().
            while (input.isReady() && !input.isFinished())
            {
                int read = input.read(buffer);
                _log.debug("Asynchronous read {} bytes from {}", read, input);
                if (read > 0)
                {
                    _log.debug("{} proxying content to upstream: {} bytes", requestId, read);
                    provider.offer(ByteBuffer.wrap(buffer, 0, read), this);
                    // Do not call isReady() so that we can apply backpressure.
                    break;
                }
            }
            if (!input.isFinished())
                _log.debug("Asynchronous read pending from {}", input);
        }

        @Override
        public void onAllDataRead() throws IOException
        {
            _log.debug("{} proxying content to upstream completed", requestId);
            provider.close();
        }

        @Override
        public void onError(Throwable x)
        {
            failed(x);
        }

        @Override
        public void succeeded()
        {
            // Notify the container that it may call onDataAvailable() again.
            input.isReady();
        }

        @Override
        public void failed(Throwable x)
        {
            // TODO: send a response error ?
            // complete the async context since we cannot throw an exception from here.
        }
    }

    private class StreamWriter implements WriteListener
    {
        private final AsyncContext asyncContext;
        private byte[] buffer;
        private int offset;
        private int length;
        private volatile Callback callback;

        private StreamWriter(AsyncContext asyncContext)
        {
            this.asyncContext = asyncContext;
        }

        private boolean data(byte[] bytes, int offset, int length, Callback callback)
        {
            if (this.callback != null)
                return false;

            this.buffer = bytes;
            this.offset = offset;
            this.length = length;
            this.callback = callback;
            return true;
        }

        @Override
        public void onWritePossible() throws IOException
        {
            if (callback == null)
            {
                ServletOutputStream output = asyncContext.getResponse().getOutputStream();
                output.write(buffer, offset, length);
                if (output.isReady())
                    complete();
            }
            else
            {
                // If we have a pending callback, it means
                // that the write blocked but is now complete.
                complete();
            }
        }

        private void complete()
        {
            this.buffer = null;
            this.offset = 0;
            this.length = 0;
            Callback callback = this.callback;
            this.callback = null;
            callback.succeeded();
        }

        @Override
        public void onError(Throwable t)
        {
            // TODO:
        }
    }
}
