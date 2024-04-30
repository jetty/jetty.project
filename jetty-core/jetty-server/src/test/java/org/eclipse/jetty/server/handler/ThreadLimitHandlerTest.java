//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ThreadLimitHandlerTest
{
    private Server _server;
    private NetworkConnector _connector;
    private LocalConnector _local;

    @BeforeEach
    public void before()
        throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _local = new LocalConnector(_server);
        _server.setConnectors(new Connector[]{_local, _connector});
    }

    @AfterEach
    public void after()
        throws Exception
    {
        _server.stop();
    }

    @Test
    public void testNoForwardHeaders() throws Exception
    {
        AtomicReference<String> last = new AtomicReference<>();
        ThreadLimitHandler handler = new ThreadLimitHandler(null, false)
        {
            @Override
            protected int getThreadLimit(String ip)
            {
                last.set(ip);
                return super.getThreadLimit(ip);
            }
        };
        handler.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.setStatus(HttpStatus.OK_200);
                callback.succeeded();
                return true;
            }
        });
        _server.setHandler(handler);
        _server.start();

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\n\r\n");
        assertThat(last.get(), is("0.0.0.0"));

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nX-Forwarded-For: 1.2.3.4\r\n\r\n");
        assertThat(last.get(), is("0.0.0.0"));

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nForwarded: for=1.2.3.4\r\n\r\n");
        assertThat(last.get(), is("0.0.0.0"));

        assertThat(handler.getRemoteCount(), is(0));
    }

    @Test
    public void testXForwardForHeaders() throws Exception
    {
        AtomicReference<String> last = new AtomicReference<>();
        ThreadLimitHandler handler = new ThreadLimitHandler("X-Forwarded-For")
        {
            @Override
            protected int getThreadLimit(String ip)
            {
                last.set(ip);
                return super.getThreadLimit(ip);
            }
        };
        handler.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.setStatus(HttpStatus.OK_200);
                callback.succeeded();
                return true;
            }
        });
        _server.setHandler(handler);
        _server.start();

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\n\r\n");
        assertThat(last.get(), is("0.0.0.0"));

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nX-Forwarded-For: 1.2.3.4\r\n\r\n");
        assertThat(last.get(), is("1.2.3.4"));

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nForwarded: for=1.2.3.4\r\n\r\n");
        assertThat(last.get(), is("0.0.0.0"));

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nX-Forwarded-For: 1.1.1.1\r\nX-Forwarded-For: 6.6.6.6,1.2.3.4\r\nForwarded: for=1.2.3.4\r\n\r\n");
        assertThat(last.get(), is("1.2.3.4"));

        assertThat(handler.getRemoteCount(), is(0));
    }

    @Test
    public void testForwardHeaders() throws Exception
    {
        AtomicReference<String> last = new AtomicReference<>();
        ThreadLimitHandler handler = new ThreadLimitHandler("Forwarded")
        {
            @Override
            protected int getThreadLimit(String ip)
            {
                last.set(ip);
                return super.getThreadLimit(ip);
            }
        };
        handler.setHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.setStatus(HttpStatus.OK_200);
                callback.succeeded();
                return true;
            }
        });
        _server.setHandler(handler);
        _server.start();

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\n\r\n");
        assertThat(last.get(), is("0.0.0.0"));

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nX-Forwarded-For: 1.2.3.4\r\n\r\n");
        assertThat(last.get(), is("0.0.0.0"));

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nForwarded: for=1.2.3.4\r\n\r\n");
        assertThat(last.get(), is("1.2.3.4"));

        last.set(null);
        _local.getResponse("GET / HTTP/1.0\r\nX-Forwarded-For: 1.1.1.1\r\nForwarded: for=6.6.6.6; for=1.2.3.4\r\nX-Forwarded-For: 6.6.6.6\r\nForwarded: proto=https\r\n\r\n");
        assertThat(last.get(), is("1.2.3.4"));

        assertThat(handler.getRemoteCount(), is(0));
    }

    @Test
    public void testLimit() throws Exception
    {
        ThreadLimitHandler handler = new ThreadLimitHandler("Forwarded");

        handler.setThreadLimit(4);

        AtomicInteger count = new AtomicInteger(0);
        AtomicInteger total = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);
        handler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                response.setStatus(HttpStatus.OK_200);
                if (!"/other".equals(Request.getPathInContext(request)))
                {
                    try
                    {
                        count.incrementAndGet();
                        total.incrementAndGet();
                        latch.await();
                    }
                    finally
                    {
                        count.decrementAndGet();
                    }
                }
                callback.succeeded();
                return true;
            }
        });
        _server.setHandler(handler);
        _server.start();

        Socket[] client = new Socket[10];
        for (int i = 0; i < client.length; i++)
        {
            client[i] = new Socket("127.0.0.1", _connector.getLocalPort());
            client[i].getOutputStream().write(("GET /" + i + " HTTP/1.0\r\nForwarded: for=1.2.3.4\r\n\r\n").getBytes());
            client[i].getOutputStream().flush();
            client[i].close();
        }

        await().atMost(10, TimeUnit.SECONDS).until(count::get, is(4));

        // check that other requests are not blocked
        String response = _local.getResponse("GET /other HTTP/1.0\r\nForwarded: for=6.6.6.6\r\n\r\n");
        assertThat(response, Matchers.containsString(" 200 OK"));

        // let the other requests go
        latch.countDown();

        await().atMost(10, TimeUnit.SECONDS).until(total::get, is(10));
        await().atMost(10, TimeUnit.SECONDS).until(count::get, is(0));

        assertThat(handler.getRemoteCount(), is(0));
    }

    @Test
    public void testDemandLimit() throws Exception
    {
        ThreadLimitHandler handler = new ThreadLimitHandler("Forwarded");

        handler.setThreadLimit(4);

        AtomicInteger count = new AtomicInteger(0);
        CountDownLatch processed = new CountDownLatch(5);
        CountDownLatch latch = new CountDownLatch(1);
        handler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                processed.countDown();
                Runnable onContent = new Runnable()
                {
                    private final AtomicLong read = new AtomicLong();
                    @Override
                    public void run()
                    {
                        count.incrementAndGet();
                        try
                        {
                            latch.await();
                            while (true)
                            {
                                Content.Chunk chunk = request.read();
                                if (chunk == null)
                                {
                                    request.demand(this);
                                    return;
                                }
                                if (Content.Chunk.isFailure(chunk))
                                    throw chunk.getFailure();

                                if (chunk.hasRemaining())
                                    read.addAndGet(chunk.remaining());
                                chunk.release();

                                if (chunk.isLast())
                                {
                                    Content.Sink.write(response, true, request.getHttpURI() + " read " + read.get(), callback);
                                    return;
                                }
                            }
                        }
                        catch (Throwable t)
                        {
                            callback.failed(t);
                        }
                        finally
                        {
                            count.decrementAndGet();
                        }
                    }
                };

                if (request.getHeaders().get(HttpHeader.CONTENT_LENGTH) == null)
                    callback.succeeded();
                else
                    request.demand(onContent);
                return true;
            }
        });
        _server.setHandler(handler);
        _server.start();

        Socket[] client = new Socket[5];
        for (int i = 0; i < client.length; i++)
        {
            client[i] = new Socket("127.0.0.1", _connector.getLocalPort());
            client[i].getOutputStream().write(("POST /" + i + " HTTP/1.0\r\nForwarded: for=1.2.3.4\r\nContent-Length: 2\r\n\r\n").getBytes());
            client[i].getOutputStream().flush();
        }

        // wait until all 5 requests are processed
        assertTrue(processed.await(10, TimeUnit.SECONDS));

        // wait until we are threadlessly waiting for demand
        await().atMost(10, TimeUnit.SECONDS).until(count::get, is(0));

        // Send some content for the clients
        for (Socket socket : client)
        {
            socket.getOutputStream().write('X');
            socket.getOutputStream().flush();
        }

        // wait until we 4 threads are blocked in onContent
        await().atMost(10, TimeUnit.SECONDS).until(count::get, is(4));

        // check that other requests are not blocked
        String response = _local.getResponse("GET /other HTTP/1.0\r\nForwarded: for=6.6.6.6\r\n\r\n");
        assertThat(response, Matchers.containsString(" 200 OK"));

        // let the requests go
        latch.countDown();

        // Wait until we are threadlessly waiting again
        await().atMost(10, TimeUnit.SECONDS).until(count::get, is(0));

        // Send the rest of the content for the clients
        for (Socket socket : client)
        {
            socket.getOutputStream().write('Y');
            socket.getOutputStream().flush();
        }

        // read all the responses
        for (Socket socket : client)
        {
            response = IO.toString(socket.getInputStream());
            assertThat(response, containsString(" 200 OK"));
            assertThat(response, containsString(" read 2"));
        }

        assertThat(handler.getRemoteCount(), is(0));
    }
}
