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

package org.eclipse.jetty.spdy.server.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannelConfig;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpTransportOverSPDY implements HttpTransport
{
    private static final Logger LOG = Log.getLogger(HttpTransportOverSPDY.class);

    private final Connector connector;
    private final HttpChannelConfig configuration;
    private final EndPoint endPoint;
    private final PushStrategy pushStrategy;
    private final Stream stream;
    private final Fields requestHeaders;

    public HttpTransportOverSPDY(Connector connector, HttpChannelConfig configuration, EndPoint endPoint, PushStrategy pushStrategy, Stream stream, Fields requestHeaders)
    {
        this.connector = connector;
        this.configuration = configuration;
        this.endPoint = endPoint;
        this.pushStrategy = pushStrategy;
        this.stream = stream;
        this.requestHeaders = requestHeaders;
    }

    @Override
    public void send(HttpGenerator.ResponseInfo info, ByteBuffer content, boolean lastContent) throws IOException
    {
        short version = stream.getSession().getVersion();
        Fields headers = new Fields();

        HttpVersion httpVersion = HttpVersion.HTTP_1_1;
        headers.put(HTTPSPDYHeader.VERSION.name(version), httpVersion.asString());

        int status = info.getStatus();
        StringBuilder httpStatus = new StringBuilder().append(status);
        String reason = info.getReason();
        if (reason == null)
            reason = HttpStatus.getMessage(status);
        if (reason != null)
            httpStatus.append(" ").append(reason);
        headers.put(HTTPSPDYHeader.STATUS.name(version), httpStatus.toString());
        LOG.debug("HTTP < {} {}", httpVersion, httpStatus);

        // TODO merge the two Field classes into one
        HttpFields fields = info.getHttpFields();
        if (fields != null)
        {
            for (int i = 0; i < fields.size(); ++i)
            {
                HttpFields.Field field = fields.getField(i);
                String name = field.getName();
                String value = field.getValue();
                headers.put(name, value);
                LOG.debug("HTTP < {}: {}", name, value);
            }
        }

        boolean hasContent = !BufferUtil.isEmpty(content);
        boolean close = !hasContent && lastContent;
        reply(stream, new ReplyInfo(headers, close));

        if (hasContent)
            sendToStream(content, lastContent);
    }

    @Override
    public void send(ByteBuffer content, boolean lastContent) throws IOException
    {
        // Guard against a last 0 bytes write
        // TODO work out if we can avoid double calls for lastContent==true
        if (stream.isClosed() && BufferUtil.isEmpty(content) && lastContent)
            return;

        sendToStream(content, lastContent);
    }

    private void sendToStream(ByteBuffer content, boolean lastContent)
    {
        try
        {
            stream.data(new ByteBufferDataInfo(content, lastContent)).get();
        }
        catch (Exception e)
        {
            endPoint.close();
        }
    }

    @Override
    // TODO move to channel ?
    public void completed()
    {
        LOG.debug("completed");
    }

    private void reply(Stream stream, ReplyInfo replyInfo)
    {
        if (!stream.isUnidirectional())
            stream.reply(replyInfo);

        Fields responseHeaders = replyInfo.getHeaders();
        short version = stream.getSession().getVersion();
        if (responseHeaders.get(HTTPSPDYHeader.STATUS.name(version)).value().startsWith("200") && !stream.isClosed())
        {
            // We have a 200 OK with some content to send, check the push strategy
            Fields.Field scheme = requestHeaders.get(HTTPSPDYHeader.SCHEME.name(version));
            Fields.Field host = requestHeaders.get(HTTPSPDYHeader.HOST.name(version));
            Fields.Field uri = requestHeaders.get(HTTPSPDYHeader.URI.name(version));
            Set<String> pushResources = pushStrategy.apply(stream, requestHeaders, responseHeaders);

            for (String pushResource : pushResources)
            {
                Fields pushHeaders = createPushHeaders(scheme, host, pushResource);
                final Fields pushRequestHeaders = createRequestHeaders(scheme, host, uri, pushResource);

                // TODO: handle the timeout better
                stream.syn(new SynInfo(pushHeaders, false), 0, TimeUnit.MILLISECONDS, new Callback.Empty<Stream>()
                {
                    @Override
                    public void completed(Stream pushStream)
                    {
                        HttpChannelOverSPDY pushChannel = newHttpChannelOverSPDY(pushStream, pushRequestHeaders);
                        pushChannel.requestStart(pushRequestHeaders, true);
                    }
                });
            }
        }
    }

    private Fields createRequestHeaders(Fields.Field scheme, Fields.Field host, Fields.Field uri, String pushResourcePath)
    {
        final Fields requestHeaders = new Fields();
        short version = stream.getSession().getVersion();
        requestHeaders.put(HTTPSPDYHeader.METHOD.name(version), "GET");
        requestHeaders.put(HTTPSPDYHeader.VERSION.name(version), "HTTP/1.1");
        requestHeaders.put(scheme);
        requestHeaders.put(host);
        requestHeaders.put(HTTPSPDYHeader.URI.name(version), pushResourcePath);
        String referrer = scheme.value() + "://" + host.value() + uri.value();
        requestHeaders.put("referer", referrer);
        // Remember support for gzip encoding
        requestHeaders.put(requestHeaders.get("accept-encoding"));
        requestHeaders.put("x-spdy-push", "true");
        return requestHeaders;
    }

    private Fields createPushHeaders(Fields.Field scheme, Fields.Field host, String pushResourcePath)
    {
        final Fields pushHeaders = new Fields();
        short version = stream.getSession().getVersion();
        if (version == SPDY.V2)
            pushHeaders.put(HTTPSPDYHeader.URI.name(version), scheme.value() + "://" + host.value() + pushResourcePath);
        else
        {
            pushHeaders.put(HTTPSPDYHeader.URI.name(version), pushResourcePath);
            pushHeaders.put(scheme);
            pushHeaders.put(host);
        }
        pushHeaders.put(HTTPSPDYHeader.STATUS.name(version), "200");
        pushHeaders.put(HTTPSPDYHeader.VERSION.name(version), "HTTP/1.1");
        return pushHeaders;
    }

    private HttpChannelOverSPDY newHttpChannelOverSPDY(Stream pushStream, Fields pushRequestHeaders)
    {
        HttpTransport transport = new HttpTransportOverSPDY(connector, configuration, endPoint, pushStrategy, pushStream, pushRequestHeaders);
        HttpInputOverSPDY input = new HttpInputOverSPDY();
        return new HttpChannelOverSPDY(connector, configuration, endPoint, transport, input, pushStream);
    }
}
