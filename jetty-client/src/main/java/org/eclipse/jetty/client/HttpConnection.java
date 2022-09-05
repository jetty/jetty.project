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

package org.eclipse.jetty.client;

import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.util.Attachable;
import org.eclipse.jetty.util.HttpCookieStore;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HttpConnection implements IConnection, Attachable
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpConnection.class);

    private final AutoLock lock = new AutoLock();
    private final HttpDestination destination;
    private final RequestTimeouts requestTimeouts;
    private Object attachment;
    private int idleTimeoutGuard;
    private long idleTimeoutNanoTime;

    protected HttpConnection(HttpDestination destination)
    {
        this.destination = destination;
        this.requestTimeouts = new RequestTimeouts(destination.getHttpClient().getScheduler());
        this.idleTimeoutNanoTime = NanoTime.now();
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

    protected SendFailure send(HttpChannel channel, HttpExchange exchange)
    {
        // Forbid idle timeouts for the time window where
        // the request is associated to the channel and sent.
        // Use a counter to support multiplexed requests.
        boolean send;
        try (AutoLock l = lock.lock())
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

            try (AutoLock l = lock.lock())
            {
                --idleTimeoutGuard;
                idleTimeoutNanoTime = NanoTime.now();
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

    protected void normalizeRequest(HttpRequest request)
    {
        boolean normalized = request.normalized();
        if (LOG.isDebugEnabled())
            LOG.debug("Normalizing {} {}", !normalized, request);
        if (normalized)
            return;

        // Make sure the path is there
        String path = request.getPath();
        if (path.trim().length() == 0)
        {
            path = "/";
            request.path(path);
        }

        ProxyConfiguration.Proxy proxy = destination.getProxy();
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
        HttpVersion version = request.getVersion();
        HttpFields headers = request.getHeaders();
        if (version.getVersion() <= 11)
        {
            if (!headers.contains(HttpHeader.HOST.asString()))
            {
                URI uri = request.getURI();
                if (uri != null)
                    request.addHeader(new HttpField(HttpHeader.HOST, uri.getAuthority()));
                else
                    request.addHeader(getHttpDestination().getHostField());
            }
        }

        // Add content headers
        Request.Content content = request.getBody();
        if (content == null)
        {
            request.body(new BytesRequestContent());
        }
        else
        {
            if (!headers.contains(HttpHeader.CONTENT_TYPE))
            {
                String contentType = content.getContentType();
                if (contentType == null)
                    contentType = getHttpClient().getDefaultRequestContentType();
                if (contentType != null)
                {
                    HttpField field = new HttpField(HttpHeader.CONTENT_TYPE, contentType);
                    request.addHeader(field);
                }
            }
            long contentLength = content.getLength();
            if (contentLength >= 0)
            {
                if (!headers.contains(HttpHeader.CONTENT_LENGTH))
                    request.addHeader(new HttpField.LongValueHttpField(HttpHeader.CONTENT_LENGTH, contentLength));
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
        {
            HttpField cookieField = new HttpField(HttpHeader.COOKIE, cookies.toString());
            request.addHeader(cookieField);
        }

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

    public boolean onIdleTimeout(long idleTimeout, Throwable failure)
    {
        try (AutoLock l = lock.lock())
        {
            if (idleTimeoutGuard == 0)
            {
                long elapsed = NanoTime.millisSince(idleTimeoutNanoTime);
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
