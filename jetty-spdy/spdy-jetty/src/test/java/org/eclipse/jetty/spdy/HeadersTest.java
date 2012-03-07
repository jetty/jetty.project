/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
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
                stream.reply(new ReplyInfo(false));
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onHeaders(Stream stream, HeadersInfo headersInfo)
                    {
                        Assert.assertTrue(stream.isHalfClosed());
                        stream.headers(new HeadersInfo(new Headers(), true));
                        Assert.assertTrue(stream.isClosed());
                    }
                };
            }
        };

        Session session = startClient(startServer(serverSessionFrameListener), null);

        final CountDownLatch latch = new CountDownLatch(1);
        session.syn(new SynInfo(false), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Headers headers = new Headers();
                headers.put("foo", "bar");
                headers.put("baz", "woo");
                stream.headers(new HeadersInfo(headers, true));
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
