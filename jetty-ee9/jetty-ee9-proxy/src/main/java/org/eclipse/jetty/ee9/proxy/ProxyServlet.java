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

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.client.util.InputStreamRequestContent;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.handler.ConnectHandler;
import org.eclipse.jetty.util.Callback;

/**
 * <p>Servlet 3.0 asynchronous proxy servlet.</p>
 * <p>The request processing is asynchronous, but the I/O is blocking.</p>
 *
 * @see AsyncProxyServlet
 * @see AsyncMiddleManServlet
 * @see ConnectHandler
 */
public class ProxyServlet extends AbstractProxyServlet
{
    private static final String CONTINUE_ACTION_ATTRIBUTE = ProxyServlet.class.getName() + ".continueAction";

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        int requestId = getRequestId(request);

        String rewrittenTarget = rewriteTarget(request);

        if (_log.isDebugEnabled())
        {
            StringBuffer uri = request.getRequestURL();
            if (request.getQueryString() != null)
                uri.append("?").append(request.getQueryString());
            if (_log.isDebugEnabled())
                _log.debug("{} rewriting: {} -> {}", requestId, uri, rewrittenTarget);
        }

        if (rewrittenTarget == null)
        {
            onProxyRewriteFailed(request, response);
            return;
        }

        Request proxyRequest = newProxyRequest(request, rewrittenTarget);

        copyRequestHeaders(request, proxyRequest);

        addProxyHeaders(request, proxyRequest);

        AsyncContext asyncContext = request.startAsync();
        // We do not timeout the continuation, but the proxy request
        asyncContext.setTimeout(0);
        proxyRequest.timeout(getTimeout(), TimeUnit.MILLISECONDS);

        if (hasContent(request))
        {
            if (expects100Continue(request))
            {
                // Must delay the call to request.getInputStream()
                // that sends the 100 Continue to the client.
                AsyncRequestContent delegate = new AsyncRequestContent();
                proxyRequest.body(delegate);
                proxyRequest.attribute(CONTINUE_ACTION_ATTRIBUTE, (Runnable)() ->
                {
                    try
                    {
                        Request.Content content = proxyRequestContent(request, response, proxyRequest);
                        Content.copy(content, delegate, Callback.from(delegate::close, x -> onClientRequestFailure(request, proxyRequest, response, x)));
                    }
                    catch (Throwable failure)
                    {
                        onClientRequestFailure(request, proxyRequest, response, failure);
                    }
                });
            }
            else
            {
                proxyRequest.body(proxyRequestContent(request, response, proxyRequest));
            }
        }

        sendProxyRequest(request, response, proxyRequest);
    }

    /**
     * Wraps the client-to-proxy request content in a {@code Request.Content} for the proxy-to-server request.
     *
     * @param request the client-to-proxy request
     * @param response the proxy-to-client response
     * @param proxyRequest the proxy-to-server request
     * @return a proxy-to-server request content
     * @throws IOException if the proxy-to-server request content cannot be created
     */
    protected Request.Content proxyRequestContent(HttpServletRequest request, HttpServletResponse response, Request proxyRequest) throws IOException
    {
        return new ProxyInputStreamRequestContent(request, response, proxyRequest, request.getInputStream());
    }

    @Override
    protected Response.Listener newProxyResponseListener(HttpServletRequest request, HttpServletResponse response)
    {
        return new ProxyResponseListener(request, response);
    }

    protected void onResponseContent(HttpServletRequest request, HttpServletResponse response, Response proxyResponse, byte[] buffer, int offset, int length, Callback callback)
    {
        try
        {
            if (_log.isDebugEnabled())
                _log.debug("{} proxying content to downstream: {} bytes", getRequestId(request), length);
            response.getOutputStream().write(buffer, offset, length);
            callback.succeeded();
        }
        catch (Throwable x)
        {
            callback.failed(x);
        }
    }

    @Override
    protected void onContinue(HttpServletRequest clientRequest, Request proxyRequest)
    {
        super.onContinue(clientRequest, proxyRequest);
        Runnable action = (Runnable)proxyRequest.getAttributes().get(CONTINUE_ACTION_ATTRIBUTE);
        Executor executor = getHttpClient().getExecutor();
        executor.execute(action);
    }

    /**
     * <p>Convenience extension of {@link ProxyServlet} that offers transparent proxy functionalities.</p>
     *
     * @see AbstractProxyServlet.TransparentDelegate
     */
    public static class Transparent extends ProxyServlet
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

    protected class ProxyResponseListener extends Response.Listener.Adapter
    {
        private final HttpServletRequest request;
        private final HttpServletResponse response;

        protected ProxyResponseListener(HttpServletRequest request, HttpServletResponse response)
        {
            this.request = request;
            this.response = response;
        }

        @Override
        public void onBegin(Response proxyResponse)
        {
            response.setStatus(proxyResponse.getStatus());
        }

        @Override
        public void onHeaders(Response proxyResponse)
        {
            onServerResponseHeaders(request, response, proxyResponse);
        }

        @Override
        public void onContent(Response proxyResponse, Content.Chunk chunk, Runnable demander)
        {
            ByteBuffer content = chunk.getByteBuffer();
            byte[] buffer;
            int offset;
            int length = content.remaining();
            if (content.hasArray())
            {
                buffer = content.array();
                offset = content.arrayOffset();
            }
            else
            {
                buffer = new byte[length];
                content.get(buffer);
                offset = 0;
            }

            Callback callback = Callback.from(chunk::release, Callback.from(demander, proxyResponse::abort));
            onResponseContent(request, response, proxyResponse, buffer, offset, length, callback);
        }

        @Override
        public void onComplete(Result result)
        {
            if (result.isSucceeded())
                onProxyResponseSuccess(request, response, result.getResponse());
            else
                onProxyResponseFailure(request, response, result.getResponse(), result.getFailure());
            if (_log.isDebugEnabled())
                _log.debug("{} proxying complete", getRequestId(request));
        }
    }

    protected class ProxyInputStreamRequestContent extends InputStreamRequestContent
    {
        private final HttpServletResponse response;
        private final Request proxyRequest;
        private final HttpServletRequest request;

        protected ProxyInputStreamRequestContent(HttpServletRequest request, HttpServletResponse response, Request proxyRequest, InputStream input)
        {
            super(input);
            this.request = request;
            this.response = response;
            this.proxyRequest = proxyRequest;
        }

        @Override
        public long getLength()
        {
            return request.getContentLength();
        }

        @Override
        public Content.Chunk read()
        {
            Content.Chunk chunk = super.read();
            if (chunk instanceof Content.Chunk.Error error)
            {
                onClientRequestFailure(request, proxyRequest, response, error.getCause());
            }
            else
            {
                if (_log.isDebugEnabled())
                    _log.debug("{} proxying content to upstream: {} bytes", getRequestId(request), chunk.remaining());
            }
            return chunk;
        }
    }
}
