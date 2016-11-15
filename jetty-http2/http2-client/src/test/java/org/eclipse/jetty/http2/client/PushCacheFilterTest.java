//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlets.PushCacheFilter;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.junit.Assert;
import org.junit.Test;

public class PushCacheFilterTest extends AbstractTest
{
    @Override
    protected void customizeContext(ServletContextHandler context)
    {
        context.addFilter(PushCacheFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
    }

    @Test
    public void testPush() throws Exception
    {
        final String primaryResource = "/primary.html";
        final String secondaryResource = "/secondary.png";
        final byte[] secondaryData = "SECONDARY".getBytes("UTF-8");
        start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                String requestURI = req.getRequestURI();
                ServletOutputStream output = resp.getOutputStream();
                if (requestURI.endsWith(primaryResource))
                    output.print("<html><head></head><body>PRIMARY</body></html>");
                else if (requestURI.endsWith(secondaryResource))
                    output.write(secondaryData);
            }
        });

        final Session session = newClient(new Session.Listener.Adapter());

        // Request for the primary and secondary resource to build the cache.
        final String referrerURI = "http://localhost:" + connector.getLocalPort() + servletPath + primaryResource;
        HttpFields primaryFields = new HttpFields();
        MetaData.Request primaryRequest = newRequest("GET", primaryResource, primaryFields);
        final CountDownLatch warmupLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(primaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                if (frame.isEndStream())
                {
                    // Request for the secondary resource.
                    HttpFields secondaryFields = new HttpFields();
                    secondaryFields.put(HttpHeader.REFERER, referrerURI);
                    MetaData.Request secondaryRequest = newRequest("GET", secondaryResource, secondaryFields);
                    session.newStream(new HeadersFrame(secondaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
                    {
                        @Override
                        public void onData(Stream stream, DataFrame frame, Callback callback)
                        {
                            callback.succeeded();
                            warmupLatch.countDown();
                        }
                    });
                }
            }
        });
        Assert.assertTrue(warmupLatch.await(5, TimeUnit.SECONDS));

        // Request again the primary resource, we should get the secondary resource pushed.
        primaryRequest = newRequest("GET", primaryResource, primaryFields);
        final CountDownLatch primaryResponseLatch = new CountDownLatch(1);
        final CountDownLatch pushLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(primaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public Stream.Listener onPush(Stream stream, PushPromiseFrame frame)
            {
                return new Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        callback.succeeded();
                        if (frame.isEndStream())
                            pushLatch.countDown();
                    }
                };
            }

            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                if (frame.isEndStream())
                    primaryResponseLatch.countDown();
            }
        });
        Assert.assertTrue(pushLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(primaryResponseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPushReferrerNoPath() throws Exception
    {
        final String primaryResource = "/primary.html";
        final String secondaryResource = "/secondary.png";
        final byte[] secondaryData = "SECONDARY".getBytes("UTF-8");
        start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                String requestURI = req.getRequestURI();
                ServletOutputStream output = resp.getOutputStream();
                if (requestURI.endsWith(primaryResource))
                    output.print("<html><head></head><body>PRIMARY</body></html>");
                else if (requestURI.endsWith(secondaryResource))
                    output.write(secondaryData);
            }
        });

        final Session session = newClient(new Session.Listener.Adapter());

        // Request for the primary and secondary resource to build the cache.
        // The referrerURI does not point to the primary resource, so there will be no
        // resource association with the primary resource and therefore won't be pushed.
        final String referrerURI = "http://localhost:" + connector.getLocalPort();
        HttpFields primaryFields = new HttpFields();
        MetaData.Request primaryRequest = newRequest("GET", primaryResource, primaryFields);
        final CountDownLatch warmupLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(primaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                if (frame.isEndStream())
                {
                    // Request for the secondary resource.
                    HttpFields secondaryFields = new HttpFields();
                    secondaryFields.put(HttpHeader.REFERER, referrerURI);
                    MetaData.Request secondaryRequest = newRequest("GET", secondaryResource, secondaryFields);
                    session.newStream(new HeadersFrame(secondaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
                    {
                        @Override
                        public void onData(Stream stream, DataFrame frame, Callback callback)
                        {
                            callback.succeeded();
                            warmupLatch.countDown();
                        }
                    });
                }
            }
        });
        Assert.assertTrue(warmupLatch.await(5, TimeUnit.SECONDS));

        // Request again the primary resource, we should not get the secondary resource pushed.
        primaryRequest = newRequest("GET", primaryResource, primaryFields);
        final CountDownLatch primaryResponseLatch = new CountDownLatch(1);
        final CountDownLatch pushLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(primaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public Stream.Listener onPush(Stream stream, PushPromiseFrame frame)
            {
                return new Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        callback.succeeded();
                        if (frame.isEndStream())
                            pushLatch.countDown();
                    }
                };
            }

            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                if (frame.isEndStream())
                    primaryResponseLatch.countDown();
            }
        });
        Assert.assertFalse(pushLatch.await(1, TimeUnit.SECONDS));
        Assert.assertTrue(primaryResponseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPushIsReset() throws Exception
    {
        final String primaryResource = "/primary.html";
        final String secondaryResource = "/secondary.png";
        final byte[] secondaryData = "SECONDARY".getBytes("UTF-8");
        start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                String requestURI = req.getRequestURI();
                ServletOutputStream output = resp.getOutputStream();
                if (requestURI.endsWith(primaryResource))
                    output.print("<html><head></head><body>PRIMARY</body></html>");
                else if (requestURI.endsWith(secondaryResource))
                    output.write(secondaryData);
            }
        });

        final Session session = newClient(new Session.Listener.Adapter());

        // Request for the primary and secondary resource to build the cache.
        final String primaryURI = "http://localhost:" + connector.getLocalPort() + servletPath + primaryResource;
        HttpFields primaryFields = new HttpFields();
        MetaData.Request primaryRequest = newRequest("GET", primaryResource, primaryFields);
        final CountDownLatch warmupLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(primaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                if (frame.isEndStream())
                {
                    // Request for the secondary resource.
                    HttpFields secondaryFields = new HttpFields();
                    secondaryFields.put(HttpHeader.REFERER, primaryURI);
                    MetaData.Request secondaryRequest = newRequest("GET", secondaryResource, secondaryFields);
                    session.newStream(new HeadersFrame(secondaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
                    {
                        @Override
                        public void onData(Stream stream, DataFrame frame, Callback callback)
                        {
                            callback.succeeded();
                            warmupLatch.countDown();
                        }
                    });
                }
            }
        });
        Assert.assertTrue(warmupLatch.await(5, TimeUnit.SECONDS));

        // Request again the primary resource, we should get the secondary resource pushed.
        primaryRequest = newRequest("GET", primaryResource, primaryFields);
        final CountDownLatch primaryResponseLatch = new CountDownLatch(1);
        final CountDownLatch pushLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(primaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public Stream.Listener onPush(Stream stream, PushPromiseFrame frame)
            {
                // Reset the stream as soon as we see the push.
                ResetFrame resetFrame = new ResetFrame(stream.getId(), ErrorCode.REFUSED_STREAM_ERROR.code);
                stream.reset(resetFrame, Callback.NOOP);
                return new Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        callback.succeeded();
                        pushLatch.countDown();
                    }
                };
            }

            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                if (frame.isEndStream())
                    primaryResponseLatch.countDown();
            }
        });
        // We should not receive pushed data that we reset.
        Assert.assertFalse(pushLatch.await(1, TimeUnit.SECONDS));
        Assert.assertTrue(primaryResponseLatch.await(5, TimeUnit.SECONDS));

        // Make sure the session is sane by requesting the secondary resource.
        HttpFields secondaryFields = new HttpFields();
        secondaryFields.put(HttpHeader.REFERER, primaryURI);
        MetaData.Request secondaryRequest = newRequest("GET", secondaryResource, secondaryFields);
        final CountDownLatch secondaryResponseLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(secondaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                if (frame.isEndStream())
                    secondaryResponseLatch.countDown();
            }
        });
        Assert.assertTrue(secondaryResponseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPushWithoutPrimaryResponseContent() throws Exception
    {
        final String primaryResource = "/primary.html";
        final String secondaryResource = "/secondary.png";
        start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                String requestURI = request.getRequestURI();
                final ServletOutputStream output = response.getOutputStream();
                if (requestURI.endsWith(secondaryResource))
                    output.write("SECONDARY".getBytes(StandardCharsets.UTF_8));
            }
        });

        final Session session = newClient(new Session.Listener.Adapter());

        // Request for the primary and secondary resource to build the cache.
        final String primaryURI = "http://localhost:" + connector.getLocalPort() + servletPath + primaryResource;
        HttpFields primaryFields = new HttpFields();
        MetaData.Request primaryRequest = newRequest("GET", primaryResource, primaryFields);
        final CountDownLatch warmupLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(primaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                {
                    // Request for the secondary resource.
                    HttpFields secondaryFields = new HttpFields();
                    secondaryFields.put(HttpHeader.REFERER, primaryURI);
                    MetaData.Request secondaryRequest = newRequest("GET", secondaryResource, secondaryFields);
                    session.newStream(new HeadersFrame(secondaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
                    {
                        @Override
                        public void onData(Stream stream, DataFrame frame, Callback callback)
                        {
                            callback.succeeded();
                            warmupLatch.countDown();
                        }
                    });
                }
            }
        });
        Assert.assertTrue(warmupLatch.await(5, TimeUnit.SECONDS));

        Thread.sleep(1000);

        // Request again the primary resource, we should get the secondary resource pushed.
        primaryRequest = newRequest("GET", primaryResource, primaryFields);
        final CountDownLatch primaryResponseLatch = new CountDownLatch(1);
        final CountDownLatch pushLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(primaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    primaryResponseLatch.countDown();
            }

            @Override
            public Stream.Listener onPush(Stream stream, PushPromiseFrame frame)
            {
                return new Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        callback.succeeded();
                        if (frame.isEndStream())
                            pushLatch.countDown();
                    }
                };
            }
        });
        Assert.assertTrue(pushLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(primaryResponseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRecursivePush() throws Exception
    {
        final String primaryResource = "/primary.html";
        final String secondaryResource1 = "/secondary1.css";
        final String secondaryResource2 = "/secondary2.js";
        final String tertiaryResource = "/tertiary.png";
        start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                String requestURI = request.getRequestURI();
                final ServletOutputStream output = response.getOutputStream();
                if (requestURI.endsWith(primaryResource))
                    output.print("<html><head></head><body>PRIMARY</body></html>");
                else if (requestURI.endsWith(secondaryResource1))
                    output.print("body { background-image: url(\"" + tertiaryResource + "\"); }");
                else if (requestURI.endsWith(secondaryResource2))
                    output.print("(function() { window.alert('HTTP/2'); })()");
                if (requestURI.endsWith(tertiaryResource))
                    output.write("TERTIARY".getBytes(StandardCharsets.UTF_8));
            }
        });

        final Session session = newClient(new Session.Listener.Adapter());

        // Request for the primary, secondary and tertiary resource to build the cache.
        final String primaryURI = "http://localhost:" + connector.getLocalPort() + servletPath + primaryResource;
        HttpFields primaryFields = new HttpFields();
        MetaData.Request primaryRequest = newRequest("GET", primaryResource, primaryFields);
        final CountDownLatch warmupLatch = new CountDownLatch(2);
        session.newStream(new HeadersFrame(primaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                if (frame.isEndStream())
                {
                    // Request for the secondary resources.
                    String secondaryURI1 = "http://localhost:" + connector.getLocalPort() + servletPath + secondaryResource1;
                    HttpFields secondaryFields1 = new HttpFields();
                    secondaryFields1.put(HttpHeader.REFERER, primaryURI);
                    MetaData.Request secondaryRequest1 = newRequest("GET", secondaryResource1, secondaryFields1);
                    session.newStream(new HeadersFrame(secondaryRequest1, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
                    {
                        @Override
                        public void onData(Stream stream, DataFrame frame, Callback callback)
                        {
                            callback.succeeded();
                            if (frame.isEndStream())
                            {
                                // Request for the tertiary resource.
                                HttpFields tertiaryFields = new HttpFields();
                                tertiaryFields.put(HttpHeader.REFERER, secondaryURI1);
                                MetaData.Request tertiaryRequest = newRequest("GET", tertiaryResource, tertiaryFields);
                                session.newStream(new HeadersFrame(tertiaryRequest, null, true), new Promise.Adapter<>(), new Adapter()
                                {
                                    @Override
                                    public void onData(Stream stream, DataFrame frame, Callback callback)
                                    {
                                        callback.succeeded();
                                        if (frame.isEndStream())
                                            warmupLatch.countDown();
                                    }
                                });
                            }
                        }
                    });

                    HttpFields secondaryFields2 = new HttpFields();
                    secondaryFields2.put(HttpHeader.REFERER, primaryURI);
                    MetaData.Request secondaryRequest2 = newRequest("GET", secondaryResource2, secondaryFields2);
                    session.newStream(new HeadersFrame(secondaryRequest2, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
                    {
                        @Override
                        public void onData(Stream stream, DataFrame frame, Callback callback)
                        {
                            callback.succeeded();
                            if (frame.isEndStream())
                                warmupLatch.countDown();
                        }
                    });
                }
            }
        });
        Assert.assertTrue(warmupLatch.await(5, TimeUnit.SECONDS));

        Thread.sleep(1000);

        // Request again the primary resource, we should get the secondary and tertiary resources pushed.
        primaryRequest = newRequest("GET", primaryResource, primaryFields);
        final CountDownLatch primaryResponseLatch = new CountDownLatch(1);
        final CountDownLatch primaryPushesLatch = new CountDownLatch(3);
        final CountDownLatch recursiveLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(primaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                if (frame.isEndStream())
                    primaryResponseLatch.countDown();
            }

            @Override
            public Stream.Listener onPush(Stream stream, PushPromiseFrame frame)
            {
                // The stream id of the PUSH_PROMISE must
                // always be a client stream and therefore odd.
                Assert.assertEquals(1, frame.getStreamId() & 1);
                return new Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        callback.succeeded();
                        if (frame.isEndStream())
                            primaryPushesLatch.countDown();
                    }

                    @Override
                    public Stream.Listener onPush(Stream stream, PushPromiseFrame frame)
                    {
                        return new Adapter()
                        {
                            @Override
                            public void onData(Stream stream, DataFrame frame, Callback callback)
                            {
                                callback.succeeded();
                                if (frame.isEndStream())
                                    recursiveLatch.countDown();
                            }
                        };
                    }
                };
            }
        });

        Assert.assertTrue(primaryPushesLatch.await(5, TimeUnit.SECONDS));
        Assert.assertFalse(recursiveLatch.await(1, TimeUnit.SECONDS));
        Assert.assertTrue(primaryResponseLatch.await(5, TimeUnit.SECONDS));

        // Make sure that explicitly requesting a secondary resource, we get the tertiary pushed.
        CountDownLatch secondaryResponseLatch = new CountDownLatch(1);
        CountDownLatch secondaryPushLatch = new CountDownLatch(1);
        MetaData.Request secondaryRequest = newRequest("GET", secondaryResource1, new HttpFields());
        session.newStream(new HeadersFrame(secondaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                if (frame.isEndStream())
                    secondaryResponseLatch.countDown();
            }

            @Override
            public Stream.Listener onPush(Stream stream, PushPromiseFrame frame)
            {
                return new Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        callback.succeeded();
                        if (frame.isEndStream())
                            secondaryPushLatch.countDown();
                    }
                };
            }
        });

        Assert.assertTrue(secondaryPushLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(secondaryResponseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSelfPush() throws Exception
    {
        // The test case is that of a login page, for example.
        // When the user sends the credentials to the login page,
        // the login may fail and redirect to the same login page,
        // perhaps with different query parameters.
        // In this case a request for the login page will push
        // the login page itself, which will generate the pushed
        // request for the login page, which will push the login
        // page itself, etc. which is not the desired behavior.

        final String primaryResource = "/login.html";
        start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                ServletOutputStream output = response.getOutputStream();
                String credentials = request.getParameter("credentials");
                if (credentials == null)
                {
                    output.print("<html><head></head><body>LOGIN</body></html>");
                }
                else if ("secret".equals(credentials))
                {
                    output.print("<html><head></head><body>OK</body></html>");
                }
                else
                {
                    response.setStatus(HttpStatus.TEMPORARY_REDIRECT_307);
                    response.setHeader(HttpHeader.LOCATION.asString(), primaryResource);
                }
            }
        });
        final String primaryURI = "http://localhost:" + connector.getLocalPort() + servletPath + primaryResource;

        final Session session = newClient(new Session.Listener.Adapter());

        // Login with the wrong credentials, causing a redirect to self.
        HttpFields primaryFields = new HttpFields();
        MetaData.Request primaryRequest = newRequest("GET", primaryResource + "?credentials=wrong", primaryFields);
        final CountDownLatch warmupLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(primaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                {
                    MetaData.Response response = (MetaData.Response)frame.getMetaData();
                    if (response.getStatus() == HttpStatus.TEMPORARY_REDIRECT_307)
                    {
                        // Follow the redirect.
                        String location = response.getFields().get(HttpHeader.LOCATION);
                        HttpFields redirectFields = new HttpFields();
                        redirectFields.put(HttpHeader.REFERER, primaryURI);
                        MetaData.Request redirectRequest = newRequest("GET", location, redirectFields);
                        session.newStream(new HeadersFrame(redirectRequest, null, true), new Promise.Adapter<>(), new Adapter()
                        {
                            @Override
                            public void onData(Stream stream, DataFrame frame, Callback callback)
                            {
                                callback.succeeded();
                                if (frame.isEndStream())
                                    warmupLatch.countDown();
                            }
                        });
                    }
                }
            }
        });
        Assert.assertTrue(warmupLatch.await(5, TimeUnit.SECONDS));

        Thread.sleep(1000);

        // Login with the right credentials, there must be no push.
        primaryRequest = newRequest("GET", primaryResource + "?credentials=secret", primaryFields);
        final CountDownLatch primaryResponseLatch = new CountDownLatch(1);
        final CountDownLatch pushLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(primaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                if (frame.isEndStream())
                    primaryResponseLatch.countDown();
            }

            @Override
            public Stream.Listener onPush(Stream stream, PushPromiseFrame frame)
            {
                pushLatch.countDown();
                return null;
            }
        });
        Assert.assertFalse(pushLatch.await(1, TimeUnit.SECONDS));
        Assert.assertTrue(primaryResponseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPushWithQueryParameters() throws Exception
    {
        String name = "foo";
        String value = "bar";
        final String primaryResource = "/primary.html";
        final String secondaryResource = "/secondary.html?" + name + "=" + value;
        start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                String requestURI = request.getRequestURI();
                if (requestURI.endsWith(primaryResource))
                {
                    response.setStatus(HttpStatus.OK_200);
                }
                else if (requestURI.endsWith(secondaryResource))
                {
                    String param = request.getParameter(name);
                    if (param == null)
                        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
                    else
                        response.setStatus(HttpStatus.OK_200);
                }
            }
        });

        final Session session = newClient(new Session.Listener.Adapter());

        // Request for the primary and secondary resource to build the cache.
        final String primaryURI = "http://localhost:" + connector.getLocalPort() + servletPath + primaryResource;
        HttpFields primaryFields = new HttpFields();
        MetaData.Request primaryRequest = newRequest("GET", primaryResource, primaryFields);
        final CountDownLatch warmupLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(primaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                {
                    // Request for the secondary resource.
                    HttpFields secondaryFields = new HttpFields();
                    secondaryFields.put(HttpHeader.REFERER, primaryURI);
                    MetaData.Request secondaryRequest = newRequest("GET", secondaryResource, secondaryFields);
                    session.newStream(new HeadersFrame(secondaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
                    {
                        @Override
                        public void onHeaders(Stream stream, HeadersFrame frame)
                        {
                            if (frame.isEndStream())
                                warmupLatch.countDown();
                        }
                    });
                }
            }
        });
        Assert.assertTrue(warmupLatch.await(5, TimeUnit.SECONDS));

        Thread.sleep(1000);

        // Request again the primary resource, we should get the secondary resource pushed.
        primaryRequest = newRequest("GET", primaryResource, primaryFields);
        final CountDownLatch primaryResponseLatch = new CountDownLatch(1);
        final CountDownLatch pushLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(primaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public Stream.Listener onPush(Stream stream, PushPromiseFrame frame)
            {
                MetaData metaData = frame.getMetaData();
                Assert.assertTrue(metaData instanceof MetaData.Request);
                MetaData.Request pushedRequest = (MetaData.Request)metaData;
                Assert.assertEquals(servletPath + secondaryResource, pushedRequest.getURI().getPathQuery());
                return new Adapter()
                {
                    @Override
                    public void onHeaders(Stream stream, HeadersFrame frame)
                    {
                        if (frame.isEndStream())
                        {
                            MetaData.Response response = (MetaData.Response)frame.getMetaData();
                            if (response.getStatus() == HttpStatus.OK_200)
                                pushLatch.countDown();
                        }
                    }
                };
            }

            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    primaryResponseLatch.countDown();
            }
        });
        Assert.assertTrue(pushLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(primaryResponseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPOSTRequestIsNotPushed() throws Exception
    {
        final String primaryResource = "/primary.html";
        final String secondaryResource = "/secondary.png";
        final byte[] secondaryData = "SECONDARY".getBytes("UTF-8");
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                String requestURI = req.getRequestURI();
                ServletOutputStream output = resp.getOutputStream();
                if (requestURI.endsWith(primaryResource))
                    output.print("<html><head></head><body>PRIMARY</body></html>");
                else if (requestURI.endsWith(secondaryResource))
                    output.write(secondaryData);
            }
        });

        final Session session = newClient(new Session.Listener.Adapter());

        // Request for the primary and secondary resource to build the cache.
        final String referrerURI = "http://localhost:" + connector.getLocalPort() + servletPath + primaryResource;
        HttpFields primaryFields = new HttpFields();
        MetaData.Request primaryRequest = newRequest("GET", primaryResource, primaryFields);
        final CountDownLatch warmupLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(primaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                if (frame.isEndStream())
                {
                    // Request for the secondary resource.
                    HttpFields secondaryFields = new HttpFields();
                    secondaryFields.put(HttpHeader.REFERER, referrerURI);
                    MetaData.Request secondaryRequest = newRequest("GET", secondaryResource, secondaryFields);
                    session.newStream(new HeadersFrame(secondaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
                    {
                        @Override
                        public void onData(Stream stream, DataFrame frame, Callback callback)
                        {
                            callback.succeeded();
                            warmupLatch.countDown();
                        }
                    });
                }
            }
        });
        Assert.assertTrue(warmupLatch.await(5, TimeUnit.SECONDS));

        // Request again the primary resource with POST, we should not get the secondary resource pushed.
        primaryRequest = newRequest("POST", primaryResource, primaryFields);
        final CountDownLatch primaryResponseLatch = new CountDownLatch(1);
        final CountDownLatch pushLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(primaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public Stream.Listener onPush(Stream stream, PushPromiseFrame frame)
            {
                return new Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        callback.succeeded();
                        if (frame.isEndStream())
                            pushLatch.countDown();
                    }
                };
            }

            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                if (frame.isEndStream())
                    primaryResponseLatch.countDown();
            }
        });
        Assert.assertTrue(primaryResponseLatch.await(5, TimeUnit.SECONDS));
        Assert.assertFalse(pushLatch.await(1, TimeUnit.SECONDS));
    }
}
