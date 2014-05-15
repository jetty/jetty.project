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

package org.eclipse.jetty.client;

import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;

public abstract class HttpConnection implements Connection
{
    private static final HttpField CHUNKED_FIELD = new HttpField(HttpHeader.TRANSFER_ENCODING, HttpHeaderValue.CHUNKED);

    private final HttpDestination destination;

    protected HttpConnection(HttpDestination destination)
    {
        this.destination = destination;
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
        ArrayList<Response.ResponseListener> listeners = new ArrayList<>(2);
        if (request.getTimeout() > 0)
        {
            TimeoutCompleteListener timeoutListener = new TimeoutCompleteListener(request);
            timeoutListener.schedule(getHttpClient().getScheduler());
            listeners.add(timeoutListener);
        }
        if (listener != null)
            listeners.add(listener);

        HttpExchange exchange = new HttpExchange(getHttpDestination(), (HttpRequest)request, listeners);

        send(exchange);
    }

    protected abstract void send(HttpExchange exchange);

    protected void normalizeRequest(Request request)
    {
        String method = request.getMethod();
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
        if (proxy != null && !HttpMethod.CONNECT.is(method))
        {
            path = request.getURI().toString();
            request.path(path);
        }

        // If we are HTTP 1.1, add the Host header
        if (version.getVersion() > 10)
        {
            if (!headers.containsKey(HttpHeader.HOST.asString()))
                headers.put(getHttpDestination().getHostField());
        }

        if (request.getAgent() == null)
            headers.put(getHttpClient().getUserAgentField());

        // Add content headers
        if (content != null)
        {
            if (content instanceof ContentProvider.Typed)
            {
                if (!headers.containsKey(HttpHeader.CONTENT_TYPE.asString()))
                {
                    String contentType = ((ContentProvider.Typed)content).getContentType();
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
            else
            {
                if (!headers.containsKey(HttpHeader.TRANSFER_ENCODING.asString()))
                    headers.put(CHUNKED_FIELD);
            }
        }

        // Cookies
        CookieStore cookieStore = getHttpClient().getCookieStore();
        if (cookieStore != null)
        {
            StringBuilder cookies = convertCookies(cookieStore.get(request.getURI()), null);
            cookies = convertCookies(request.getCookies(), cookies);
            if (cookies != null)
                request.header(HttpHeader.COOKIE.asString(), cookies.toString());
        }

        // Authorization
        URI authenticationURI = proxy != null ? proxy.getURI() : request.getURI();
        if (authenticationURI != null)
        {
            Authentication.Result authnResult = getHttpClient().getAuthenticationStore().findAuthenticationResult(authenticationURI);
            if (authnResult != null)
                authnResult.apply(request);
        }
    }

    private StringBuilder convertCookies(List<HttpCookie> cookies, StringBuilder builder)
    {
        for (int i = 0; i < cookies.size(); ++i)
        {
            if (builder == null)
                builder = new StringBuilder();
            if (builder.length() > 0)
                builder.append("; ");
            HttpCookie cookie = cookies.get(i);
            builder.append(cookie.getName()).append("=").append(cookie.getValue());
        }
        return builder;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%h", getClass().getSimpleName(), this);
    }
}
