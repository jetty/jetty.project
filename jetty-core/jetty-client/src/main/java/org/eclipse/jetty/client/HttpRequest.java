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

import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.client.util.PathRequestContent;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.NanoTime;

public class HttpRequest implements Request
{
    private static final URI NULL_URI = URI.create("null:0");

    private final HttpFields.Mutable headers = HttpFields.build();
    private final Fields params = new Fields(true);
    private final List<Response.ResponseListener> responseListeners = new ArrayList<>();
    private final AtomicReference<Throwable> aborted = new AtomicReference<>();
    private final HttpClient client;
    private final HttpConversation conversation;
    private String scheme;
    private String host;
    private int port;
    private String path;
    private String query;
    private URI uri;
    private String method = HttpMethod.GET.asString();
    private HttpVersion version = HttpVersion.HTTP_1_1;
    private boolean versionExplicit;
    private long idleTimeout = -1;
    private long timeout;
    private long timeoutNanoTime = Long.MAX_VALUE;
    private Content content;
    private boolean followRedirects;
    private List<HttpCookie> cookies;
    private Map<String, Object> attributes;
    private List<RequestListener> requestListeners;
    private BiFunction<Request, Request, Response.CompleteListener> pushListener;
    private Supplier<HttpFields> trailers;
    private String upgradeProtocol;
    private Object tag;
    private boolean normalized;

    protected HttpRequest(HttpClient client, HttpConversation conversation, URI uri)
    {
        this.client = client;
        this.conversation = conversation;
        scheme = uri.getScheme();
        host = uri.getHost();
        port = HttpClient.normalizePort(scheme, uri.getPort());
        path = uri.getRawPath();
        query = uri.getRawQuery();
        extractParams(query);

        followRedirects(client.isFollowRedirects());
        HttpField acceptEncodingField = client.getAcceptEncodingField();
        if (acceptEncodingField != null)
            headers.put(acceptEncodingField);
        HttpField userAgentField = client.getUserAgentField();
        if (userAgentField != null)
            headers.put(userAgentField);
    }

    public HttpConversation getConversation()
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
    public Request host(String host)
    {
        this.host = host;
        this.uri = null;
        return this;
    }

    @Override
    public int getPort()
    {
        return port;
    }

    @Override
    public Request port(int port)
    {
        this.port = port;
        this.uri = null;
        return this;
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
            if (!rawPath.startsWith("/"))
                rawPath = "/" + rawPath;
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

        @SuppressWarnings("ReferenceEquality")
        boolean isNullURI = (uri == NULL_URI);
        return isNullURI ? null : uri;
    }

    @Override
    public HttpVersion getVersion()
    {
        return version;
    }

    public boolean isVersionExplicit()
    {
        return versionExplicit;
    }

