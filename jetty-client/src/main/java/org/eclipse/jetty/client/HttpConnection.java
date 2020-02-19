//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client;

import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.util.HttpCookieStore;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public abstract class HttpConnection implements IConnection
{
    private static final Logger LOG = Log.getLogger(HttpConnection.class);

    private final HttpDestination destination;
    private int idleTimeoutGuard;
    private long idleTimeoutStamp;

    protected HttpConnection(HttpDestination destination)
    {
        this.destination = destination;
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
                channel.send();
                result = null;
            }
            else
            {
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
            return new SendFailure(new TimeoutException(), true);
        }
    }

    protected void normalizeRequest(Request request)
    {
        boolean normalized = ((HttpRequest)request).normalized();
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

        URI uri = request.getURI();

        ProxyConfiguration.Proxy proxy = destination.getProxy();
        if (proxy instanceof HttpProxy && !HttpClient.isSchemeSecure(request.getScheme()) && uri != null)
        {
            path = uri.toString();
            request.path(path);
        }

        // If we are HTTP 1.1, add the Host header
        HttpVersion version = request.getVersion();
        HttpFields headers = request.getHeaders();
        if (version.getVersion() <= 11)
        {
            if (!headers.containsKey(HttpHeader.HOST.asString()))
                headers.put(getHttpDestination().getHostField());
        }

        // Add content headers
        Request.Content content = request.getBody();
        if (content == null)
        {
            request.body(new BytesRequestContent());
        }
        else
        {
            if (!headers.containsKey(HttpHeader.CONTENT_TYPE.asString()))
            {
                String contentType = content.getContentType();
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
        CookieStore cookieStore = getHttpClient().getCookieStore();
        if (cookieStore != null)
        {
            StringBuilder cookies = null;
            if (uri != null)
                cookies = convertCookies(HttpCookieStore.matchPath(uri, cookieStore.get(uri)), null);
            cookies = convertCookies(request.getCookies(), cookies);
            if (cookies != null)
                request.header(HttpHeader.COOKIE.asString(), cookies.toString());
        }

        // Authentication
        applyAuthentication(request, proxy != null ? proxy.getURI() : null);
        applyAuthentication(request, uri);
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

    private void applyAuthentication(Request request, URI uri)
    {
        if (uri != null)
        {
            Authentication.Result result = getHttpClient().getAuthenticationStore().findAuthenticationResult(uri);
            if (result != null)
                result.apply(request);
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
    public String toString()
    {
        return String.format("%s@%h", getClass().getSimpleName(), this);
    }
}
