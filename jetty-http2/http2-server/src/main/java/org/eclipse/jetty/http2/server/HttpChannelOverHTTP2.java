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
import java.util.List;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.hpack.MetaData;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpInput;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

public class HttpChannelOverHTTP2 extends HttpChannel<ByteBufferCallback>
{
    public HttpChannelOverHTTP2(Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransport transport, HttpInput<ByteBufferCallback> input)
    {
        super(connector, configuration, endPoint, transport, input);
    }

    public void requestHeaders(HeadersFrame frame)
    {
        MetaData metaData = frame.getMetaData();
        if (!metaData.isRequest())
        {
            badMessage(400, null);
            return;
        }

        MetaData.Request requestMetaData = (MetaData.Request)metaData;

        String method = requestMetaData.getMethod();
        HttpURI uri = new HttpURI(requestMetaData.getPath());
        HttpVersion version = HttpVersion.HTTP_2_0;

        startRequest(method, uri, version);

        HttpScheme scheme = requestMetaData.getScheme();
        if (scheme != null)
        {
            getRequest().setScheme(scheme.asString());
        }

        parsedHostHeader(requestMetaData.getHost(), requestMetaData.getPort());

        List<HttpField> fields = requestMetaData.getFields();
        for (int i = 0; i < fields.size(); ++i)
        {
            HttpField field = fields.get(i);
            parsedHeader(field);
        }

        headerComplete();

        if (frame.isEndStream())
        {
            messageComplete();
        }

        // TODO: pending refactoring of HttpChannel API.
        // Here we "cheat", knowing that headerComplete() will always return true
        // and that content() and messageComplete() will always return false.
        // This is the only place where we process the channel.
        execute(this);
    }

    public void requestContent(DataFrame frame, Callback callback)
    {
        // We must copy the data since we do not know when its bytes will be consumed.
        ByteBufferPool byteBufferPool = getByteBufferPool();
        ByteBuffer original = frame.getData();
        final ByteBuffer copy = byteBufferPool.acquire(original.remaining(), original.isDirect());
        BufferUtil.clearToFill(copy);
        copy.put(original).flip();

        // TODO: pending refactoring of HttpChannel API (see above).
        content(new ByteBufferCallback(byteBufferPool, copy, callback));

        if (frame.isEndStream())
        {
            messageComplete();
        }
    }

    @Override
    public boolean messageComplete()
    {
        super.messageComplete();
        return false;
    }
}
