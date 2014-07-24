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

package org.eclipse.jetty.http2.server;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpInput;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpChannelOverHTTP2 extends HttpChannel
{
    private static final Logger LOG = Log.getLogger(HttpChannelOverHTTP2.class);
    private static final HttpField ACCEPT_ENCODING_GZIP = new HttpField(HttpHeader.ACCEPT_ENCODING,"gzip");
    private static final HttpField SERVER_VERSION=new HttpField(HttpHeader.SERVER,HttpConfiguration.SERVER_VERSION);
    private static final HttpField POWERED_BY=new HttpField(HttpHeader.X_POWERED_BY,HttpConfiguration.SERVER_VERSION);
    private final Stream stream;

    public HttpChannelOverHTTP2(Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransport transport, HttpInput input, Stream stream)
    {
        super(connector, configuration, endPoint, transport, input);
        this.stream = stream;
    }

    public void requestHeaders(HeadersFrame frame)
    {
        MetaData metaData = frame.getMetaData();
        if (!metaData.isRequest())
        {
            onBadMessage(400, null);
            return;
        }

        MetaData.Request request = (MetaData.Request)metaData;

        // The specification says user agents MUST support gzip encoding.
        // Based on that, some browser does not send the header, but it's
        // important that applications can find it (e.g. GzipFilter).
        HttpFields fields = request.getFields();
        if (!fields.contains(HttpHeader.ACCEPT_ENCODING, "gzip"))
            fields.add(ACCEPT_ENCODING_GZIP);

        // TODO make this a better field for h2 hpack generation
        if (getHttpConfiguration().getSendServerVersion())
            getResponse().getHttpFields().add(SERVER_VERSION);
        if (getHttpConfiguration().getSendXPoweredBy())
            getResponse().getHttpFields().add(POWERED_BY);
        
        onRequest(request);
        
        if (frame.isEndStream())
        {
            onRequestComplete();
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("HTTP2 Request #{}:{}{} {} {}{}{}",
                    stream.getId(), System.lineSeparator(), request.getMethod(), request.getURI(), request.getVersion(), System.lineSeparator(), fields);
        }

        execute(this);
    }

    public void requestContent(DataFrame frame, final Callback callback)
    {
        // We must copy the data since we do not know when its bytes will be consumed.
        final ByteBufferPool byteBufferPool = getByteBufferPool();
        ByteBuffer original = frame.getData();
        final ByteBuffer copy = byteBufferPool.acquire(original.remaining(), original.isDirect());
        BufferUtil.clearToFill(copy);
        copy.put(original).flip();

        if (LOG.isDebugEnabled())
            LOG.debug("HTTP2 Request #{}: {} bytes of content", stream.getId(), copy.remaining());

        onContent(new HttpInput.Content(copy)
        {
            @Override
            public void succeeded()
            {
                byteBufferPool.release(copy);
                callback.succeeded();
            }

            @Override
            public void failed(Throwable x)
            {
                byteBufferPool.release(copy);
                callback.failed(x);
            }
        });

        if (frame.isEndStream())
        {
            onRequestComplete();
        }
    }
}
