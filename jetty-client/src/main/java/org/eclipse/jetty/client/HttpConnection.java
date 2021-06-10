//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.util.Attachable;
import org.eclipse.jetty.util.HttpCookieStore;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;

public abstract class HttpConnection implements Connection, Attachable
{
    private static final Logger LOG = Log.getLogger(HttpConnection.class);

    private final HttpDestination destination;
    private final RequestTimeouts requestTimeouts;
    private Object attachment;
    private int idleTimeoutGuard;
    private long idleTimeoutStamp;

    protected HttpConnection(HttpDestination destination)
    {
        this.destination = destination;
        this.requestTimeouts = new RequestTimeouts(destination.getHttpClient().getScheduler());
        this.idleTimeoutStamp = System.nanoTime();
    }

    public HttpClient getHttpClient()
    {
        return destination.getHttpClient();
    }

    public HttpDestination getHttpDestination()
    {
        return destination;
    }

    protected abstract Iterator<HttpChannel> getHttpChannels();

    @Override
    public void send(Request request, Response.CompleteListener listener)
    {
        HttpRequest httpRequest = (HttpRequest)request;

        ArrayList<Response.ResponseListener> listeners = new ArrayList<>(httpRequest.getResponseListeners());

        httpRequest.sent();
        if (listener != null)
            listeners.add(listener);

        HttpExchange exchange = new HttpExchange(getHttpDestination(), httpRequest, listeners);

        SendFailure result = send(exchange);
        if (result != null)
            httpRequest.abort(result.failure);
    }

    protected abstract SendFailure send(HttpExchange exchange);

    protected void normalizeRequest(Request request)
    {
        boolean normalized = ((HttpRequest)request).normalized();
        if (LOG.isDebugEnabled())
            LOG.debug("Normalizing {} {}", !normalized, request);
        if (normalized)
            return;

        HttpVersion version = request.getVersion();
        HttpFields headers = request.getHeaders();
        ContentProvider content = request.getContent();
        ProxyConfiguration.Proxy proxy = destination.getProxy();

        // Make sure the path is there
        String path = request.getPath();
        if (path.trim().length() == 0)
        {
            path = "/";
            request.path(path);
        }

        if (proxy instanceof HttpProxy && !HttpClient.isSchemeSecure(request.getScheme()))
        {
            URI uri = request.getURI();
            if (uri != null)
            {
                path = uri.toString();
                request.path(path);
            }
        }

        // If we are HTTP 1.1, add the Host header
        if (version.getVersion() <= 11)
        {
            if (!headers.containsKey(HttpHeader.HOST.asString()))
            {
                URI uri = request.getURI();
                if (uri != null)
                    headers.put(HttpHeader.HOST, uri.getAuthority());
                else
                    headers.put(getHttpDestination().getHostField());
            }
        }

        // Add content headers
        if (content != null)
        {
            if (!headers.containsKey(HttpHeader.CONTENT_TYPE.asString()))
            {
                String contentType = null;
                if (content instanceof ContentProvider.Typed)
                    contentType = ((ContentProvider.Typed)content).getContentType();
                if (contentType != null)
                {
                    headers.put(HttpHeader.CONTENT_TYPE, contentType);
                }
                else
                {
                    contentType = getHttpClient().getDefaultRequestContentType();
                    if (contentType != null)
                        headers.put(HttpHeader.CONTENT_TYPE, contentType);
                }
            }
            long contentLength = content.getLength();
            if (contentLength >= 0)
            {
                if (!headers.containsKey(HttpHeader.CONTENT_LENGTH.asString()))
                    headers.put(HttpHeader.CONTENT_LENGTH, String.valueOf(contentLength));
            }
        }

        // Cookies
        StringBuilder cookies = convertCookies(request.getCookies(), null);
        CookieStore cookieStore = getHttpClient().getCookieStore();
        if (cookieStore != null && cookieStore.getClass() != HttpCookieStore.Empty.class)
        {
            URI uri = request.getURI();
            if (uri != null)
                cookies = convertCookies(HttpCookieStore.matchPath(uri, cookieStore.get(uri)), cookies);
        }
        if (cookies != null)
            request.header(HttpHeader.COOKIE.asString(), cookies.toString());

        // Authentication
        applyProxyAuthentication(request, proxy);
        applyRequestAuthentication(request);
    }

