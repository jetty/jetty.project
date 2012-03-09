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

package org.eclipse.jetty.spdy.http;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.junit.Assert;
import org.junit.Test;

public class ConcurrentStreamsTest extends AbstractHTTPSPDYTest
{
    @Test
    public void testSlowStreamDoesNotBlockOtherStreams() throws Exception
    {
        final CountDownLatch slowServerLatch = new CountDownLatch(1);
        final CountDownLatch fastServerLatch = new CountDownLatch(1);
        Session session = startClient(startHTTPServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                try
                {
                    request.setHandled(true);
                    switch (target)
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
                            break;
                    }
                }
                catch (InterruptedException x)
                {
                    throw new ServletException(x);
                }
            }
        }), null);

        // Perform slow request. This will wait on server side until the fast request wakes it up
        Headers headers = new Headers();
        headers.put("method", "GET");
        headers.put("url", "/slow");
        headers.put("version", "HTTP/1.1");
        headers.put("host", "localhost:" + connector.getLocalPort());
        final CountDownLatch slowClientLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                slowClientLatch.countDown();
            }
        });

        // Perform the fast request. This will wake up the slow request
        headers.clear();
        headers.put("method", "GET");
        headers.put("url", "/fast");
        headers.put("version", "HTTP/1.1");
        headers.put("host", "localhost:" + connector.getLocalPort());
        final CountDownLatch fastClientLatch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                fastClientLatch.countDown();
            }
        });

        Assert.assertTrue(fastServerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(slowServerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(fastClientLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(slowClientLatch.await(5, TimeUnit.SECONDS));
    }
}