    @Override
    public Request version(HttpVersion version)
    {
        this.version = Objects.requireNonNull(version);
        this.versionExplicit = true;
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
    public List<HttpCookie> getCookies()
    {
        return cookies != null ? cookies : Collections.emptyList();
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
    public Request tag(Object tag)
    {
        this.tag = tag;
        return this;
    }

    @Override
    public Object getTag()
    {
        return tag;
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
        return attributes != null ? attributes : Collections.emptyMap();
    }

    @Override
    public HttpFields getHeaders()
    {
        return headers;
    }

    @Override
    public Request headers(Consumer<HttpFields.Mutable> consumer)
    {
        consumer.accept(headers);
        return this;
    }

    public HttpRequest addHeader(HttpField header)
    {
        headers.add(header);
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends RequestListener> List<T> getRequestListeners(Class<T> type)
    {
        // This method is invoked often in a request/response conversation,
        // so we avoid allocation if there is no need to filter.
        if (type == null || requestListeners == null)
            return requestListeners != null ? (List<T>)requestListeners : Collections.emptyList();

        ArrayList<T> result = new ArrayList<>();
        for (RequestListener listener : requestListeners)
        {
            if (type.isInstance(listener))
                result.add((T)listener);
        }
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
        this.responseListeners.add(new Response.ContentListener()
        {
            @Override
            public void onContent(Response response, ByteBuffer content)
            {
                listener.onContent(response, content);
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
    public Request onResponseContentDemanded(Response.DemandedContentListener listener)
    {
        this.responseListeners.add(new Response.DemandedContentListener()
        {
            @Override
            public void onBeforeContent(Response response, LongConsumer demand)
            {
                listener.onBeforeContent(response, demand);
            }

            @Override
            public void onContent(Response response, LongConsumer demand, ByteBuffer content, Callback callback)
            {
                listener.onContent(response, demand, content, callback);
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

    @Override
    public Request trailersSupplier(Supplier<HttpFields> trailers)
    {
        this.trailers = trailers;
        return this;
    }

    public HttpRequest upgradeProtocol(String upgradeProtocol)
    {
        this.upgradeProtocol = upgradeProtocol;
        return this;
    }

    @Override
    public Content getBody()
    {
        return content;
    }

    @Override
    public Request body(Content content)
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
        return body(new PathRequestContent(contentType, file));
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
        send(listener);

        try
        {
            return listener.get();
        }
        catch (ExecutionException x)
        {
            // Previously this method used a timed get on the future, which was in a race
            // with the timeouts implemented in HttpDestination and HttpConnection. The change to
            // make those timeouts relative to the timestamp taken in sent() has made that race
            // less certain, so a timeout could be either a TimeoutException from the get() or
            // a ExecutionException(TimeoutException) from the HttpDestination/HttpConnection.
            // We now do not do a timed get and just rely on the HttpDestination/HttpConnection
            // timeouts.   This has the affect of changing this method from mostly throwing a
            // TimeoutException to always throwing a ExecutionException(TimeoutException).
            // Thus for backwards compatibility we unwrap the timeout exception here
            if (x.getCause() instanceof TimeoutException)
            {
                TimeoutException t = (TimeoutException)(x.getCause());
                abort(t);
                throw t;
            }

            abort(x);
            throw x;
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
        sendAsync(client::send, listener);
    }

    void sendAsync(HttpDestination destination, Response.CompleteListener listener)
    {
        sendAsync(destination::send, listener);
    }

    private void sendAsync(BiConsumer<HttpRequest, List<Response.ResponseListener>> sender, Response.CompleteListener listener)
    {
        if (listener != null)
            responseListeners.add(listener);
        sender.accept(this, responseListeners);
    }

    void sent()
    {
        if (timeoutNanoTime == Long.MAX_VALUE)
        {
            long timeout = getTimeout();
            if (timeout > 0)
                timeoutNanoTime = NanoTime.now() + TimeUnit.MILLISECONDS.toNanos(timeout);
        }
    }

    /**
     * @return The nanoTime at which the timeout expires or {@link Long#MAX_VALUE} if there is no timeout.
     * @see #timeout(long, TimeUnit)
     */
    long getTimeoutNanoTime()
    {
        return timeoutNanoTime;
    }

    protected List<Response.ResponseListener> getResponseListeners()
    {
        return responseListeners;
    }

    public BiFunction<Request, Request, Response.CompleteListener> getPushListener()
    {
        return pushListener;
    }

    @Override
    public Supplier<HttpFields> getTrailersSupplier()
    {
        return trailers;
    }

    public String getUpgradeProtocol()
    {
        return upgradeProtocol;
    }

    @Override
    public boolean abort(Throwable cause)
    {
        if (aborted.compareAndSet(null, Objects.requireNonNull(cause)))
            return conversation.abort(cause);
        return false;
    }

    @Override
    public Throwable getAbortCause()
    {
        return aborted.get();
    }

    /**
     * <p>Marks this request as <em>normalized</em>.</p>
     * <p>A request is normalized by setting things that applications give
     * for granted such as defaulting the method to {@code GET}, adding the
     * {@code Host} header, adding the cookies, adding {@code Authorization}
     * headers, etc.</p>
     *
     * @return whether this request was already normalized
     * @see HttpConnection#normalizeRequest(HttpRequest)
     */
    boolean normalized()
    {
        boolean result = normalized;
        normalized = true;
        return result;
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

        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
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

    private URI newURI(String path)
    {
        try
        {
            // Handle specially the "OPTIONS *" case, since it is possible to create a URI from "*" (!).
            if ("*".equals(path))
                return null;
            URI result = new URI(path);
            return result.isOpaque() ? null : result;
        }
        catch (URISyntaxException x)
        {
            // The "path" of an HTTP request may not be a URI,
            // for example for CONNECT 127.0.0.1:8080.
            return null;
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s %s %s]@%x", getClass().getSimpleName(), getMethod(), getPath(), getVersion(), hashCode());
    }
}
