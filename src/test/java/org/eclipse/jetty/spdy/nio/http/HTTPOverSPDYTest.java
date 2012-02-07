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

package org.eclipse.jetty.spdy.nio.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.nio.SPDYClient;
import org.eclipse.jetty.spdy.nio.SPDYServerConnector;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class HTTPOverSPDYTest
{
    private Server server;
    private SPDYServerConnector connector;
    private SPDYClient.Factory clientFactory;
    private Session session;

    public void start(Handler handler, Session.FrameListener listener) throws Exception
    {
        server = new Server();
        connector = new SPDYServerConnector(null);
        server.addConnector(connector);
        connector.putAsyncConnectionFactory("spdy/2", new ServerHTTP11OverSPDY2AsyncConnectionFactory(connector));
        server.setHandler(handler);
        server.start();

        clientFactory = new SPDYClient.Factory();
        clientFactory.start();
        SPDYClient client = clientFactory.newSPDYClient();
        session = client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), listener).get(5, TimeUnit.SECONDS);
    }

    @After
    public void stop() throws Exception
    {
        session.goAway((short)2);
        clientFactory.stop();
        clientFactory.join();
        server.stop();
        server.join();
    }

    @Test
    public void testSimpleGET() throws Exception
    {
        final String path = "/foo";
        final CountDownLatch handlerLatch = new CountDownLatch(1);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                Assert.assertEquals(path, target);
                Assert.assertEquals(httpRequest.getRequestURI(), path);
                Assert.assertEquals(httpRequest.getHeader("host"), "localhost:" + connector.getLocalPort());
                handlerLatch.countDown();
            }
        }, null);

        Headers headers = new Headers();
        headers.put("method", "GET");
        headers.put("url", "http://localhost:" + connector.getLocalPort() + path);
        headers.put("version", "HTTP/1.1");
        final CountDownLatch replyLatch = new CountDownLatch(1);
        session.syn((short)2, new SynInfo(headers, true), new Stream.FrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertTrue(replyInfo.isClose());
                Headers replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get("status").value().contains("200"));
                replyLatch.countDown();
            }
        });
        Assert.assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));
    }
}
