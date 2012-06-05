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
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.spdy.AsyncConnectionFactory;
import org.eclipse.jetty.spdy.SPDYServerConnector;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.junit.Assert;
import org.junit.Test;

public class ReferrerPushStrategyV2Test extends AbstractHTTPSPDYTest
{
    @Override
    protected SPDYServerConnector newHTTPSPDYServerConnector(short version)
    {
        SPDYServerConnector connector = super.newHTTPSPDYServerConnector(version);
        AsyncConnectionFactory defaultFactory = new ServerHTTPSPDYAsyncConnectionFactory(version, connector.getByteBufferPool(), connector.getExecutor(), connector.getScheduler(), connector, new ReferrerPushStrategy());
        connector.setDefaultAsyncConnectionFactory(defaultFactory);
        return connector;
    }

    @Test
    public void testMaxAssociatedResources() throws Exception
    {
        InetSocketAddress address = startHTTPServer(version(), new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                String url = request.getRequestURI();
                PrintWriter output = response.getWriter();
                if (url.endsWith(".html"))
                    output.print("<html><head/><body>HELLO</body></html>");
                else if (url.endsWith(".css"))
                    output.print("body { background: #FFF; }");
                else if (url.endsWith(".js"))
                    output.print("function(){}();");
                baseRequest.setHandled(true);
            }
        });
        ReferrerPushStrategy pushStrategy = new ReferrerPushStrategy();
        pushStrategy.setMaxAssociatedResources(1);
        AsyncConnectionFactory defaultFactory = new ServerHTTPSPDYAsyncConnectionFactory(version(), connector.getByteBufferPool(), connector.getExecutor(), connector.getScheduler(), connector, pushStrategy);
        connector.setDefaultAsyncConnectionFactory(defaultFactory);

        Session session1 = startClient(version(), address, null);

        final CountDownLatch mainResourceLatch = new CountDownLatch(1);
        Headers mainRequestHeaders = new Headers();
        mainRequestHeaders.put(HTTPSPDYHeader.METHOD.name(version()), "GET");
        String mainResource = "/index.html";
        mainRequestHeaders.put(HTTPSPDYHeader.URI.name(version()), mainResource);
        mainRequestHeaders.put(HTTPSPDYHeader.VERSION.name(version()), "HTTP/1.1");
        mainRequestHeaders.put(HTTPSPDYHeader.SCHEME.name(version()), "http");
        mainRequestHeaders.put(HTTPSPDYHeader.HOST.name(version()), "localhost:" + connector.getLocalPort());
        session1.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainResourceLatch.countDown();
            }
        });
        Assert.assertTrue(mainResourceLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch associatedResourceLatch1 = new CountDownLatch(1);
        Headers associatedRequestHeaders1 = new Headers();
        associatedRequestHeaders1.put(HTTPSPDYHeader.METHOD.name(version()), "GET");
        associatedRequestHeaders1.put(HTTPSPDYHeader.URI.name(version()), "/style.css");
        associatedRequestHeaders1.put(HTTPSPDYHeader.VERSION.name(version()), "HTTP/1.1");
        associatedRequestHeaders1.put(HTTPSPDYHeader.SCHEME.name(version()), "http");
        associatedRequestHeaders1.put(HTTPSPDYHeader.HOST.name(version()), "localhost:" + connector.getLocalPort());
        associatedRequestHeaders1.put("referer", "http://localhost:" + connector.getLocalPort() + mainResource);
        session1.syn(new SynInfo(associatedRequestHeaders1, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    associatedResourceLatch1.countDown();
            }
        });
        Assert.assertTrue(associatedResourceLatch1.await(5, TimeUnit.SECONDS));

        final CountDownLatch associatedResourceLatch2 = new CountDownLatch(1);
        Headers associatedRequestHeaders2 = new Headers();
        associatedRequestHeaders2.put(HTTPSPDYHeader.METHOD.name(version()), "GET");
        associatedRequestHeaders2.put(HTTPSPDYHeader.URI.name(version()), "/application.js");
        associatedRequestHeaders2.put(HTTPSPDYHeader.VERSION.name(version()), "HTTP/1.1");
        associatedRequestHeaders2.put(HTTPSPDYHeader.SCHEME.name(version()), "http");
        associatedRequestHeaders2.put(HTTPSPDYHeader.HOST.name(version()), "localhost:" + connector.getLocalPort());
        associatedRequestHeaders2.put("referer", "http://localhost:" + connector.getLocalPort() + mainResource);
        session1.syn(new SynInfo(associatedRequestHeaders2, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    associatedResourceLatch2.countDown();
            }
        });
        Assert.assertTrue(associatedResourceLatch2.await(5, TimeUnit.SECONDS));

        // Create another client, and perform the same request for the main resource,
        // we expect the css being pushed, but not the js

        final CountDownLatch mainStreamLatch = new CountDownLatch(2);
        final CountDownLatch pushDataLatch = new CountDownLatch(1);
        Session session2 = startClient(version(), address, new SessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Assert.assertTrue(stream.isUnidirectional());
                Assert.assertTrue(synInfo.getHeaders().get(HTTPSPDYHeader.URI.name(version())).value().endsWith(".css"));
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {

                        dataInfo.consume(dataInfo.length());
                        if (dataInfo.isClose())
                            pushDataLatch.countDown();
                    }
                };
            }
        });
        session2.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                mainStreamLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainStreamLatch.countDown();
            }
        });

        Assert.assertTrue(mainStreamLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(pushDataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testAssociatedResourceIsPushed() throws Exception
    {
        InetSocketAddress address = startHTTPServer(version(), new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                String url = request.getRequestURI();
                PrintWriter output = response.getWriter();
                if (url.endsWith(".html"))
                    output.print("<html><head/><body>HELLO</body></html>");
                else if (url.endsWith(".css"))
                    output.print("body { background: #FFF; }");
                baseRequest.setHandled(true);
            }
        });
        Session session1 = startClient(version(), address, null);

        final CountDownLatch mainResourceLatch = new CountDownLatch(1);
        Headers mainRequestHeaders = new Headers();
        mainRequestHeaders.put(HTTPSPDYHeader.METHOD.name(version()), "GET");
        String mainResource = "/index.html";
        mainRequestHeaders.put(HTTPSPDYHeader.URI.name(version()), mainResource);
        mainRequestHeaders.put(HTTPSPDYHeader.VERSION.name(version()), "HTTP/1.1");
        mainRequestHeaders.put(HTTPSPDYHeader.SCHEME.name(version()), "http");
        mainRequestHeaders.put(HTTPSPDYHeader.HOST.name(version()), "localhost:" + connector.getLocalPort());
        session1.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainResourceLatch.countDown();
            }
        });
        Assert.assertTrue(mainResourceLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch associatedResourceLatch = new CountDownLatch(1);
        Headers associatedRequestHeaders = new Headers();
        associatedRequestHeaders.put(HTTPSPDYHeader.METHOD.name(version()), "GET");
        associatedRequestHeaders.put(HTTPSPDYHeader.URI.name(version()), "/style.css");
        associatedRequestHeaders.put(HTTPSPDYHeader.VERSION.name(version()), "HTTP/1.1");
        associatedRequestHeaders.put(HTTPSPDYHeader.SCHEME.name(version()), "http");
        associatedRequestHeaders.put(HTTPSPDYHeader.HOST.name(version()), "localhost:" + connector.getLocalPort());
        associatedRequestHeaders.put("referer", "http://localhost:" + connector.getLocalPort() + mainResource);
        session1.syn(new SynInfo(associatedRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    associatedResourceLatch.countDown();
            }
        });
        Assert.assertTrue(associatedResourceLatch.await(5, TimeUnit.SECONDS));

        // Create another client, and perform the same request for the main resource, we expect the css being pushed

        final CountDownLatch mainStreamLatch = new CountDownLatch(2);
        final CountDownLatch pushDataLatch = new CountDownLatch(1);
        Session session2 = startClient(version(), address, new SessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Assert.assertTrue(stream.isUnidirectional());
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        dataInfo.consume(dataInfo.length());
                        if (dataInfo.isClose())
                            pushDataLatch.countDown();
                    }
                };
            }
        });
        session2.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                mainStreamLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainStreamLatch.countDown();
            }
        });

        Assert.assertTrue(mainStreamLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(pushDataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testAssociatedResourceWithWrongContentTypeIsNotPushed() throws Exception
    {
        InetSocketAddress address = startHTTPServer(version(), new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                String url = request.getRequestURI();
                PrintWriter output = response.getWriter();
                if (url.endsWith(".html"))
                {
                    response.setContentType("text/html");
                    output.print("<html><head/><body>HELLO</body></html>");
                }
                else if (url.equals("/fake.png"))
                {
                    response.setContentType("text/html");
                    output.print("<html><head/><body>IMAGE</body></html>");
                }
                else if (url.endsWith(".css"))
                {
                    response.setContentType("text/css");
                    output.print("body { background: #FFF; }");
                }
                baseRequest.setHandled(true);
            }
        });
        Session session1 = startClient(version(), address, null);

        final CountDownLatch mainResourceLatch = new CountDownLatch(1);
        Headers mainRequestHeaders = new Headers();
        mainRequestHeaders.put(HTTPSPDYHeader.METHOD.name(version()), "GET");
        String mainResource = "/index.html";
        mainRequestHeaders.put(HTTPSPDYHeader.URI.name(version()), mainResource);
        mainRequestHeaders.put(HTTPSPDYHeader.VERSION.name(version()), "HTTP/1.1");
        mainRequestHeaders.put(HTTPSPDYHeader.SCHEME.name(version()), "http");
        mainRequestHeaders.put(HTTPSPDYHeader.HOST.name(version()), "localhost:" + connector.getLocalPort());
        session1.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainResourceLatch.countDown();
            }
        });
        Assert.assertTrue(mainResourceLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch associatedResourceLatch = new CountDownLatch(1);
        Headers associatedRequestHeaders = new Headers();
        associatedRequestHeaders.put(HTTPSPDYHeader.METHOD.name(version()), "GET");
        associatedRequestHeaders.put(HTTPSPDYHeader.URI.name(version()), "/stylesheet.css");
        associatedRequestHeaders.put(HTTPSPDYHeader.VERSION.name(version()), "HTTP/1.1");
        associatedRequestHeaders.put(HTTPSPDYHeader.SCHEME.name(version()), "http");
        associatedRequestHeaders.put(HTTPSPDYHeader.HOST.name(version()), "localhost:" + connector.getLocalPort());
        associatedRequestHeaders.put("referer", "http://localhost:" + connector.getLocalPort() + mainResource);
        session1.syn(new SynInfo(associatedRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    associatedResourceLatch.countDown();
            }
        });
        Assert.assertTrue(associatedResourceLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch fakeAssociatedResourceLatch = new CountDownLatch(1);
        Headers fakeAssociatedRequestHeaders = new Headers();
        fakeAssociatedRequestHeaders.put(HTTPSPDYHeader.METHOD.name(version()), "GET");
        fakeAssociatedRequestHeaders.put(HTTPSPDYHeader.URI.name(version()), "/fake.png");
        fakeAssociatedRequestHeaders.put(HTTPSPDYHeader.VERSION.name(version()), "HTTP/1.1");
        fakeAssociatedRequestHeaders.put(HTTPSPDYHeader.SCHEME.name(version()), "http");
        fakeAssociatedRequestHeaders.put(HTTPSPDYHeader.HOST.name(version()), "localhost:" + connector.getLocalPort());
        fakeAssociatedRequestHeaders.put("referer", "http://localhost:" + connector.getLocalPort() + mainResource);
        session1.syn(new SynInfo(fakeAssociatedRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    fakeAssociatedResourceLatch.countDown();
            }
        });
        Assert.assertTrue(fakeAssociatedResourceLatch.await(5, TimeUnit.SECONDS));

        // Create another client, and perform the same request for the main resource,
        // we expect the css being pushed but not the fake PNG

        final CountDownLatch mainStreamLatch = new CountDownLatch(2);
        final CountDownLatch pushDataLatch = new CountDownLatch(1);
        Session session2 = startClient(version(), address, new SessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Assert.assertTrue(stream.isUnidirectional());
                Assert.assertTrue(synInfo.getHeaders().get(HTTPSPDYHeader.URI.name(version())).value().endsWith(".css"));
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        dataInfo.consume(dataInfo.length());
                        if (dataInfo.isClose())
                            pushDataLatch.countDown();
                    }
                };
            }
        });
        session2.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                mainStreamLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainStreamLatch.countDown();
            }
        });

        Assert.assertTrue(mainStreamLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(pushDataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testNestedAssociatedResourceIsPushed() throws Exception
    {
        InetSocketAddress address = startHTTPServer(version(), new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                String url = request.getRequestURI();
                PrintWriter output = response.getWriter();
                if (url.endsWith(".html"))
                    output.print("<html><head/><body>HELLO</body></html>");
                else if (url.endsWith(".css"))
                    output.print("body { background: #FFF; }");
                else if (url.endsWith(".gif"))
                    output.print("\u0000");
                baseRequest.setHandled(true);
            }
        });
        Session session1 = startClient(version(), address, null);

        final CountDownLatch mainResourceLatch = new CountDownLatch(1);
        Headers mainRequestHeaders = new Headers();
        mainRequestHeaders.put(HTTPSPDYHeader.METHOD.name(version()), "GET");
        String mainResource = "/index.html";
        mainRequestHeaders.put(HTTPSPDYHeader.URI.name(version()), mainResource);
        mainRequestHeaders.put(HTTPSPDYHeader.VERSION.name(version()), "HTTP/1.1");
        mainRequestHeaders.put(HTTPSPDYHeader.SCHEME.name(version()), "http");
        mainRequestHeaders.put(HTTPSPDYHeader.HOST.name(version()), "localhost:" + connector.getLocalPort());
        session1.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainResourceLatch.countDown();
            }
        });
        Assert.assertTrue(mainResourceLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch associatedResourceLatch = new CountDownLatch(1);
        Headers associatedRequestHeaders = new Headers();
        associatedRequestHeaders.put(HTTPSPDYHeader.METHOD.name(version()), "GET");
        String associatedResource = "/style.css";
        associatedRequestHeaders.put(HTTPSPDYHeader.URI.name(version()), associatedResource);
        associatedRequestHeaders.put(HTTPSPDYHeader.VERSION.name(version()), "HTTP/1.1");
        associatedRequestHeaders.put(HTTPSPDYHeader.SCHEME.name(version()), "http");
        associatedRequestHeaders.put(HTTPSPDYHeader.HOST.name(version()), "localhost:" + connector.getLocalPort());
        associatedRequestHeaders.put("referer", "http://localhost:" + connector.getLocalPort() + mainResource);
        session1.syn(new SynInfo(associatedRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    associatedResourceLatch.countDown();
            }
        });
        Assert.assertTrue(associatedResourceLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch nestedResourceLatch = new CountDownLatch(1);
        Headers nestedRequestHeaders = new Headers();
        nestedRequestHeaders.put(HTTPSPDYHeader.METHOD.name(version()), "GET");
        nestedRequestHeaders.put(HTTPSPDYHeader.URI.name(version()), "/image.gif");
        nestedRequestHeaders.put(HTTPSPDYHeader.VERSION.name(version()), "HTTP/1.1");
        nestedRequestHeaders.put(HTTPSPDYHeader.SCHEME.name(version()), "http");
        nestedRequestHeaders.put(HTTPSPDYHeader.HOST.name(version()), "localhost:" + connector.getLocalPort());
        nestedRequestHeaders.put("referer", "http://localhost:" + connector.getLocalPort() + associatedResource);
        session1.syn(new SynInfo(nestedRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    nestedResourceLatch.countDown();
            }
        });
        Assert.assertTrue(nestedResourceLatch.await(5, TimeUnit.SECONDS));

        // Create another client, and perform the same request for the main resource, we expect the css and the image being pushed

        final CountDownLatch mainStreamLatch = new CountDownLatch(2);
        final CountDownLatch pushDataLatch = new CountDownLatch(2);
        Session session2 = startClient(version(), address, new SessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Assert.assertTrue(stream.isUnidirectional());
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        dataInfo.consume(dataInfo.length());
                        if (dataInfo.isClose())
                            pushDataLatch.countDown();
                    }
                };
            }
        });
        session2.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                mainStreamLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainStreamLatch.countDown();
            }
        });

        Assert.assertTrue(mainStreamLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(pushDataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testMainResourceWithReferrerIsNotPushed() throws Exception
    {
        InetSocketAddress address = startHTTPServer(version(), new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                String url = request.getRequestURI();
                PrintWriter output = response.getWriter();
                if (url.endsWith(".html"))
                    output.print("<html><head/><body>HELLO</body></html>");
                baseRequest.setHandled(true);
            }
        });
        Session session1 = startClient(version(), address, null);

        final CountDownLatch mainResourceLatch = new CountDownLatch(1);
        Headers mainRequestHeaders = new Headers();
        mainRequestHeaders.put(HTTPSPDYHeader.METHOD.name(version()), "GET");
        String mainResource = "/index.html";
        mainRequestHeaders.put(HTTPSPDYHeader.URI.name(version()), mainResource);
        mainRequestHeaders.put(HTTPSPDYHeader.VERSION.name(version()), "HTTP/1.1");
        mainRequestHeaders.put(HTTPSPDYHeader.SCHEME.name(version()), "http");
        mainRequestHeaders.put(HTTPSPDYHeader.HOST.name(version()), "localhost:" + connector.getLocalPort());
        session1.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainResourceLatch.countDown();
            }
        });
        Assert.assertTrue(mainResourceLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch associatedResourceLatch = new CountDownLatch(1);
        Headers associatedRequestHeaders = new Headers();
        associatedRequestHeaders.put(HTTPSPDYHeader.METHOD.name(version()), "GET");
        associatedRequestHeaders.put(HTTPSPDYHeader.URI.name(version()), "/home.html");
        associatedRequestHeaders.put(HTTPSPDYHeader.VERSION.name(version()), "HTTP/1.1");
        associatedRequestHeaders.put(HTTPSPDYHeader.SCHEME.name(version()), "http");
        associatedRequestHeaders.put(HTTPSPDYHeader.HOST.name(version()), "localhost:" + connector.getLocalPort());
        associatedRequestHeaders.put("referer", "http://localhost:" + connector.getLocalPort() + mainResource);
        session1.syn(new SynInfo(associatedRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    associatedResourceLatch.countDown();
            }
        });
        Assert.assertTrue(associatedResourceLatch.await(5, TimeUnit.SECONDS));

        // Create another client, and perform the same request for the main resource, we expect nothing being pushed

        final CountDownLatch mainStreamLatch = new CountDownLatch(2);
        final CountDownLatch pushLatch = new CountDownLatch(1);
        Session session2 = startClient(version(), address, new SessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                pushLatch.countDown();
                return null;
            }
        });
        session2.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                mainStreamLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainStreamLatch.countDown();
            }
        });

        Assert.assertTrue(mainStreamLatch.await(5, TimeUnit.SECONDS));
        Assert.assertFalse(pushLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testRequestWithIfModifiedSinceHeaderPreventsPush() throws Exception
    {
        InetSocketAddress address = startHTTPServer(version(), new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                String url = request.getRequestURI();
                PrintWriter output = response.getWriter();
                if (url.endsWith(".html"))
                    output.print("<html><head/><body>HELLO</body></html>");
                else if (url.endsWith(".css"))
                    output.print("body { background: #FFF; }");
                baseRequest.setHandled(true);
            }
        });
        Session session1 = startClient(version(), address, null);

        final CountDownLatch mainResourceLatch = new CountDownLatch(1);
        Headers mainRequestHeaders = new Headers();
        mainRequestHeaders.put(HTTPSPDYHeader.METHOD.name(version()), "GET");
        String mainResource = "/index.html";
        mainRequestHeaders.put(HTTPSPDYHeader.URI.name(version()), mainResource);
        mainRequestHeaders.put(HTTPSPDYHeader.VERSION.name(version()), "HTTP/1.1");
        mainRequestHeaders.put(HTTPSPDYHeader.SCHEME.name(version()), "http");
        mainRequestHeaders.put(HTTPSPDYHeader.HOST.name(version()), "localhost:" + connector.getLocalPort());
        mainRequestHeaders.put("If-Modified-Since", "Tue, 27 Mar 2012 16:36:52 GMT");
        session1.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainResourceLatch.countDown();
            }
        });
        Assert.assertTrue(mainResourceLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch associatedResourceLatch = new CountDownLatch(1);
        Headers associatedRequestHeaders = new Headers();
        associatedRequestHeaders.put(HTTPSPDYHeader.METHOD.name(version()), "GET");
        associatedRequestHeaders.put(HTTPSPDYHeader.URI.name(version()), "/style.css");
        associatedRequestHeaders.put(HTTPSPDYHeader.VERSION.name(version()), "HTTP/1.1");
        associatedRequestHeaders.put(HTTPSPDYHeader.SCHEME.name(version()), "http");
        associatedRequestHeaders.put(HTTPSPDYHeader.HOST.name(version()), "localhost:" + connector.getLocalPort());
        associatedRequestHeaders.put("referer", "http://localhost:" + connector.getLocalPort() + mainResource);
        session1.syn(new SynInfo(associatedRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    associatedResourceLatch.countDown();
            }
        });
        Assert.assertTrue(associatedResourceLatch.await(5, TimeUnit.SECONDS));

        // Create another client, and perform the same request for the main resource, we expect the css NOT being pushed as the main request contains an
        // if-modified-since header

        final CountDownLatch mainStreamLatch = new CountDownLatch(2);
        final CountDownLatch pushDataLatch = new CountDownLatch(1);
        Session session2 = startClient(version(), address, new SessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                Assert.assertTrue(stream.isUnidirectional());
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        dataInfo.consume(dataInfo.length());
                        if (dataInfo.isClose())
                            pushDataLatch.countDown();
                    }
                };
            }
        });
        session2.syn(new SynInfo(mainRequestHeaders, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Assert.assertFalse(replyInfo.isClose());
                mainStreamLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    mainStreamLatch.countDown();
            }
        });

        Assert.assertTrue(mainStreamLatch.await(5, TimeUnit.SECONDS));
        Assert.assertFalse("We don't expect data to be pushed as the main request contained an if-modified-since header",pushDataLatch.await(1, TimeUnit.SECONDS));
    }
}
