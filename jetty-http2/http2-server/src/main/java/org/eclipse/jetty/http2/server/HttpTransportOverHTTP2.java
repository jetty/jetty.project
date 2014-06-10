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
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.hpack.MetaData;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

public class HttpTransportOverHTTP2 implements HttpTransport
{
    private final AtomicBoolean commit = new AtomicBoolean();
    private final IStream stream = null;
    private final HeadersFrame request = null;

    @Override
    public void send(HttpGenerator.ResponseInfo info, ByteBuffer content, boolean lastContent, Callback callback)
    {
        MetaData.Request metaData = (MetaData.Request)request.getMetaData();
        boolean isHeadRequest = HttpMethod.HEAD.is(metaData.getMethod());
        boolean hasContent = BufferUtil.hasContent(content) && !isHeadRequest;

        // TODO: the idea here is this:
        // CallbackLease lease = new CallbackLease(callback);
        // commit(lease, ...)
        //   stream.header(lease, frame)
        //     session.frame(lease, frame)
        //       generator.generate(lease, frame)
        //         generateHeader(lease, frame);
        //         bodyGenerator[frame.getType()].generateBody(lease, frame);
        //   stream.content(lease, frame)
        //     ...
        //   flush(lease)
        //
        // Problem is that in this way I need to aggregate multiple callbacks for the same lease.
        // So it'd need another abstraction that is a Lease+Callback

        if (commit.compareAndSet(false, true))
        {
            commit(info, !hasContent, !hasContent ? callback : new Callback.Adapter()
            {
                @Override
                public void failed(Throwable x)
                {
                    // TODO
                }
            });
        }
        else
        {
            // TODO
        }

        if (hasContent)
        {
            send(content, lastContent, callback);
        }
    }

    private void commit(HttpGenerator.ResponseInfo info, boolean endStream, Callback callback)
    {
        MetaData metaData = new MetaData.Response(info.getStatus(), info.getHttpFields());
        HeadersFrame frame = new HeadersFrame(stream.getId(), metaData, null, endStream);
        stream.headers(frame, callback);
    }

    @Override
    public void send(ByteBuffer content, boolean lastContent, Callback callback)
    {
        DataFrame frame = new DataFrame(stream.getId(), content, lastContent);
        stream.data(frame, callback);
    }

    @Override
    public void completed()
    {
    }

    @Override
    public void abort()
    {
    }
}
