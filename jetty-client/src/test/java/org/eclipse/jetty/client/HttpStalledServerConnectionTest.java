//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

/**
 * This test reproduces an issue on the server side, where the server threads might get stalled waiting for content,
 * even if the content has been already fully read:
 *
 * "serverQTP-74" prio=5 tid=0x00007f9765aff000 nid=0x9303 waiting on condition [0x000000012bee3000]
    java.lang.Thread.State: WAITING (parking)
	at sun.misc.Unsafe.park(Native Method)
	- parking to wait for  <0x00000001170c0098> (a java.util.concurrent.Semaphore$NonfairSync)
	at java.util.concurrent.locks.LockSupport.park(LockSupport.java:186)
	at java.util.concurrent.locks.AbstractQueuedSynchronizer.parkAndCheckInterrupt(AbstractQueuedSynchronizer.java:834)
	at java.util.concurrent.locks.AbstractQueuedSynchronizer.doAcquireSharedInterruptibly(AbstractQueuedSynchronizer.java:994)
	at java.util.concurrent.locks.AbstractQueuedSynchronizer.acquireSharedInterruptibly(AbstractQueuedSynchronizer.java:1303)
	at java.util.concurrent.Semaphore.acquire(Semaphore.java:317)
	at org.eclipse.jetty.util.BlockingCallback.block(BlockingCallback.java:96)
	at org.eclipse.jetty.server.HttpInputOverHTTP.blockForContent(HttpInputOverHTTP.java:64)
	at org.eclipse.jetty.server.HttpInput$1.waitForContent(HttpInput.java:335)
	at org.eclipse.jetty.server.HttpInput.read(HttpInput.java:131)
	- locked <0x00000001170a3da0> (a org.eclipse.jetty.server.HttpInputOverHTTP)
	at org.eclipse.jetty.util.IO.copy(IO.java:202)
	at org.eclipse.jetty.util.IO.copy(IO.java:143)
	at org.eclipse.jetty.client.HttpStalledClientConnectionTest$1.handle(HttpStalledClientConnectionTest.java:77)
	at org.eclipse.jetty.server.handler.HandlerWrapper.handle(HandlerWrapper.java:97)
	at org.eclipse.jetty.server.Server.handle(Server.java:445)
	at org.eclipse.jetty.server.HttpChannel.handle(HttpChannel.java:272)
	at org.eclipse.jetty.server.HttpConnection.onFillable(HttpConnection.java:220)
	at org.eclipse.jetty.io.AbstractConnection$6.run(AbstractConnection.java:465)
	at org.eclipse.jetty.util.thread.QueuedThreadPool.runJob(QueuedThreadPool.java:601)
	at org.eclipse.jetty.util.thread.QueuedThreadPool$3.run(QueuedThreadPool.java:532)
	at java.lang.Thread.run(Thread.java:724)

 * This test actually doesn't belong to jetty-client. But for now it's ok. Once the issue is identified this test can
 * be moved or removed.
 */
@Ignore
@RunWith(JUnit4.class)
public class HttpStalledServerConnectionTest
{
    private static final Logger LOG = Log.getLogger(HttpStalledServerConnectionTest.class);

    private Server server;
    private ServerConnector connector;
    private HttpClient httpClient;
    private ExecutorService threadPool = Executors.newFixedThreadPool(16);

    @Before
    public void setUp() throws Exception
    {
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName("serverQTP");
        server = new Server(threadPool);
        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                IO.copy(request.getInputStream(), response.getOutputStream());
                baseRequest.setHandled(true);
                //                response.getWriter().write("Hello world");
            }
        });
        connector = new ServerConnector(server);
        connector.setPort(8080);
        server.addConnector(connector);
        server.start();
        httpClient = new HttpClient();
        httpClient.start();
    }

    @After
    public void tearDown() throws Exception
    {
        server.stop();
        server.join();
        httpClient.stop();

    }

    @Test
    public void simpleLoadTest() throws InterruptedException, ExecutionException, TimeoutException
    {
        int requests = 1000;
        CountDownLatch requestLatch = new CountDownLatch(requests);
        for (int i = 0; i < requests; i++)
        {
            threadPool.execute(new executeSingleRequestRunnable(requestLatch));
        }
        threadPool.shutdown();
        threadPool.awaitTermination(60, TimeUnit.SECONDS);

        assertThat("all requests executed", requestLatch.await(60, TimeUnit.SECONDS), is(true));
    }

    private class executeSingleRequestRunnable implements Runnable
    {
        final CountDownLatch requestLatch;

        private executeSingleRequestRunnable(CountDownLatch requestLatch)
        {
            this.requestLatch = requestLatch;
        }

        public void run()
        {
            URI uri = URI.create("http://localhost:" + connector.getLocalPort());
            DeferredContentProvider deferredContentProvider = new DeferredContentProvider();
            org.eclipse.jetty.client.api.Request request = httpClient.newRequest(uri).method(HttpMethod.POST).content
                    (deferredContentProvider);
            request.header("Via","http/1.1 Thomass-MacBook-Pro.local");
            request.header("X-Forwarded-Proto", "http");
            request.header("X-Forwarded-Host", "localhost");
            request.header("X-Forwarded-For", "localhost/127.0.0.1:61726");
            request.header("X-Forwarded-Server", "Thomass-MacBook-Pro.local");
            ArrayList<Response.ResponseListener> listeners = new ArrayList<>();
            listeners.add(new Response.ContentListener()
            {
                @Override
                public void onContent(Response response, ByteBuffer content)
                {
                    assertThat("response status is 200", response.getStatus(), is(200));
                    requestLatch.countDown();
                    LOG.warn("status={},response={}", response.getStatus(), BufferUtil.toDetailString(content));
                }
            });
            httpClient.send(request, listeners);
            final String body = UUID.randomUUID().toString();

            deferredContentProvider.offer(BufferUtil.toBuffer(body.getBytes()));
            deferredContentProvider.close();
        }
    }
}
