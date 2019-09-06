//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.test;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.Selector;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FailedSelectorTest
{
    private static final Logger LOG = Log.getLogger(FailedSelectorTest.class);
    private HttpClient client;
    private Server server;

    @AfterEach
    public void stopServerAndClient() throws Exception
    {
        LOG.info("Deathing Server");
        server.stop();
        LOG.info("Deathing Client");
        client.stop();
    }

    @BeforeEach
    public void startClient() throws Exception
    {
        HttpClientTransport transport = new HttpClientTransportOverHTTP(1);
        QueuedThreadPool qtp = new QueuedThreadPool();
        qtp.setName("Client");
        qtp.setStopTimeout(1000);
        client = new HttpClient(transport, null);
        client.setExecutor(qtp);

        client.setIdleTimeout(1000);
//        client.setMaxConnectionsPerDestination(1);
//        client.setMaxRequestsQueuedPerDestination(1);
        client.start();
    }

    public void startServer(Function<Server, ServerConnector> customizeServerConsumer) throws Exception
    {
        server = new Server();
        server.setStopTimeout(1000);
        server.setStopAtShutdown(true);

        ServerConnector connector = customizeServerConsumer.apply(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(HelloServlet.class, "/hello");

        ServletHolder closeHolder = new ServletHolder(new CloseSelectorServlet(connector));
        context.addServlet(closeHolder, "/selector/close");

        HandlerList handlers = new HandlerList();
        handlers.addHandler(context);
        handlers.addHandler(new DefaultHandler());

        server.setHandler(handlers);

        server.start();
    }

    @Test
    public void testRestartServerOnSelectFailure() throws Exception
    {
        CountDownLatch failedLatch = new CountDownLatch(1);

        startServer((server) ->
        {
            CustomServerConnector connector = new CustomServerConnector(server, 1, 1, new RestartServerTask(server, failedLatch));
            connector.setPort(0);
            connector.setIdleTimeout(1000);
            return connector;
        });

        // Request /hello
        assertRequestHello();

        // Request /selector/close
        assertRequestSelectorClose("/selector/close");

        // Wait for selectors to close from action above
        assertTrue(failedLatch.await(2, TimeUnit.SECONDS));
        LOG.info("Got failedLatch");

        // Request /hello
        assertRequestHello();

        LOG.info("Test done");
    }

    private void assertRequestSelectorClose(String path) throws InterruptedException, ExecutionException, TimeoutException
    {
        URI dest = server.getURI().resolve(path);
        LOG.info("Requesting GET on {}", dest);

        ContentResponse response = client.newRequest(dest)
            .method(HttpMethod.GET)
            .header(HttpHeader.CONNECTION, "close")
            .send();

        assertThat("/selector/close status", response.getStatus(), is(HttpStatus.OK_200));
        assertThat("/selector/close response", response.getContentAsString(), startsWith("Closing selectors "));
    }

    private void assertRequestHello() throws InterruptedException, ExecutionException, TimeoutException
    {
        URI dest = server.getURI().resolve("/hello");
        LOG.info("Requesting GET on {}", dest);
        ContentResponse response = client.newRequest(dest)
            .method(HttpMethod.GET)
            .header(HttpHeader.CONNECTION, "close")
            .send();

        assertThat("/hello status", response.getStatus(), is(HttpStatus.OK_200));
        assertThat("/hello response", response.getContentAsString(), startsWith("Hello "));
    }

    public static class RestartServerTask implements Runnable
    {
        private final Server server;
        private final CountDownLatch latch;

        public RestartServerTask(Server server, CountDownLatch latch)
        {
            this.server = server;
            this.latch = latch;
        }

        @Override
        public void run()
        {
            try
            {
                server.stop();
                server.start();
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }
            finally
            {
                latch.countDown();
            }
        }
    }

    public static class CustomServerConnector extends ServerConnector
    {
        private final Runnable onSelectFailureTask;

        public CustomServerConnector(Server server, int acceptors, int selectors, Runnable onSelectFailureTask)
        {
            super(server, acceptors, selectors);
            this.onSelectFailureTask = onSelectFailureTask;
        }

        @Override
        protected SelectorManager newSelectorManager(Executor executor, Scheduler scheduler, int selectors)
        {
            return new ServerConnectorManager(executor, scheduler, selectors)
            {
                @Override
                protected ManagedSelector newSelector(int id)
                {
                    return new CustomManagedSelector(this, id, onSelectFailureTask);
                }
            };
        }
    }

    public static class CustomManagedSelector extends ManagedSelector
    {
        private final Runnable onSelectFailureTask;

        public CustomManagedSelector(SelectorManager selectorManager, int id, Runnable onSelectFailureTask)
        {
            super(selectorManager, id);
            this.onSelectFailureTask = onSelectFailureTask;
        }

        @Override
        protected void onSelectFailed(Throwable cause)
        {
            new Thread(onSelectFailureTask, "onSelectFailedTask").start();
        }
    }

    public static class HelloServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            resp.setContentType("text/plain");
            resp.setCharacterEncoding("utf-8");
            resp.getWriter().printf("Hello %s:%d%n", req.getRemoteAddr(), req.getRemotePort());
        }
    }

    private static class InterruptSelector implements Runnable
    {
        private static final Logger LOG = Log.getLogger(InterruptSelector.class);
        private final ServerConnector connector;

        public InterruptSelector(ServerConnector connector)
        {
            this.connector = connector;
        }

        @Override
        public void run()
        {
            SelectorManager selectorManager = connector.getSelectorManager();
            Collection<ManagedSelector> managedSelectors = selectorManager.getBeans(ManagedSelector.class);
            for (ManagedSelector managedSelector : managedSelectors)
            {
                if (managedSelector instanceof CustomManagedSelector)
                {
                    CustomManagedSelector customManagedSelector = (CustomManagedSelector)managedSelector;
                    Selector selector = customManagedSelector.getSelector();
                    LOG.debug("Closing selector {}}", selector);
                    IO.close(selector);
                }
            }
        }
    }

    public static class CloseSelectorServlet extends HttpServlet
    {
        private static final int DELAY_MS = 500;
        private ServerConnector connector;
        private ScheduledExecutorService scheduledExecutorService;

        public CloseSelectorServlet(ServerConnector connector)
        {
            this.connector = connector;
            scheduledExecutorService = Executors.newScheduledThreadPool(5);
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            resp.setContentType("text/plain");
            resp.setCharacterEncoding("utf-8");
            resp.setHeader("Connection", "close");
            resp.getWriter().printf("Closing selectors in %,d ms%n", DELAY_MS);
            scheduledExecutorService.schedule(new InterruptSelector(connector), DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }
}