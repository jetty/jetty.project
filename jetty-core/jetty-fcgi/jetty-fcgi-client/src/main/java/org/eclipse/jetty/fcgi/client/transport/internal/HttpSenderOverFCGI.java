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

package org.eclipse.jetty.fcgi.client.transport.internal;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.Locale;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.internal.HttpChannel;
import org.eclipse.jetty.client.internal.HttpExchange;
import org.eclipse.jetty.client.internal.HttpSender;
import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.client.transport.HttpClientTransportOverFCGI;
import org.eclipse.jetty.fcgi.generator.ClientGenerator;
import org.eclipse.jetty.fcgi.generator.Generator;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.StringUtil;

public class HttpSenderOverFCGI extends HttpSender
{
    private final ClientGenerator generator;

    public HttpSenderOverFCGI(HttpChannel channel)
    {
        super(channel);
        HttpClient httpClient = channel.getHttpDestination().getHttpClient();
        this.generator = new ClientGenerator(httpClient.getByteBufferPool(), httpClient.isUseOutputDirectByteBuffers());
    }

    @Override
    protected HttpChannelOverFCGI getHttpChannel()
    {
        return (HttpChannelOverFCGI)super.getHttpChannel();
    }

    @Override
    protected void sendHeaders(HttpExchange exchange, ByteBuffer contentBuffer, boolean lastContent, Callback callback)
    {
        Request request = exchange.getRequest();
        // Copy the request headers to be able to convert them properly
        HttpFields headers = request.getHeaders();
        HttpFields.Mutable fcgiHeaders = HttpFields.build();

        // FastCGI headers based on the URI
        URI uri = request.getURI();
        String path = uri == null ? request.getPath() : uri.getRawPath();
        fcgiHeaders.put(FCGI.Headers.DOCUMENT_URI, path);
        String query = uri == null ? null : uri.getRawQuery();
        fcgiHeaders.put(FCGI.Headers.QUERY_STRING, query == null ? "" : query);

        // FastCGI headers based on HTTP headers
        HttpField httpField = headers.getField(HttpHeader.AUTHORIZATION);
        EnumSet<HttpHeader> toRemove = EnumSet.of(HttpHeader.AUTHORIZATION);
        if (httpField != null)
            fcgiHeaders.put(FCGI.Headers.AUTH_TYPE, httpField.getValue());
        httpField = headers.getField(HttpHeader.CONTENT_LENGTH);
        toRemove.add(HttpHeader.CONTENT_LENGTH);
        fcgiHeaders.put(FCGI.Headers.CONTENT_LENGTH, httpField == null ? "" : httpField.getValue());
        httpField = headers.getField(HttpHeader.CONTENT_TYPE);
        toRemove.add(HttpHeader.CONTENT_TYPE);
        fcgiHeaders.put(FCGI.Headers.CONTENT_TYPE, httpField == null ? "" : httpField.getValue());

        // FastCGI headers that are not based on HTTP headers nor URI
        fcgiHeaders.put(FCGI.Headers.REQUEST_METHOD, request.getMethod());
        fcgiHeaders.put(FCGI.Headers.SERVER_PROTOCOL, request.getVersion().asString());
        fcgiHeaders.put(FCGI.Headers.GATEWAY_INTERFACE, "CGI/1.1");
        fcgiHeaders.put(FCGI.Headers.SERVER_SOFTWARE, "Jetty/" + Jetty.VERSION);

        // Translate remaining HTTP header into the HTTP_* format
        for (HttpField field : headers)
        {
            if (toRemove.contains(field.getHeader()))
                continue;
            String name = field.getName();
            String fcgiName = "HTTP_" + StringUtil.replace(name, '-', '_').toUpperCase(Locale.ENGLISH);
            fcgiHeaders.add(fcgiName, field.getValue());
        }

        // Give a chance to the transport implementation to customize the FastCGI headers
        HttpClientTransportOverFCGI transport = (HttpClientTransportOverFCGI)getHttpChannel().getHttpDestination().getHttpClient().getTransport();
        transport.customize(request, fcgiHeaders);

        int id = getHttpChannel().getRequest();
        if (contentBuffer.hasRemaining() || lastContent)
        {
            Generator.Result headersResult = generator.generateRequestHeaders(id, fcgiHeaders, Callback.NOOP);
            Generator.Result contentResult = generator.generateRequestContent(id, contentBuffer, lastContent, callback);
            getHttpChannel().flush(headersResult, contentResult);
        }
        else
        {
            Generator.Result headersResult = generator.generateRequestHeaders(id, fcgiHeaders, callback);
            getHttpChannel().flush(headersResult);
        }
    }

    @Override
    protected void sendContent(HttpExchange exchange, ByteBuffer contentBuffer, boolean lastContent, Callback callback)
    {
        if (contentBuffer.hasRemaining() || lastContent)
        {
            int request = getHttpChannel().getRequest();
            Generator.Result result = generator.generateRequestContent(request, contentBuffer, lastContent, callback);
            getHttpChannel().flush(result);
        }
        else
        {
            callback.succeeded();
        }
    }
}
