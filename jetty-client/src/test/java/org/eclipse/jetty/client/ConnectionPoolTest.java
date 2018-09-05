//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.IO;
import org.junit.After;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
@Ignore
public class ConnectionPoolTest
{
    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    @Parameterized.Parameters
    public static ConnectionPool.Factory[] parameters()
    {
        return new ConnectionPool.Factory[]
                {
                        destination -> new DuplexConnectionPool(destination, 8, destination),
                        destination -> new RoundRobinConnectionPool(destination, 8, destination)
                };
    }

    private final ConnectionPool.Factory factory;

    public ConnectionPoolTest(ConnectionPool.Factory factory)
    {
        this.factory = factory;
    }

    private void start(Handler handler) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);
        server.setHandler(handler);

        HttpClientTransport transport = new HttpClientTransportOverHTTP(1);
        transport.setConnectionPoolFactory(factory);
        client = new HttpClient(transport, null);
        server.addBean(client);

        server.start();
    }

    @After
    public void dispose() throws Exception
    {
        if (server != null)
            server.stop();
    }

    @Test
    public void test() throws Exception
    {
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, org.eclipse.jetty.server.Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                switch (HttpMethod.fromString(request.getMethod()))
                {
                    case GET:
                    {
                        int contentLength = request.getIntHeader("X-Download");
                        if (contentLength > 0)
                        {
                            response.setContentLength(contentLength);
                            response.getOutputStream().write(new byte[contentLength]);
                        }
                        break;
                    }
                    case POST:
                    {
                        int contentLength = request.getContentLength();
                        if (contentLength > 0)
                            response.setContentLength(contentLength);
                        IO.copy(request.getInputStream(), response.getOutputStream());
                        break;
                    }
                    default:
                    {
                        throw new IllegalStateException();
                    }
                }

                if (Boolean.parseBoolean(request.getHeader("X-Close")))
                    response.setHeader("Connection", "close");
            }
        });

        int parallelism = 16;
        int runs = 2;
        int iterations = 1024;
        CountDownLatch latch = new CountDownLatch(parallelism * runs);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        IntStream.range(0, parallelism).parallel().forEach(i ->
                IntStream.range(0, runs).forEach(j ->
                        run(latch, iterations, failures)));
        Assert.assertTrue(latch.await(iterations, TimeUnit.SECONDS));
        Assert.assertTrue(failures.toString(), failures.isEmpty());
    }

    private void run(CountDownLatch latch, int iterations, List<Throwable> failures)
    {
        long begin = System.nanoTime();
        for (int i = 0; i < iterations; ++i)
            test(failures);
        long end = System.nanoTime();
        long elapsed = TimeUnit.NANOSECONDS.toMillis(end - begin);
        System.err.printf("%d requests in %d ms, %.3f req/s%n", iterations, elapsed, elapsed > 0 ? iterations * 1000D / elapsed : -1D);
        latch.countDown();
    }

    private void test(List<Throwable> failures)
    {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // Choose a random method.
        HttpMethod method = random.nextBoolean() ? HttpMethod.GET : HttpMethod.POST;

        // Choose randomly whether to close the connection on the client or on the server.
        boolean clientClose = false;
        if (random.nextInt(100) < 1)
            clientClose = true;
        boolean serverClose = false;
        if (random.nextInt(100) < 1)
            serverClose = true;

        int maxContentLength = 64 * 1024;
        int contentLength = random.nextInt(maxContentLength) + 1;

        test(method, clientClose, serverClose, contentLength, failures);
    }

    private void test(HttpMethod method, boolean clientClose, boolean serverClose, int contentLength, List<Throwable> failures)
    {
        Request request = client.newRequest("localhost", connector.getLocalPort())
                .path("/")
                .method(method);

        if (clientClose)
            request.header(HttpHeader.CONNECTION, "close");
        else if (serverClose)
            request.header("X-Close", "true");

        switch (method)
        {
            case GET:
                request.header("X-Download", String.valueOf(contentLength));
                break;
            case POST:
                request.header(HttpHeader.CONTENT_LENGTH, String.valueOf(contentLength));
                request.content(new BytesContentProvider(new byte[contentLength]));
                break;
            default:
                throw new IllegalStateException();
        }

        FutureResponseListener listener = new FutureResponseListener(request, contentLength);
        request.send(listener);

        try
        {
            ContentResponse response = listener.get(5, TimeUnit.SECONDS);
            Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        }
        catch (Throwable x)
        {
            failures.add(x);
        }
    }
}
