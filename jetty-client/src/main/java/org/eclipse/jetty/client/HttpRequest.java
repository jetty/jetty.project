//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import java.net.HttpCookie;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.client.util.PathContentProvider;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;

public class HttpRequest implements Request
{
    private static final URI NULL_URI = URI.create("null:0");

    private final HttpFields headers = new HttpFields();
    private final Fields params = new Fields(true);
    private final List<Response.ResponseListener> responseListeners = new ArrayList<>();
    private final AtomicReference<Throwable> aborted = new AtomicReference<>();
    private final HttpClient client;
    private final HttpConversation conversation;
    private final String host;
    private final int port;
    private URI uri;
    private String scheme;
    private String path;
    private String query;
    private String method = HttpMethod.GET.asString();
    private HttpVersion version = HttpVersion.HTTP_1_1;
    private long idleTimeout;
    private long timeout;
    private long sentTimestampNanos;
    private ContentProvider content;
    private boolean followRedirects;
    private List<HttpCookie> cookies;
    private Map<String, Object> attributes;
    private List<RequestListener> requestListeners;
    private BiFunction<Request, Request, Response.CompleteListener> pushListener;
    private Supplier<HttpFields> trailers;

    protected HttpRequest(HttpClient client, HttpConversation conversation, URI uri)
    {
        this.client = client;
        this.conversation = conversation;
        scheme = uri.getScheme();
        host = client.normalizeHost(uri.getHost());
        port = HttpClient.normalizePort(scheme, uri.getPort());
        path = uri.getRawPath();
        query = uri.getRawQuery();
        extractParams(query);

        followRedirects(client.isFollowRedirects());
        idleTimeout = client.getIdleTimeout();
        HttpField acceptEncodingField = client.getAcceptEncodingField();
        if (acceptEncodingField != null)
            headers.put(acceptEncodingField);
        HttpField userAgentField = client.getUserAgentField();
        if (userAgentField != null)
            headers.put(userAgentField);
    }

    protected HttpConversation getConversation()
    {
        return conversation;
    }

    @Override
    public String getScheme()
    {
        return scheme;
    }

    @Override
    public Request scheme(String scheme)
    {
        this.scheme = scheme;
        this.uri = null;
        return this;
    }

    @Override
    public String getHost()
    {
        return host;
    }

    @Override
    public int getPort()
    {
        return port;
    }

    @Override
    public String getMethod()
    {
        return method;
    }

    @Override
    public Request method(HttpMethod method)
    {
        return method(method.asString());
    }

    @Override
    public Request method(String method)
    {
        this.method = Objects.requireNonNull(method).toUpperCase(Locale.ENGLISH);
        return this;
    }

    @Override
    public String getPath()
    {
        return path;
    }

    @Override
    public Request path(String path)
    {
        URI uri = newURI(path);
        if (uri == null)
        {
            this.path = path;
            this.query = null;
        }
        else
        {
            String rawPath = uri.getRawPath();
            if (rawPath == null)
                rawPath = "";
            this.path = rawPath;
            String query = uri.getRawQuery();
            if (query != null)
            {
                this.query = query;
                params.clear();
                extractParams(query);
            }
            if (uri.isAbsolute())
                this.path = buildURI(false).toString();
        }
        this.uri = null;
        return this;
    }

    @Override
    public String getQuery()
    {
        return query;
    }

    @Override
    public URI getURI()
    {
        if (uri == null)
            uri = buildURI(true);
        return uri == NULL_URI ? null : uri;
    }

    @Override
    public HttpVersion getVersion()
    {
        return version;
    }

    @Override
    public Request version(HttpVersion version)
    {
        this.version = Objects.requireNonNull(version);
        return this;
    }

    @Override
    public Request param(String name, String value)
    {
        return param(name, value, false);
    }

    private Request param(String name, String value, boolean fromQuery)
    {
        params.add(name, value);
        if (!fromQuery)
        {
            // If we have an existing query string, preserve it and append the new parameter.
            if (query != null)
                query += "&" + urlEncode(name) + "=" + urlEncode(value);
            else
                query = buildQuery();
            uri = null;
        }
        return this;
    }

    @Override
    public Fields getParams()
    {
        return new Fields(params, true);
    }

