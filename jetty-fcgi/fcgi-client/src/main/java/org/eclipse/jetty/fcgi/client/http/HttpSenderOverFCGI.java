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

package org.eclipse.jetty.fcgi.client.http;

import java.net.URI;
import java.util.Locale;

import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpContent;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpSender;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.generator.ClientGenerator;
import org.eclipse.jetty.fcgi.generator.Generator;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.StringUtil;

public class HttpSenderOverFCGI extends HttpSender
{
    private final ClientGenerator generator;

    public HttpSenderOverFCGI(HttpChannel channel)
    {
        super(channel);
        this.generator = new ClientGenerator(channel.getHttpDestination().getHttpClient().getByteBufferPool());
    }

    @Override
    protected HttpChannelOverFCGI getHttpChannel()
    {
        return (HttpChannelOverFCGI)super.getHttpChannel();
    }

    @Override
    protected void sendHeaders(HttpExchange exchange, HttpContent content, Callback callback)
    {
        Request request = exchange.getRequest();
        // Copy the request headers to be able to convert them properly
        HttpFields headers = new HttpFields();
        for (HttpField field : request.getHeaders())
        {
            headers.put(field);
        }
        HttpFields fcgiHeaders = new HttpFields();

        // FastCGI headers based on the URI
        URI uri = request.getURI();
        String path = uri == null ? request.getPath() : uri.getRawPath();
        fcgiHeaders.put(FCGI.Headers.DOCUMENT_URI, path);
        String query = uri == null ? null : uri.getRawQuery();
        fcgiHeaders.put(FCGI.Headers.QUERY_STRING, query == null ? "" : query);

        // FastCGI headers based on HTTP headers
        HttpField httpField = headers.remove(HttpHeader.AUTHORIZATION);
        if (httpField != null)
            fcgiHeaders.put(FCGI.Headers.AUTH_TYPE, httpField.getValue());
        httpField = headers.remove(HttpHeader.CONTENT_LENGTH);
        fcgiHeaders.put(FCGI.Headers.CONTENT_LENGTH, httpField == null ? "" : httpField.getValue());
        httpField = headers.remove(HttpHeader.CONTENT_TYPE);
        fcgiHeaders.put(FCGI.Headers.CONTENT_TYPE, httpField == null ? "" : httpField.getValue());

        // FastCGI headers that are not based on HTTP headers nor URI
        fcgiHeaders.put(FCGI.Headers.REQUEST_METHOD, request.getMethod());
        fcgiHeaders.put(FCGI.Headers.SERVER_PROTOCOL, request.getVersion().asString());
        fcgiHeaders.put(FCGI.Headers.GATEWAY_INTERFACE, "CGI/1.1");
        fcgiHeaders.put(FCGI.Headers.SERVER_SOFTWARE, "Jetty/" + Jetty.VERSION);

        // Translate remaining HTTP header into the HTTP_* format
        for (HttpField field : headers)
        {
            String name = field.getName();
            String fcgiName = "HTTP_" + StringUtil.replace(name, '-', '_').toUpperCase(Locale.ENGLISH);
            fcgiHeaders.add(fcgiName, field.getValue());
        }

        // Give a chance to the transport implementation to customize the FastCGI headers
        HttpClientTransportOverFCGI transport = (HttpClientTransportOverFCGI)getHttpChannel().getHttpDestination().getHttpClient().getTransport();
        transport.customize(request, fcgiHeaders);

        int id = getHttpChannel().getRequest();
        boolean hasContent = content.hasContent();
        Generator.Result headersResult = generator.generateRequestHeaders(id, fcgiHeaders,
            hasContent ? callback : Callback.NOOP);
        if (hasContent)
        {
            getHttpChannel().flush(headersResult);
        }
        else
        {
            Generator.Result noContentResult = generator.generateRequestContent(id, BufferUtil.EMPTY_BUFFER, true, callback);
            getHttpChannel().flush(headersResult, noContentResult);
        }
    }

    @Override
    protected void sendContent(HttpExchange exchange, HttpContent content, Callback callback)
    {
        if (content.isConsumed())
        {
            callback.succeeded();
        }
        else
        {
            int request = getHttpChannel().getRequest();
            Generator.Result result = generator.generateRequestContent(request, content.getByteBuffer(), content.isLast(), callback);
            getHttpChannel().flush(result);
        }
    }
}
