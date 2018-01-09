//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import javax.servlet.AsyncContext;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.InputStreamContentProvider;
import org.eclipse.jetty.http.HttpVersion;
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
    @Override
    protected void service(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
    {
        final int requestId = getRequestId(request);

        URI rewrittenURI = rewriteURI(request);

        if (_log.isDebugEnabled())
        {
            StringBuffer uri = request.getRequestURL();
            if (request.getQueryString() != null)
                uri.append("?").append(request.getQueryString());
            if (_log.isDebugEnabled())
                _log.debug("{} rewriting: {} -> {}", requestId, uri, rewrittenURI);
        }

        if (rewrittenURI == null)
        {
            onRewriteFailed(request, response);
            return;
        }

        final Request proxyRequest = getHttpClient().newRequest(rewrittenURI)
                .method(request.getMethod())
                .version(HttpVersion.fromString(request.getProtocol()));

        copyHeaders(request, proxyRequest);

        addProxyHeaders(request, proxyRequest);

        final AsyncContext asyncContext = request.startAsync();
        // We do not timeout the continuation, but the proxy request
        asyncContext.setTimeout(0);
        proxyRequest.timeout(getTimeout(), TimeUnit.MILLISECONDS);

        if (hasContent(request))
            proxyRequest.content(proxyRequestContent(proxyRequest, request));

        customizeProxyRequest(proxyRequest, request);

        sendProxyRequest(request, response, proxyRequest);
    }

    /**
     * @deprecated use {@link #copyRequestHeaders(HttpServletRequest, Request)} instead
     */
    @Deprecated
    protected void copyHeaders(HttpServletRequest clientRequest, Request proxyRequest)
    {
        copyRequestHeaders(clientRequest, proxyRequest);
    }

    protected ContentProvider proxyRequestContent(final Request proxyRequest, final HttpServletRequest request) throws IOException
    {
        return new ProxyInputStreamContentProvider(proxyRequest, request, request.getInputStream());
    }

    protected Response.Listener newProxyResponseListener(HttpServletRequest request, HttpServletResponse response)
    {
        return new ProxyResponseListener(request, response);
    }

    protected void onClientRequestFailure(Request proxyRequest, HttpServletRequest request, Throwable failure)
    {
        if (_log.isDebugEnabled())
            _log.debug(getRequestId(request) + " client request failure", failure);
        proxyRequest.abort(failure);
    }

    /**
     * @deprecated use {@link #onProxyRewriteFailed(HttpServletRequest, HttpServletResponse)}
     */
    @Deprecated
    protected void onRewriteFailed(HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        onProxyRewriteFailed(request, response);
    }

    /**
     * @deprecated use {@link #onServerResponseHeaders(HttpServletRequest, HttpServletResponse, Response)}
     */
    @Deprecated
    protected void onResponseHeaders(HttpServletRequest request, HttpServletResponse response, Response proxyResponse)
    {
        onServerResponseHeaders(request, response, proxyResponse);
    }

    // TODO: remove in Jetty 9.3, only here for backward compatibility.
    @Override
    protected String filterServerResponseHeader(HttpServletRequest clientRequest, Response serverResponse, String headerName, String headerValue)
    {
        return filterResponseHeader(clientRequest, headerName, headerValue);
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

    /**
     * @deprecated Use {@link #onProxyResponseSuccess(HttpServletRequest, HttpServletResponse, Response)}
     */
    @Deprecated
    protected void onResponseSuccess(HttpServletRequest request, HttpServletResponse response, Response proxyResponse)
    {
        onProxyResponseSuccess(request, response, proxyResponse);
    }

    /**
     * @deprecated Use {@link #onProxyResponseFailure(HttpServletRequest, HttpServletResponse, Response, Throwable)}
     */
    @Deprecated
    protected void onResponseFailure(HttpServletRequest request, HttpServletResponse response, Response proxyResponse, Throwable failure)
    {
        onProxyResponseFailure(request, response, proxyResponse, failure);
    }

    /**
     * @deprecated use {@link #rewriteTarget(HttpServletRequest)}
     */
    @Deprecated
    protected URI rewriteURI(HttpServletRequest request)
    {
        String newTarget = rewriteTarget(request);
        return newTarget == null ? null : URI.create(newTarget);
    }

    /**
     * @deprecated use {@link #sendProxyRequest(HttpServletRequest, HttpServletResponse, Request)}
     */
    @Deprecated
    protected void customizeProxyRequest(Request proxyRequest, HttpServletRequest request)
    {
    }

    /**
     * Extension point for remote server response header filtering.
     * The default implementation returns the header value as is.
     * If null is returned, this header won't be forwarded back to the client.
     *
     * @param headerName the header name
     * @param headerValue the header value
     * @param request the request to proxy
     * @return filteredHeaderValue the new header value
     * @deprecated use {@link #filterServerResponseHeader(HttpServletRequest, Response, String, String)} instead
     */
    @Deprecated
    protected String filterResponseHeader(HttpServletRequest request, String headerName, String headerValue)
    {
        return headerValue;
    }

    /**
     * <p>Convenience extension of {@link ProxyServlet} that offers transparent proxy functionalities.</p>
     *
     * @see TransparentDelegate
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
        protected URI rewriteURI(HttpServletRequest request)
        {
            return URI.create(delegate.rewriteTarget(request));
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
            onResponseHeaders(request, response, proxyResponse);
        }

        @Override
        public void onContent(final Response proxyResponse, ByteBuffer content, final Callback callback)
        {
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

            onResponseContent(request, response, proxyResponse, buffer, offset, length, new Callback()
            {
                @Override
                public void succeeded()
                {
                    callback.succeeded();
                }

                @Override
                public void failed(Throwable x)
                {
                    callback.failed(x);
                    proxyResponse.abort(x);
                }
            });
        }

        @Override
        public void onComplete(Result result)
        {
            if (result.isSucceeded())
                onResponseSuccess(request, response, result.getResponse());
            else
                onResponseFailure(request, response, result.getResponse(), result.getFailure());
            if (_log.isDebugEnabled())
                _log.debug("{} proxying complete", getRequestId(request));
        }
    }

    protected class ProxyInputStreamContentProvider extends InputStreamContentProvider
    {
        private final Request proxyRequest;
        private final HttpServletRequest request;

        protected ProxyInputStreamContentProvider(Request proxyRequest, HttpServletRequest request, InputStream input)
        {
            super(input);
            this.proxyRequest = proxyRequest;
            this.request = request;
        }

        @Override
        public long getLength()
        {
            return request.getContentLength();
        }

        @Override
        protected ByteBuffer onRead(byte[] buffer, int offset, int length)
        {
            if (_log.isDebugEnabled())
                _log.debug("{} proxying content to upstream: {} bytes", getRequestId(request), length);
            return onRequestContent(proxyRequest, request, buffer, offset, length);
        }

        protected ByteBuffer onRequestContent(Request proxyRequest, final HttpServletRequest request, byte[] buffer, int offset, int length)
        {
            return super.onRead(buffer, offset, length);
        }

        @Override
        protected void onReadFailure(Throwable failure)
        {
            onClientRequestFailure(proxyRequest, request, failure);
        }
    }
}
