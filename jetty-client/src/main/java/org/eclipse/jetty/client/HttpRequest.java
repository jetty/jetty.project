//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.BlockingResponseListener;
import org.eclipse.jetty.client.util.PathContentProvider;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.util.Fields;

public class HttpRequest implements Request
{
    private static final AtomicLong ids = new AtomicLong();

    private final HttpFields headers = new HttpFields();
    private final Fields params = new Fields();
    private final Map<String, Object> attributes = new HashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final HttpClient client;
    private final long conversation;
    private final String host;
    private final int port;
    private String scheme;
    private String path;
    private HttpMethod method;
    private HttpVersion version;
    private long idleTimeout;
    private ContentProvider content;
    private boolean followRedirects;
    private volatile boolean aborted;

    public HttpRequest(HttpClient client, URI uri)
    {
        this(client, ids.incrementAndGet(), uri);
    }

    protected HttpRequest(HttpClient client, long conversation, URI uri)
    {
        this.client = client;
        this.conversation = conversation;
        scheme(uri.getScheme());
        host = uri.getHost();
        port = uri.getPort();
        path(uri.getPath());
        String query = uri.getRawQuery();
        if (query != null)
        {
            for (String nameValue : query.split("&"))
            {
                String[] parts = nameValue.split("=");
                param(parts[0], parts.length < 2 ? "" : urlDecode(parts[1]));
            }
        }
        followRedirects(client.isFollowRedirects());
    }

    private String urlDecode(String value)
    {
        String charset = "UTF-8";
        try
        {
            return URLDecoder.decode(value, charset);
        }
        catch (UnsupportedEncodingException x)
        {
            throw new UnsupportedCharsetException(charset);
        }
    }

    @Override
    public long conversation()
    {
        return conversation;
    }

    @Override
    public String scheme()
    {
        return scheme;
    }

    @Override
    public Request scheme(String scheme)
    {
        this.scheme = scheme;
        return this;
    }

    @Override
    public String host()
    {
        return host;
    }

    @Override
    public int port()
    {
        return port;
    }

    @Override
    public HttpMethod method()
    {
        return method;
    }

    @Override
    public Request method(HttpMethod method)
    {
        this.method = method;
        return this;
    }

    @Override
    public String path()
    {
        return path;
    }

    @Override
    public Request path(String path)
    {
        this.path = path;
        return this;
    }

    @Override
    public String uri()
    {
        String scheme = scheme();
        String result = scheme + "://" + host();
        int port = port();
        result += "http".equals(scheme) && port != 80 ? ":" + port : "";
        result += "https".equals(scheme) && port != 443 ? ":" + port : "";
        result += path();
        return result;
    }

    @Override
    public HttpVersion version()
    {
        return version;
    }

    @Override
    public Request version(HttpVersion version)
    {
        this.version = version;
        return this;
    }

    @Override
    public Request param(String name, String value)
    {
        params.add(name, value);
        return this;
    }

    @Override
    public Fields params()
    {
        return params;
    }

    @Override
    public String agent()
    {
        return headers.get(HttpHeader.USER_AGENT);
    }

    @Override
    public Request agent(String agent)
    {
        headers.put(HttpHeader.USER_AGENT, agent);
        return this;
    }

    @Override
    public Request header(String name, String value)
    {
        if (value == null)
            headers.remove(name);
        else
            headers.add(name, value);
        return this;
    }

    @Override
    public Request attribute(String name, Object value)
    {
        attributes.put(name, value);
        return this;
    }

    @Override
    public Map<String, Object> attributes()
    {
        return attributes;
    }

    @Override
    public HttpFields headers()
    {
        return headers;
    }

    @Override
    public List<Listener> listeners()
    {
        return listeners;
    }

    @Override
    public Request listener(Request.Listener listener)
    {
        this.listeners.add(listener);
        return this;
    }

    @Override
    public ContentProvider content()
    {
        return content;
    }

    @Override
    public Request content(ContentProvider content)
    {
        this.content = content;
        return this;
    }

    @Override
    public Request file(Path file) throws IOException
    {
        return file(file, "application/octet-stream");
    }

    @Override
    public Request file(Path file, String contentType) throws IOException
    {
        if (contentType != null)
            header(HttpHeader.CONTENT_TYPE.asString(), contentType);
        return content(new PathContentProvider(file));
    }

//    @Override
//    public Request decoder(ContentDecoder decoder)
//    {
//        return this;
//    }

    @Override
    public boolean followRedirects()
    {
        return followRedirects;
    }

    @Override
    public Request followRedirects(boolean follow)
    {
        this.followRedirects = follow;
        return this;
    }

    @Override
    public long idleTimeout()
    {
        return idleTimeout;
    }

    @Override
    public Request idleTimeout(long timeout)
    {
        this.idleTimeout = timeout;
        return this;
    }

    @Override
    public Future<ContentResponse> send()
    {
        BlockingResponseListener listener = new BlockingResponseListener(this);
        send(listener);
        return listener;
    }

    @Override
    public void send(final Response.Listener listener)
    {
        send(0, TimeUnit.SECONDS, listener);
    }

    @Override
    public void send(long timeout, TimeUnit unit, Response.Listener listener)
    {
        client.send(this, timeout, unit, listener);
    }

    @Override
    public boolean abort(String reason)
    {
        aborted = true;
        if (client.provideDestination(scheme(), host(), port()).abort(this, reason))
            return true;
        HttpConversation conversation = client.getConversation(conversation());
        return conversation.abort(reason);
    }

    @Override
    public boolean aborted()
    {
        return aborted;
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s %s %s]@%x", HttpRequest.class.getSimpleName(), method(), path(), version(), hashCode());
    }
}
