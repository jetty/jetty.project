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
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SPDYException;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.junit.Assert;
import org.junit.Test;

public class ConcurrentTest extends AbstractTest
{
    @Test
    public void testConcurrentSyn() throws Exception
    {
        final CountDownLatch slowServerLatch = new CountDownLatch(1);
        final CountDownLatch fastServerLatch = new CountDownLatch(1);
        Session session = startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                try
                {
                    Headers headers = synInfo.getHeaders();
                    String url = headers.get("url").value();
                    switch (url)
                    {
                        case "/slow":
                            Assert.assertTrue(fastServerLatch.await(10, TimeUnit.SECONDS));
                            slowServerLatch.countDown();
                            break;
                        case "/fast":
                            fastServerLatch.countDown();
                            break;
                        default:
                            Assert.fail();
                    }
                    stream.reply(new ReplyInfo(true));
                    return null;
                }
                catch (InterruptedException x)
                {
                    throw new SPDYException(x);
                }
            }
        }), null);

        final CountDownLatch slowClientLatch = new CountDownLatch(1);
        Headers headers1 = new Headers();
        headers1.put("url", "/slow");
        session.syn(new SynInfo(headers1, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                slowClientLatch.countDown();
            }
        });

        final CountDownLatch fastClientLatch = new CountDownLatch(1);
        Headers headers2 = new Headers();
        headers2.put("url", "/fast");
        session.syn(new SynInfo(headers2, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                fastClientLatch.countDown();
            }
        });

        Assert.assertTrue(fastServerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(fastClientLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(slowServerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(slowClientLatch.await(5, TimeUnit.SECONDS));
    }
}