    @Override
    public String getAgent()
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
    public Request accept(String... accepts)
    {
        StringBuilder result = new StringBuilder();
        for (String accept : accepts)
        {
            if (result.length() > 0)
                result.append(", ");
            result.append(accept);
        }
        if (result.length() > 0)
            headers.put(HttpHeader.ACCEPT, result.toString());
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
    public Request header(HttpHeader header, String value)
    {
        if (value == null)
            headers.remove(header);
        else
            headers.add(header, value);
        return this;
    }

    @Override
    public List<HttpCookie> getCookies()
    {
        return cookies != null ? cookies : Collections.<HttpCookie>emptyList();
    }

    @Override
    public Request cookie(HttpCookie cookie)
    {
        if (cookies == null)
            cookies = new ArrayList<>();
        cookies.add(cookie);
        return this;
    }

    @Override
    public Request attribute(String name, Object value)
    {
        if (attributes == null)
            attributes = new HashMap<>(4);
        attributes.put(name, value);
        return this;
    }

    @Override
    public Map<String, Object> getAttributes()
    {
        return attributes != null ? attributes : Collections.<String, Object>emptyMap();
    }

    @Override
    public HttpFields getHeaders()
    {
        return headers;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends RequestListener> List<T> getRequestListeners(Class<T> type)
    {
        // This method is invoked often in a request/response conversation,
        // so we avoid allocation if there is no need to filter.
        if (type == null || requestListeners == null)
            return requestListeners != null ? (List<T>)requestListeners : Collections.<T>emptyList();

        ArrayList<T> result = new ArrayList<>();
        for (RequestListener listener : requestListeners)
            if (type.isInstance(listener))
                result.add((T)listener);
        return result;
    }

    @Override
    public Request listener(Request.Listener listener)
    {
        return requestListener(listener);
    }

    @Override
    public Request onRequestQueued(final QueuedListener listener)
    {
        return requestListener(new QueuedListener()
        {
            @Override
            public void onQueued(Request request)
            {
                listener.onQueued(request);
            }
        });
    }

    @Override
    public Request onRequestBegin(final BeginListener listener)
    {
        return requestListener(new BeginListener()
        {
            @Override
            public void onBegin(Request request)
            {
                listener.onBegin(request);
            }
        });
    }

    @Override
    public Request onRequestHeaders(final HeadersListener listener)
    {
        return requestListener(new HeadersListener()
        {
            @Override
            public void onHeaders(Request request)
            {
                listener.onHeaders(request);
            }
        });
    }

    @Override
    public Request onRequestCommit(final CommitListener listener)
    {
        return requestListener(new CommitListener()
        {
            @Override
            public void onCommit(Request request)
            {
                listener.onCommit(request);
            }
        });
    }

    @Override
    public Request onRequestContent(final ContentListener listener)
    {
        return requestListener(new ContentListener()
        {
            @Override
            public void onContent(Request request, ByteBuffer content)
            {
                listener.onContent(request, content);
            }
        });
    }

    @Override
    public Request onRequestSuccess(final SuccessListener listener)
    {
        return requestListener(new SuccessListener()
        {
            @Override
            public void onSuccess(Request request)
            {
                listener.onSuccess(request);
            }
        });
    }

    @Override
    public Request onRequestFailure(final FailureListener listener)
    {
        return requestListener(new FailureListener()
        {
            @Override
            public void onFailure(Request request, Throwable failure)
            {
                listener.onFailure(request, failure);
            }
        });
    }

    private Request requestListener(RequestListener listener)
    {
        if (requestListeners == null)
            requestListeners = new ArrayList<>();
        requestListeners.add(listener);
        return this;
    }

    @Override
    public Request onResponseBegin(final Response.BeginListener listener)
    {
        this.responseListeners.add(new Response.BeginListener()
        {
            @Override
            public void onBegin(Response response)
            {
                listener.onBegin(response);
            }
        });
        return this;
    }

    @Override
    public Request onResponseHeader(final Response.HeaderListener listener)
    {
        this.responseListeners.add(new Response.HeaderListener()
        {
            @Override
            public boolean onHeader(Response response, HttpField field)
            {
                return listener.onHeader(response, field);
            }
        });
        return this;
    }

    @Override
    public Request onResponseHeaders(final Response.HeadersListener listener)
    {
        this.responseListeners.add(new Response.HeadersListener()
        {
            @Override
            public void onHeaders(Response response)
            {
                listener.onHeaders(response);
            }
        });
        return this;
    }

    @Override
    public Request onResponseContent(final Response.ContentListener listener)
    {
        this.responseListeners.add(new Response.AsyncContentListener()
        {
            @Override
            public void onContent(Response response, ByteBuffer content, Callback callback)
            {
                try
                {
                    listener.onContent(response, content);
                    callback.succeeded();
                }
                catch (Throwable x)
                {
                    callback.failed(x);
                }
            }
        });
        return this;
    }

    @Override
    public Request onResponseContentAsync(final Response.AsyncContentListener listener)
    {
        this.responseListeners.add(new Response.AsyncContentListener()
        {
            @Override
            public void onContent(Response response, ByteBuffer content, Callback callback)
            {
                listener.onContent(response, content, callback);
            }
        });
        return this;
    }

    @Override
    public Request onResponseSuccess(final Response.SuccessListener listener)
    {
        this.responseListeners.add(new Response.SuccessListener()
        {
            @Override
            public void onSuccess(Response response)
            {
                listener.onSuccess(response);
            }
        });
        return this;
    }

    @Override
    public Request onResponseFailure(final Response.FailureListener listener)
    {
        this.responseListeners.add(new Response.FailureListener()
        {
            @Override
            public void onFailure(Response response, Throwable failure)
            {
                listener.onFailure(response, failure);
            }
        });
        return this;
    }

    @Override
    public Request onComplete(final Response.CompleteListener listener)
    {
        this.responseListeners.add(new Response.CompleteListener()
        {
            @Override
            public void onComplete(Result result)
            {
                listener.onComplete(result);
            }
        });
        return this;
    }

    /**
     * <p>Sets a listener for pushed resources.</p>
     * <p>When resources are pushed from the server, the given {@code listener}
     * is invoked for every pushed resource.
     * The parameters to the {@code BiFunction} are this request and the
     * synthesized request for the pushed resource.
     * The {@code BiFunction} should return a {@code CompleteListener} that
     * may also implement other listener interfaces to be notified of various
     * response events, or {@code null} to signal that the pushed resource
     * should be canceled.</p>
     *
     * @param listener a listener for pushed resource events
     * @return this request object
     */
    public Request pushListener(BiFunction<Request, Request, Response.CompleteListener> listener)
    {
        this.pushListener = listener;
        return this;
    }

    public HttpRequest trailers(Supplier<HttpFields> trailers)
    {
        this.trailers = trailers;
        return this;
    }

    @Override
    public ContentProvider getContent()
    {
        return content;
    }

    @Override
    public Request content(ContentProvider content)
    {
        return content(content, null);
    }

    @Override
    public Request content(ContentProvider content, String contentType)
    {
        if (contentType != null)
            header(HttpHeader.CONTENT_TYPE, contentType);
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
        return content(new PathContentProvider(contentType, file));
    }

    @Override
    public boolean isFollowRedirects()
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
    public long getIdleTimeout()
    {
        return idleTimeout;
    }

    @Override
    public Request idleTimeout(long timeout, TimeUnit unit)
    {
        this.idleTimeout = unit.toMillis(timeout);
        return this;
    }

    @Override
    public long getTimeout()
    {
        return timeout;
    }

    @Override
    public Request timeout(long timeout, TimeUnit unit)
    {
        this.timeout = unit.toMillis(timeout);
        return this;
    }

    @Override
    public ContentResponse send() throws InterruptedException, TimeoutException, ExecutionException
    {
        FutureResponseListener listener = new FutureResponseListener(this);
        send(this, listener);

        try
        {
            long timeout = getTimeout();
            if (timeout <= 0)
                return listener.get();

            return listener.get(timeout, TimeUnit.MILLISECONDS);
        }
        catch (Throwable x)
        {
            // Differently from the Future, the semantic of this method is that if
            // the send() is interrupted or times out, we abort the request.
            abort(x);
            throw x;
        }
    }

    @Override
    public void send(Response.CompleteListener listener)
    {
        send(this, listener);
    }

    void sent()
    {
        sentTimestampNanos = System.nanoTime();
    }
    
    private void send(HttpRequest request, Response.CompleteListener listener)
    {
        if (listener != null)
            responseListeners.add(listener);
        sent();
        client.send(request, responseListeners);
    }

    /**
     * Get the time until the current request timeout fires
     * @param nanotime The current nanotime 
     * @param units The units of the time to return
     * @return The time until the current request timeout fires;
     * or 0 if the timeout has expired; or -1 if no timeout applies
     */
    public long timeoutIn(long nanotime, TimeUnit units)
    {
        long timeoutMs = getTimeout();
        if (timeoutMs<=0 || sentTimestampNanos==0)
            return -1;
        long now = TimeUnit.NANOSECONDS.toMillis(nanotime);
        long expires = TimeUnit.NANOSECONDS.toMillis(sentTimestampNanos)+timeoutMs;
        if (expires<=now)
            return 0;

        return TimeUnit.MILLISECONDS.convert(expires-now,units);
    }
    
    protected List<Response.ResponseListener> getResponseListeners()
    {
        return responseListeners;
    }

    public BiFunction<Request, Request, Response.CompleteListener> getPushListener()
    {
        return pushListener;
    }

    public Supplier<HttpFields> getTrailers()
    {
        return trailers;
    }

    @Override
    public boolean abort(Throwable cause)
    {
        if (aborted.compareAndSet(null, Objects.requireNonNull(cause)))
        {
            if (content instanceof Callback)
                ((Callback)content).failed(cause);
            return conversation.abort(cause);
        }
        return false;
    }

    @Override
    public Throwable getAbortCause()
    {
        return aborted.get();
    }

    private String buildQuery()
    {
        StringBuilder result = new StringBuilder();
        for (Iterator<Fields.Field> iterator = params.iterator(); iterator.hasNext(); )
        {
            Fields.Field field = iterator.next();
            List<String> values = field.getValues();
            for (int i = 0; i < values.size(); ++i)
            {
                if (i > 0)
                    result.append("&");
                result.append(field.getName()).append("=");
                result.append(urlEncode(values.get(i)));
            }
            if (iterator.hasNext())
                result.append("&");
        }
        return result.toString();
    }

    private String urlEncode(String value)
    {
        if (value == null)
            return "";

        String encoding = "utf-8";
        try
        {
            return URLEncoder.encode(value, encoding);
        }
        catch (UnsupportedEncodingException e)
        {
            throw new UnsupportedCharsetException(encoding);
        }
    }

    private void extractParams(String query)
    {
        if (query != null)
        {
            for (String nameValue : query.split("&"))
            {
                String[] parts = nameValue.split("=");
                if (parts.length > 0)
                {
                    String name = urlDecode(parts[0]);
                    if (name.trim().length() == 0)
                        continue;
                    param(name, parts.length < 2 ? "" : urlDecode(parts[1]), true);
                }
            }
        }
    }

    private String urlDecode(String value)
    {
        String charset = "utf-8";
        try
        {
            return URLDecoder.decode(value, charset);
        }
        catch (UnsupportedEncodingException x)
        {
            throw new UnsupportedCharsetException(charset);
        }
    }

    private URI buildURI(boolean withQuery)
    {
        String path = getPath();
        String query = getQuery();
        if (query != null && withQuery)
            path += "?" + query;
        URI result = newURI(path);
        if (result == null)
            return NULL_URI;
        if (!result.isAbsolute())
            result = URI.create(new Origin(getScheme(), getHost(), getPort()).asString() + path);
        return result;
    }

    private URI newURI(String uri)
    {
        try
        {
            // Handle specially the "OPTIONS *" case, since it is possible to create a URI from "*" (!).
            if ("*".equals(uri))
                return null;
            URI result = new URI(uri);
            return result.isOpaque() ? null : result;
        }
        catch (URISyntaxException x)
        {
            // The "path" of a HTTP request may not be a URI,
            // for example for CONNECT 127.0.0.1:8080.
            return null;
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s %s %s]@%x", HttpRequest.class.getSimpleName(), getMethod(), getPath(), getVersion(), hashCode());
    }
}