    private StringBuilder convertCookies(List<HttpCookie> cookies, StringBuilder builder)
    {
        for (HttpCookie cookie : cookies)
        {
            if (builder == null)
                builder = new StringBuilder();
            if (builder.length() > 0)
                builder.append("; ");
            builder.append(cookie.getName()).append("=").append(cookie.getValue());
        }
        return builder;
    }

    private void applyRequestAuthentication(Request request)
    {
        AuthenticationStore authenticationStore = getHttpClient().getAuthenticationStore();
        if (authenticationStore.hasAuthenticationResults())
        {
            URI uri = request.getURI();
            if (uri != null)
            {
                Authentication.Result result = authenticationStore.findAuthenticationResult(uri);
                if (result != null)
                    result.apply(request);
            }
        }
    }

    private void applyProxyAuthentication(Request request, ProxyConfiguration.Proxy proxy)
    {
        if (proxy != null)
        {
            Authentication.Result result = getHttpClient().getAuthenticationStore().findAuthenticationResult(proxy.getURI());
            if (result != null)
                result.apply(request);
        }
    }

    protected SendFailure send(HttpChannel channel, HttpExchange exchange)
    {
        // Forbid idle timeouts for the time window where
        // the request is associated to the channel and sent.
        // Use a counter to support multiplexed requests.
        boolean send;
        synchronized (this)
        {
            send = idleTimeoutGuard >= 0;
            if (send)
                ++idleTimeoutGuard;
        }

        if (send)
        {
            HttpRequest request = exchange.getRequest();
            SendFailure result;
            if (channel.associate(exchange))
            {
                requestTimeouts.schedule(channel);
                channel.send();
                result = null;
            }
            else
            {
                // Association may fail, for example if the application
                // aborted the request, so we must release the channel.
                channel.release();
                result = new SendFailure(new HttpRequestException("Could not associate request to connection", request), false);
            }

            synchronized (this)
            {
                --idleTimeoutGuard;
                idleTimeoutStamp = System.nanoTime();
            }

            return result;
        }
        else
        {
            // This connection has been timed out by another thread
            // that will take care of removing it from the pool.
            return new SendFailure(new TimeoutException(), true);
        }
    }

    public boolean onIdleTimeout(long idleTimeout)
    {
        synchronized (this)
        {
            if (idleTimeoutGuard == 0)
            {
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - idleTimeoutStamp);
                boolean idle = elapsed > idleTimeout / 2;
                if (idle)
                    idleTimeoutGuard = -1;
                if (LOG.isDebugEnabled())
                    LOG.debug("Idle timeout {}/{}ms - {}", elapsed, idleTimeout, this);
                return idle;
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Idle timeout skipped - {}", this);
                return false;
            }
        }
    }

    @Override
    public void setAttachment(Object obj)
    {
        this.attachment = obj;
    }

    @Override
    public Object getAttachment()
    {
        return attachment;
    }

    protected void destroy()
    {
        requestTimeouts.destroy();
    }

    @Override
    public String toString()
    {
        return String.format("%s@%h", getClass().getSimpleName(), this);
    }

    private class RequestTimeouts extends CyclicTimeouts<HttpChannel>
    {
        private RequestTimeouts(Scheduler scheduler)
        {
            super(scheduler);
        }

        @Override
        protected Iterator<HttpChannel> iterator()
        {
            return getHttpChannels();
        }

        @Override
        protected boolean onExpired(HttpChannel channel)
        {
            HttpExchange exchange = channel.getHttpExchange();
            if (exchange != null)
            {
                HttpRequest request = exchange.getRequest();
                request.abort(new TimeoutException("Total timeout " + request.getConversation().getTimeout() + " ms elapsed"));
            }
            return false;
        }
    }
}
