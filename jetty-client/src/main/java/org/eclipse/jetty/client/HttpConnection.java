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

import java.io.UnsupportedEncodingException;
import java.net.HttpCookie;
import java.net.URLEncoder;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpConnection extends AbstractConnection implements Connection
{
    private static final Logger LOG = Log.getLogger(HttpConnection.class);
    private static final HttpField CHUNKED_FIELD = new HttpField(HttpHeader.TRANSFER_ENCODING, HttpHeaderValue.CHUNKED);

    private final AtomicReference<HttpExchange> exchange = new AtomicReference<>();
    private final HttpClient client;
    private final HttpDestination destination;
    private final HttpSender sender;
    private final HttpReceiver receiver;
    private long idleTimeout;

    public HttpConnection(HttpClient client, EndPoint endPoint, HttpDestination destination)
    {
        super(endPoint, client.getExecutor(), client.isDispatchIO());
        this.client = client;
        this.destination = destination;
        this.sender = new HttpSender(this);
        this.receiver = new HttpReceiver(this);
    }

    public HttpClient getHttpClient()
    {
        return client;
    }

    public HttpDestination getDestination()
    {
        return destination;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        fillInterested();
    }

    @Override
    protected boolean onReadTimeout()
    {
        LOG.debug("{} idle timeout", this);

        HttpExchange exchange = getExchange();
        if (exchange != null)
            idleTimeout();
        else
            destination.remove(this);

        return true;
    }

    protected void idleTimeout()
    {
        receiver.idleTimeout();
    }

    @Override
    public void send(Request request, Response.CompleteListener listener)
    {
        send(request, Collections.<Response.ResponseListener>singletonList(listener));
    }

    public void send(Request request, List<Response.ResponseListener> listeners)
    {
        normalizeRequest(request);

        // Save the old idle timeout to restore it
        EndPoint endPoint = getEndPoint();
        idleTimeout = endPoint.getIdleTimeout();
        endPoint.setIdleTimeout(request.getIdleTimeout());

        HttpConversation conversation = client.getConversation(request.getConversationID(), true);
        HttpExchange exchange = new HttpExchange(conversation, this, request, listeners);
        setExchange(exchange);
        conversation.getExchanges().offer(exchange);

        for (Response.ResponseListener listener : listeners)
            if (listener instanceof Schedulable)
                ((Schedulable)listener).schedule(client.getScheduler());

        sender.send(exchange);
    }

    private void normalizeRequest(Request request)
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
        if (destination.isProxied() && HttpMethod.CONNECT != request.getMethod())
        {
            path = request.getURI().toString();
            request.path(path);
        }

        Fields fields = request.getParams();
        if (!fields.isEmpty())
        {
            StringBuilder params = new StringBuilder();
            for (Iterator<Fields.Field> fieldIterator = fields.iterator(); fieldIterator.hasNext();)
            {
                Fields.Field field = fieldIterator.next();
                String[] values = field.values();
                for (int i = 0; i < values.length; ++i)
                {
                    if (i > 0)
                        params.append("&");
                    params.append(field.name()).append("=");
                    params.append(urlEncode(values[i]));
                }
                if (fieldIterator.hasNext())
                    params.append("&");
            }

            // Behave as a GET, adding the params to the path, if it's a POST with some content
            if (method == HttpMethod.POST && request.getContent() != null)
                method = HttpMethod.GET;

            switch (method)
            {
                case GET:
                {
                    path += "?";
                    path += params.toString();
                    request.path(path);
                    break;
                }
                case POST:
                {
                    request.header(HttpHeader.CONTENT_TYPE.asString(), MimeTypes.Type.FORM_ENCODED.asString());
                    request.content(new StringContentProvider(params.toString()));
                    break;
                }
            }
        }

        // If we are HTTP 1.1, add the Host header
        if (version.getVersion() > 10)
        {
            if (!headers.containsKey(HttpHeader.HOST.asString()))
                headers.put(getDestination().getHostField());
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
        Authentication.Result authnResult = client.getAuthenticationStore().findAuthenticationResult(request.getURI());
        if (authnResult != null)
            authnResult.apply(request);

        if (!headers.containsKey(HttpHeader.ACCEPT_ENCODING.asString()))
        {
            HttpField acceptEncodingField = client.getAcceptEncodingField();
            if (acceptEncodingField != null)
                headers.put(acceptEncodingField);
        }
    }

    private String urlEncode(String value)
    {
        String encoding = "UTF-8";
        try
        {
            return URLEncoder.encode(value, encoding);
        }
        catch (UnsupportedEncodingException e)
        {
            throw new UnsupportedCharsetException(encoding);
        }
    }

    public HttpExchange getExchange()
    {
        return exchange.get();
    }

    protected void setExchange(HttpExchange exchange)
    {
        if (!this.exchange.compareAndSet(null, exchange))
            throw new UnsupportedOperationException("Pipelined requests not supported");
        else
            LOG.debug("{} associated to {}", exchange, this);
    }

    @Override
    public void onFillable()
    {
        HttpExchange exchange = getExchange();
        if (exchange != null)
        {
            exchange.receive();
        }
        else
        {
            // If there is no exchange, then could be either a remote close,
            // or garbage bytes; in both cases we close the connection
            close();
        }
    }

    protected void receive()
    {
        receiver.receive();
    }

    public void complete(HttpExchange exchange, boolean success)
    {
        HttpExchange existing = this.exchange.getAndSet(null);
        if (existing == exchange)
        {
            exchange.awaitTermination();

            // Restore idle timeout
            getEndPoint().setIdleTimeout(idleTimeout);

            LOG.debug("{} disassociated from {}", exchange, this);
            if (success)
            {
                HttpFields responseHeaders = exchange.getResponse().getHeaders();
                Enumeration<String> values = responseHeaders.getValues(HttpHeader.CONNECTION.asString(), ",");
                if (values != null)
                {
                    while (values.hasMoreElements())
                    {
                        if ("close".equalsIgnoreCase(values.nextElement()))
                        {
                            close();
                            return;
                        }
                    }
                }
                destination.release(this);
            }
            else
            {
                close();
            }
        }
        else if (existing == null)
        {
            // It is possible that the exchange has already been disassociated,
            // for example if the connection idle timeouts: this will fail
            // the response, but the request may still be under processing.
            // Eventually the request will also fail as the connection is closed
            // and will arrive here without an exchange being present.
            // We just ignore this fact, as the exchange has already been processed
        }
        else
        {
            throw new IllegalStateException();
        }
    }

    public boolean abort(HttpExchange exchange, Throwable cause)
    {
        // We want the return value to be that of the response
        // because if the response has already successfully
        // arrived then we failed to abort the exchange
        sender.abort(exchange, cause);
        return receiver.abort(exchange, cause);
    }

    public void proceed(boolean proceed)
    {
        sender.proceed(proceed);
    }

    @Override
    public void close()
    {
        destination.remove(this);
        getEndPoint().shutdownOutput();
        LOG.debug("{} oshut", this);
        getEndPoint().close();
        LOG.debug("{} closed", this);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x(l:%s <-> r:%s)",
                HttpConnection.class.getSimpleName(),
                hashCode(),
                getEndPoint().getLocalAddress(),
                getEndPoint().getRemoteAddress());
    }
}
