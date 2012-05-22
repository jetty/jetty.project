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
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.junit.Assert;
import org.junit.Test;

public class ReferrerPushStrategyTest extends AbstractHTTPSPDYTest
{
    @Override
    protected SPDYServerConnector newHTTPSPDYServerConnector()
    {
        return new HTTPSPDYServerConnector()
        {
            private final AsyncConnectionFactory defaultAsyncConnectionFactory =
                    new ServerHTTPSPDYAsyncConnectionFactory(SPDY.V2, getByteBufferPool(), getExecutor(), getScheduler(), this, new ReferrerPushStrategy());

            @Override
            protected AsyncConnectionFactory getDefaultAsyncConnectionFactory()
            {
                return defaultAsyncConnectionFactory;
            }
        };
    }

    @Test
    public void testAssociatedResourceIsPushed() throws Exception
    {
        InetSocketAddress address = startHTTPServer(new AbstractHandler()
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
        Session session1 = startClient(address, null);

        final CountDownLatch mainResourceLatch = new CountDownLatch(1);
        Headers mainRequestHeaders = new Headers();
        mainRequestHeaders.put("method", "GET");
        String mainResource = "/index.html";
        mainRequestHeaders.put("url", mainResource);
        mainRequestHeaders.put("version", "HTTP/1.1");
        mainRequestHeaders.put("scheme", "http");
        mainRequestHeaders.put("host", "localhost:" + connector.getLocalPort());
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
        associatedRequestHeaders.put("method", "GET");
        associatedRequestHeaders.put("url", "/style.css");
        associatedRequestHeaders.put("version", "HTTP/1.1");
        associatedRequestHeaders.put("scheme", "http");
        associatedRequestHeaders.put("host", "localhost:" + connector.getLocalPort());
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
        Session session2 = startClient(address, new SessionFrameListener.Adapter()
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
    public void testNestedAssociatedResourceIsPushed() throws Exception
    {
        InetSocketAddress address = startHTTPServer(new AbstractHandler()
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
        Session session1 = startClient(address, null);

        final CountDownLatch mainResourceLatch = new CountDownLatch(1);
        Headers mainRequestHeaders = new Headers();
        mainRequestHeaders.put("method", "GET");
        String mainResource = "/index.html";
        mainRequestHeaders.put("url", mainResource);
        mainRequestHeaders.put("version", "HTTP/1.1");
        mainRequestHeaders.put("scheme", "http");
        mainRequestHeaders.put("host", "localhost:" + connector.getLocalPort());
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
        associatedRequestHeaders.put("method", "GET");
        String associatedResource = "/style.css";
        associatedRequestHeaders.put("url", associatedResource);
        associatedRequestHeaders.put("version", "HTTP/1.1");
        associatedRequestHeaders.put("scheme", "http");
        associatedRequestHeaders.put("host", "localhost:" + connector.getLocalPort());
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
        nestedRequestHeaders.put("method", "GET");
        nestedRequestHeaders.put("url", "/image.gif");
        nestedRequestHeaders.put("version", "HTTP/1.1");
        nestedRequestHeaders.put("scheme", "http");
        nestedRequestHeaders.put("host", "localhost:" + connector.getLocalPort());
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
        Session session2 = startClient(address, new SessionFrameListener.Adapter()
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
        InetSocketAddress address = startHTTPServer(new AbstractHandler()
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
        Session session1 = startClient(address, null);

        final CountDownLatch mainResourceLatch = new CountDownLatch(1);
        Headers mainRequestHeaders = new Headers();
        mainRequestHeaders.put("method", "GET");
        String mainResource = "/index.html";
        mainRequestHeaders.put("url", mainResource);
        mainRequestHeaders.put("version", "HTTP/1.1");
        mainRequestHeaders.put("scheme", "http");
        mainRequestHeaders.put("host", "localhost:" + connector.getLocalPort());
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
        associatedRequestHeaders.put("method", "GET");
        associatedRequestHeaders.put("url", "/home.html");
        associatedRequestHeaders.put("version", "HTTP/1.1");
        associatedRequestHeaders.put("scheme", "http");
        associatedRequestHeaders.put("host", "localhost:" + connector.getLocalPort());
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
        Session session2 = startClient(address, new SessionFrameListener.Adapter()
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
        InetSocketAddress address = startHTTPServer(new AbstractHandler()
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
        Session session1 = startClient(address, null);

        final CountDownLatch mainResourceLatch = new CountDownLatch(1);
        Headers mainRequestHeaders = new Headers();
        mainRequestHeaders.put("method", "GET");
        String mainResource = "/index.html";
        mainRequestHeaders.put("url", mainResource);
        mainRequestHeaders.put("version", "HTTP/1.1");
        mainRequestHeaders.put("scheme", "http");
        mainRequestHeaders.put("host", "localhost:" + connector.getLocalPort());
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
        associatedRequestHeaders.put("method", "GET");
        associatedRequestHeaders.put("url", "/style.css");
        associatedRequestHeaders.put("version", "HTTP/1.1");
        associatedRequestHeaders.put("scheme", "http");
        associatedRequestHeaders.put("host", "localhost:" + connector.getLocalPort());
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
        Session session2 = startClient(address, new SessionFrameListener.Adapter()
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
