//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.client.transport.internal;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

import org.eclipse.jetty.client.HttpUpgrader;
import org.eclipse.jetty.client.transport.HttpExchange;
import org.eclipse.jetty.client.transport.HttpRequest;
import org.eclipse.jetty.client.transport.HttpSender;
import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.HTTP3Stream;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.HTTP3SessionClient;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpSenderOverHTTP3 extends HttpSender
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpSenderOverHTTP3.class);

    public HttpSenderOverHTTP3(HttpChannelOverHTTP3 channel)
    {
        super(channel);
    }

    @Override
    protected HttpChannelOverHTTP3 getHttpChannel()
    {
        return (HttpChannelOverHTTP3)super.getHttpChannel();
    }

    @Override
    protected void sendHeaders(HttpExchange exchange, ByteBuffer contentBuffer, boolean lastContent, Callback callback)
    {
        HttpRequest request = exchange.getRequest();
        boolean isTunnel = HttpMethod.CONNECT.is(request.getMethod());
        MetaData.Request metaData;
        if (isTunnel)
        {
            String upgradeProtocol = (String)request.getAttributes().get(HttpUpgrader.PROTOCOL_ATTRIBUTE);
            if (upgradeProtocol == null)
            {
                metaData = new MetaData.ConnectRequest((String)null, new HostPortHttpField(request.getPath()), null, request.getHeaders(), null);
            }
            else
            {
                HostPortHttpField authority = new HostPortHttpField(request.getHost(), request.getPort());
                String pathQuery = URIUtil.addPathQuery(request.getPath(), request.getQuery());
                metaData = new MetaData.ConnectRequest(request.getScheme(), authority, pathQuery, request.getHeaders(), upgradeProtocol);
            }
        }
        else
        {
            String path = relativize(request.getPath());
            // URI validations already performed by using the java.net.URI class.
            HttpURI uri = HttpURI.from(request.getScheme(), request.getHost(), request.getPort(), path, request.getQuery(), null);
            metaData = new MetaData.Request(request.getMethod(), uri, HttpVersion.HTTP_3, request.getHeaders(), -1, request.getTrailersSupplier());
        }

        HeadersFrame headersFrame;
        RetainableByteBuffer data = null;
        boolean dataLast = false;
        HeadersFrame trailerFrame = null;

        if (isTunnel)
        {
            headersFrame = new HeadersFrame(metaData, false);
        }
        else
        {
            boolean hasContent = BufferUtil.hasContent(contentBuffer);
            if (hasContent)
            {
                headersFrame = new HeadersFrame(metaData, false);
                data = RetainableByteBuffer.wrap(contentBuffer);
                if (lastContent)
                {
                    HttpFields trailers = retrieveTrailers(request);
                    boolean hasTrailers = trailers != null;
                    dataLast = !hasTrailers;
                    if (hasTrailers)
                        trailerFrame = new HeadersFrame(new MetaData(HttpVersion.HTTP_3, trailers), true);
                }
            }
            else
            {
                if (lastContent)
                {
                    HttpFields trailers = retrieveTrailers(request);
                    boolean hasTrailers = trailers != null;
                    headersFrame = new HeadersFrame(metaData, !hasTrailers);
                    if (hasTrailers)
                        trailerFrame = new HeadersFrame(new MetaData(HttpVersion.HTTP_3, trailers), true);
                }
                else
                {
                    headersFrame = new HeadersFrame(metaData, false);
                }
            }
        }

        HeadersFrame hf = headersFrame;
        RetainableByteBuffer d = data;
        boolean dl = dataLast;
        HeadersFrame tf = trailerFrame;

        HTTP3SessionClient session = getHttpChannel().getSession();
        session.newRequest(hf, getHttpChannel().getStreamListener(), Promise.Invocable.from(callback.getInvocationType(), s ->
        {
            onNewStream(s, request);

            if (LOG.isDebugEnabled())
            {
                LOG.debug("HTTP3 request #{}/{}:{}{} {}{}{}",
                    s.getId(), Integer.toHexString(s.getSession().hashCode()),
                    System.lineSeparator(), metaData.getMethod(), metaData.getHttpURI(),
                    System.lineSeparator(), metaData.getHttpFields());
            }

            if (d != null)
            {
                if (tf != null)
                    sendDataAndTrailer(s, d, tf, callback);
                else
                    sendData(s, d, dl, lastContent, callback);
            }
            else
            {
                if (tf != null)
                    sendTrailer(s, tf, callback);
                else
                    callback.succeeded();
            }
        }, callback::failed));
    }

    private void onNewStream(Stream stream, HttpRequest request)
    {
        long idleTimeout = request.getIdleTimeout();
        if (idleTimeout > 0)
            ((HTTP3Stream)stream).setIdleTimeout(idleTimeout);
    }

    private HttpFields retrieveTrailers(HttpRequest request)
    {
        Supplier<HttpFields> trailerSupplier = request.getTrailersSupplier();
        HttpFields trailers = trailerSupplier == null ? null : trailerSupplier.get();
        return trailers == null || trailers.size() == 0 ? null : trailers;
    }

    @Override
    protected void sendContent(HttpExchange exchange, ByteBuffer contentBuffer, boolean lastContent, Callback callback)
    {
        Stream stream = getHttpChannel().getStream();
        boolean hasContent = contentBuffer.hasRemaining();
        if (lastContent)
        {
            // Call the trailers supplier as late as possible.
            HttpFields trailers = retrieveTrailers(exchange.getRequest());
            boolean hasTrailers = trailers != null && trailers.size() > 0;
            if (hasContent)
            {
                RetainableByteBuffer data = RetainableByteBuffer.wrap(contentBuffer);
                if (hasTrailers)
                {
                    HeadersFrame trailerFrame = new HeadersFrame(new MetaData(HttpVersion.HTTP_3, trailers), true);
                    sendDataAndTrailer(stream, data, trailerFrame, callback);
                }
                else
                {
                    sendData(stream, data, true, true, callback);
                }
            }
            else
            {
                if (hasTrailers)
                {
                    HeadersFrame trailerFrame = new HeadersFrame(new MetaData(HttpVersion.HTTP_3, trailers), true);
                    sendTrailer(stream, trailerFrame, callback);
                }
                else
                {
                    RetainableByteBuffer data = RetainableByteBuffer.wrap(contentBuffer);
                    sendData(stream, data, true, true, callback);
                }
            }
        }
        else
        {
            if (hasContent)
            {
                RetainableByteBuffer data = RetainableByteBuffer.wrap(contentBuffer);
                sendData(stream, data, false, false, callback);
            }
            else
            {
                // Don't send empty non-last content.
                callback.succeeded();
            }
        }
    }

    private void sendDataAndTrailer(Stream stream, RetainableByteBuffer data, HeadersFrame trailersFrame, Callback callback)
    {
        sendData(stream, data, false, true, Callback.from(callback.getInvocationType(), () -> sendTrailer(stream, trailersFrame, callback), callback::failed));
    }

    private void sendData(Stream stream, RetainableByteBuffer data, boolean dataLast, boolean lastContent, Callback callback)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP3 request #{}/{}: {} content bytes{}",
                stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                data.remaining(), lastContent ? " (last chunk)" : "");
        }
        stream.data(data, dataLast, Promise.Invocable.from(callback.getInvocationType(), _ -> callback.succeeded(), callback::failed));
    }

    private void sendTrailer(Stream stream, HeadersFrame trailerFrame, Callback callback)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP3 request #{}/{}: trailer{}{}",
                stream.getId(), Integer.toHexString(stream.getSession().hashCode()),
                System.lineSeparator(), trailerFrame.getMetaData().getHttpFields());
        }
        stream.trailer(trailerFrame, Promise.Invocable.from(callback.getInvocationType(), _ -> callback.succeeded(), callback::failed));
    }
}
