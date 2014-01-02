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


package org.eclipse.jetty.spdy.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.junit.Assert;
import org.junit.Test;

public class HeadersTest extends AbstractTest
{
    @Test
    public void testHeaders() throws Exception
    {
        ServerSessionFrameListener serverSessionFrameListener = new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(false), new Callback.Adapter());
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onHeaders(Stream stream, HeadersInfo headersInfo)
                    {
                        Assert.assertTrue(stream.isHalfClosed());
                        stream.headers(new HeadersInfo(new Fields(), true), new Callback.Adapter());
                        Assert.assertTrue(stream.isClosed());
                    }
                };
            }
        };

        Session session = startClient(startServer(serverSessionFrameListener), null);

        final CountDownLatch latch = new CountDownLatch(1);
        session.syn(new SynInfo(new Fields(), false), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Fields headers = new Fields();
                headers.put("foo", "bar");
                headers.put("baz", "woo");
                stream.headers(new HeadersInfo(headers, true), new Callback.Adapter());
                Assert.assertTrue(stream.isHalfClosed());
            }

            @Override
            public void onHeaders(Stream stream, HeadersInfo headersInfo)
            {
                Assert.assertTrue(stream.isClosed());
                latch.countDown();
            }
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
