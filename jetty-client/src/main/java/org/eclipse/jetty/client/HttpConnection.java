//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.net.HttpCookie;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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

    private final HttpClient client;
    private final HttpDestination destination;

    protected HttpConnection(HttpClient client, HttpDestination destination)
    {
        this.client = client;
        this.destination = destination;
    }

    public HttpClient getHttpClient()
    {
        return client;
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
            timeoutListener.schedule(client.getScheduler());
            listeners.add(timeoutListener);
        }
        if (listener != null)
            listeners.add(listener);

        HttpConversation conversation = client.getConversation(request.getConversationID(), true);
        HttpExchange exchange = new HttpExchange(conversation, getHttpDestination(), request, listeners);

        send(exchange);
    }

    protected abstract void send(HttpExchange exchange);

    protected void normalizeRequest(Request request)
    {
        if (request.getMethod() == null)
            request.method(HttpMethod.GET);

        if (request.getVersion() == null)
            request.version(HttpVersion.HTTP_1_1);

        if (request.getIdleTimeout() <= 0)
            request.idleTimeout(client.getIdleTimeout(), TimeUnit.MILLISECONDS);

        HttpMethod method = request.getMethod();
        HttpVersion version = request.getVersion();
        HttpFields headers = request.getHeaders();
        ContentProvider content = request.getContent();

        if (request.getAgent() == null)
            headers.put(client.getUserAgentField());

        // Make sure the path is there
        String path = request.getPath();
        if (path.trim().length() == 0)
        {
            path = "/";
            request.path(path);
        }
        if (destination.isProxied() && HttpMethod.CONNECT != method)
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

        // Add content headers
        if (content != null)
        {
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
        List<HttpCookie> cookies = client.getCookieStore().get(request.getURI());
        StringBuilder cookieString = null;
        for (int i = 0; i < cookies.size(); ++i)
        {
            if (cookieString == null)
                cookieString = new StringBuilder();
            if (i > 0)
                cookieString.append("; ");
            HttpCookie cookie = cookies.get(i);
            cookieString.append(cookie.getName()).append("=").append(cookie.getValue());
        }
        if (cookieString != null)
            request.header(HttpHeader.COOKIE.asString(), cookieString.toString());

        // Authorization
        URI authenticationURI = destination.isProxied() ? destination.getProxyURI() : request.getURI();
        Authentication.Result authnResult = client.getAuthenticationStore().findAuthenticationResult(authenticationURI);
        if (authnResult != null)
            authnResult.apply(request);

        if (!headers.containsKey(HttpHeader.ACCEPT_ENCODING.asString()))
        {
            HttpField acceptEncodingField = client.getAcceptEncodingField();
            if (acceptEncodingField != null)
                headers.put(acceptEncodingField);
        }
    }
}
