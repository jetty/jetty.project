//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.proxy;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.proxy.AbstractProxyServlet;
import org.eclipse.jetty.proxy.AsyncProxyServlet;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class Async502Loop
{
    private static final Logger LOG = Log.getLogger(Async502Loop.class);
    private static final String PROXIED_HEADER = "X-Proxied";

    public static void main(String[] args)
    {
        try
        {
            new Async502Loop().loop();
        }
        catch (Throwable t)
        {
            t.printStackTrace(System.err);
        }
    }

    private HttpClient client;
    private Server proxy;
    private ServerConnector proxyConnector;
    private ServletContextHandler proxyContext;
    private AbstractProxyServlet proxyServlet;
    private Server server;
    private ServerConnector serverConnector;

    public Async502Loop()
    {
        proxyServlet = new AsyncProxyServlet();
        // proxyServlet = new AsyncMiddleManServlet();
    }

    private void startServer(HttpServlet servlet) throws Exception
    {
        QueuedThreadPool serverPool = new QueuedThreadPool();
        serverPool.setName("server");
        server = new Server(serverPool);
        serverConnector = new ServerConnector(server);
        server.addConnector(serverConnector);

        ServletContextHandler appCtx = new ServletContextHandler(server,"/",true,false);
        ServletHolder appServletHolder = new ServletHolder(servlet);
        appCtx.addServlet(appServletHolder,"/*");

        server.start();
    }

    private void startProxy() throws Exception
    {
        startProxy(new HashMap<String, String>());
    }

    private void startProxy(Map<String, String> initParams) throws Exception
    {
        QueuedThreadPool proxyPool = new QueuedThreadPool();
        proxyPool.setName("proxy");
        proxy = new Server(proxyPool);

        HttpConfiguration configuration = new HttpConfiguration();
        configuration.setSendDateHeader(false);
        configuration.setSendServerVersion(false);
        String value = initParams.get("outputBufferSize");
        if (value != null)
            configuration.setOutputBufferSize(Integer.valueOf(value));
        proxyConnector = new ServerConnector(proxy,new HttpConnectionFactory(configuration));
        proxy.addConnector(proxyConnector);

        proxyContext = new ServletContextHandler(proxy,"/",true,false);
        ServletHolder proxyServletHolder = new ServletHolder(proxyServlet);
        proxyServletHolder.setInitParameters(initParams);
        proxyContext.addServlet(proxyServletHolder,"/*");

        proxy.start();
    }

    private void startClient() throws Exception
    {
        client = prepareClient();
    }

    private HttpClient prepareClient() throws Exception
    {
        QueuedThreadPool clientPool = new QueuedThreadPool();
        clientPool.setName("client");
        HttpClient result = new HttpClient();
        result.setExecutor(clientPool);
        result.getProxyConfiguration().getProxies().add(new HttpProxy("localhost",proxyConnector.getLocalPort()));
        result.start();
        return result;
    }

    public void dispose() throws Exception
    {
        client.stop();
        proxy.stop();
        server.stop();
    }

    private static class ClientLoop implements Runnable
    {
        private final CountDownLatch active;
        private final AtomicBoolean ok;
        private final HttpClient client;
        private final String host;
        private final int port;

        public ClientLoop(CountDownLatch activeClientLatch, AtomicBoolean atomicOk, HttpClient client, String serverHost, int serverPort)
        {
            this.active = activeClientLatch;
            this.ok = atomicOk;
            this.client = client;
            this.host = serverHost;
            this.port = serverPort;
        }

        @Override
        public void run()
        {
            String threadName = Thread.currentThread().getName();
            LOG.info("Starting thread {}",threadName);
            try
            {
                while (ok.get())
                {
                    byte[] content = new byte[1024];
                    new Random().nextBytes(content);
                    ContentResponse response = client.newRequest(host,port).method(HttpMethod.POST).content(new BytesContentProvider(content))
                            .timeout(5,TimeUnit.SECONDS).send();

                    if (response.getStatus() != 200)
                    {
                        LOG.warn("Got response <{}>, expecting <{}>",response.getStatus(),200);
                        // allow all ClientLoops to finish
                        ok.set(false);
                    }
                }
            }
            catch (InterruptedException | TimeoutException | ExecutionException e)
            {
                LOG.warn("Error processing request",e);
                ok.set(false);
            }
            finally
            {
                LOG.info("Shutting down thread {}",threadName);
                active.countDown();
            }
        }
    }

    @SuppressWarnings("serial")
    private void loop() throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                if (req.getHeader("Via") != null)
                    resp.addHeader(PROXIED_HEADER,"true");
                IO.copy(req.getInputStream(),resp.getOutputStream());
            }
        });
        startProxy();
        startClient();

        // Number of clients to simulate
        int clientCount = 5;
        // Latch for number of clients still active (used to terminate test)
        final CountDownLatch activeClientLatch = new CountDownLatch(clientCount);
        // Atomic Boolean to track that its OK to still continue looping.
        // When this goes false, that means one of the client threads has
        // encountered an error condition, and should allow all remaining
        // client threads to finish cleanly.
        final AtomicBoolean atomicOk = new AtomicBoolean(true);

        // Start clients
        for (int i = 0; i < clientCount; i++)
        {
            ClientLoop r = new ClientLoop(activeClientLatch,atomicOk,client,"localhost",serverConnector.getLocalPort());
            String name = "client-" + i;
            Thread thread = new Thread(r,name);
            thread.start();
        }

        activeClientLatch.await();
        dispose();
    }
}
