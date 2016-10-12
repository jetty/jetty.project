//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.servlet.AsyncContext;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.AsyncContentProvider;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.client.util.InputStreamContentProvider;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;

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
    protected void service(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
    {
        final int requestId = getRequestId(request);

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

        final Request proxyRequest = getHttpClient().newRequest(rewrittenTarget)
                .method(request.getMethod())
                .version(HttpVersion.fromString(request.getProtocol()));

        copyRequestHeaders(request, proxyRequest);

        addProxyHeaders(request, proxyRequest);

        final AsyncContext asyncContext = request.startAsync();
        // We do not timeout the continuation, but the proxy request
        asyncContext.setTimeout(0);
        proxyRequest.timeout(getTimeout(), TimeUnit.MILLISECONDS);

        if (hasContent(request))
        {
            if (expects100Continue(request))
            {
                DeferredContentProvider deferred = new DeferredContentProvider();
                proxyRequest.content(deferred);
                proxyRequest.attribute(CLIENT_REQUEST_ATTRIBUTE, request);
                proxyRequest.attribute(CONTINUE_ACTION_ATTRIBUTE, (Runnable)() ->
                {
                    try
                    {
                        ContentProvider provider = proxyRequestContent(request, response, proxyRequest);
                        new DelegatingContentProvider(request, proxyRequest, response, provider, deferred).iterate();
                    }
                    catch (Throwable failure)
                    {
                        onClientRequestFailure(request, proxyRequest, response, failure);
                    }
                });
            }
            else
            {
                proxyRequest.content(proxyRequestContent(request, response, proxyRequest));
            }
        }

        sendProxyRequest(request, response, proxyRequest);
    }

    protected ContentProvider proxyRequestContent(HttpServletRequest request, HttpServletResponse response, Request proxyRequest) throws IOException
    {
        return new ProxyInputStreamContentProvider(request, response, proxyRequest, request.getInputStream());
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
     * @see org.eclipse.jetty.proxy.AbstractProxyServlet.TransparentDelegate
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

            onResponseContent(request, response, proxyResponse, buffer, offset, length, new Callback.Nested(callback)
            {
                @Override
                public void failed(Throwable x)
                {
                    super.failed(x);
                    proxyResponse.abort(x);
                }
            });
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

    protected class ProxyInputStreamContentProvider extends InputStreamContentProvider
    {
        private final HttpServletResponse response;
        private final Request proxyRequest;
        private final HttpServletRequest request;

        protected ProxyInputStreamContentProvider(HttpServletRequest request, HttpServletResponse response, Request proxyRequest, InputStream input)
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
        protected ByteBuffer onRead(byte[] buffer, int offset, int length)
        {
            if (_log.isDebugEnabled())
                _log.debug("{} proxying content to upstream: {} bytes", getRequestId(request), length);
            return onRequestContent(request, proxyRequest, buffer, offset, length);
        }

        protected ByteBuffer onRequestContent(HttpServletRequest request, Request proxyRequest, byte[] buffer, int offset, int length)
        {
            return super.onRead(buffer, offset, length);
        }

        @Override
        protected void onReadFailure(Throwable failure)
        {
            onClientRequestFailure(request, proxyRequest, response, failure);
        }
    }

    private class DelegatingContentProvider extends IteratingCallback implements AsyncContentProvider.Listener
    {
        private final HttpServletRequest clientRequest;
        private final Request proxyRequest;
        private final HttpServletResponse proxyResponse;
        private final Iterator<ByteBuffer> iterator;
        private final DeferredContentProvider deferred;

        private DelegatingContentProvider(HttpServletRequest clientRequest, Request proxyRequest, HttpServletResponse proxyResponse, ContentProvider provider, DeferredContentProvider deferred)
        {
            this.clientRequest = clientRequest;
            this.proxyRequest = proxyRequest;
            this.proxyResponse = proxyResponse;
            this.iterator = provider.iterator();
            this.deferred = deferred;
            if (provider instanceof AsyncContentProvider)
                ((AsyncContentProvider)provider).setListener(this);
        }

        @Override
        protected Action process() throws Exception
        {
            if (!iterator.hasNext())
                return Action.SUCCEEDED;

            ByteBuffer buffer = iterator.next();
            if (buffer == null)
                return Action.IDLE;

            deferred.offer(buffer, this);
            return Action.SCHEDULED;
        }

        @Override
        public void succeeded()
        {
            if (iterator instanceof Callback)
                ((Callback)iterator).succeeded();
            super.succeeded();
        }

        @Override
        protected void onCompleteSuccess()
        {
            try
            {
                if (iterator instanceof Closeable)
                    ((Closeable)iterator).close();
                deferred.close();
            }
            catch (Throwable x)
            {
                _log.ignore(x);
            }
        }

        @Override
        protected void onCompleteFailure(Throwable failure)
        {
            if (iterator instanceof Callback)
                ((Callback)iterator).failed(failure);
            onClientRequestFailure(clientRequest, proxyRequest, proxyResponse, failure);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }

        @Override
        public void onContent()
        {
            iterate();
        }
    }
}
