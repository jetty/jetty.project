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
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
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
import org.eclipse.jetty.util.thread.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FailedSelectorTest
{
    private HttpClient client;
    private Server server;
    private AsyncCloseSelectorServlet asyncCloseSelectorServlet;

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new HttpClient();
        client.setIdleTimeout(2000);
        client.setMaxConnectionsPerDestination(1);
        client.start();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }

    public void startServer(Function<Server, ServerConnector> customizeServerConsumer) throws Exception
    {
        server = new Server();

        ServerConnector connector = customizeServerConsumer.apply(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(HelloServlet.class, "/hello");

        ServletHolder closeHolder = new ServletHolder(new CloseSelectorServlet(connector));
        context.addServlet(closeHolder, "/selector/close");

        asyncCloseSelectorServlet = new AsyncCloseSelectorServlet(connector);
        ServletHolder asyncCloseHolder = new ServletHolder(asyncCloseSelectorServlet);
        asyncCloseHolder.setAsyncSupported(true);
        context.addServlet(asyncCloseHolder, "/selector/async-close");

        HandlerList handlers = new HandlerList();
        handlers.addHandler(context);
        handlers.addHandler(new DefaultHandler());

        server.setHandler(handlers);

        server.start();
    }

    @Test
    public void testRebuildServerSelectorNormal() throws Exception
    {
        CountDownLatch failedLatch = new CountDownLatch(1);

        startServer((server) ->
        {
            CustomServerConnector connector = new CustomServerConnector(server, failedLatch, 1, 1);
            connector.setPort(0);
            return connector;
        });

        // Request /hello
        assertRequestHello();

        // Request /selector/close
        assertRequestSelectorClose("/selector/close");

        // Wait for selectors to close from action above
        assertTrue(failedLatch.await(2, TimeUnit.SECONDS));

        // Request /hello
        assertRequestHello();
    }

    @Test
    @Disabled
    public void testRebuildServerSelectorAsync() throws Exception
    {
        CountDownLatch failedLatch = new CountDownLatch(1);

        startServer((server) ->
        {
            CustomServerConnector connector = new CustomServerConnector(server, failedLatch, 1, 1);
            connector.setPort(0);
            return connector;
        });

        // Request /hello
        assertRequestHello();

        // Request /selector/async-close
        assertRequestSelectorClose("/selector/async-close");

        // Wait for selectors to close from action above
        assertTrue(failedLatch.await(2, TimeUnit.SECONDS));

        // Ensure that Async Listener onError was called
        assertTrue(asyncCloseSelectorServlet.onErrorLatch.await(2, TimeUnit.SECONDS));

        // Request /hello
        assertRequestHello();
    }

    private void assertRequestSelectorClose(String path) throws InterruptedException, ExecutionException, TimeoutException
    {
        ContentResponse response = client.newRequest(server.getURI().resolve(path))
            .method(HttpMethod.GET)
            .header(HttpHeader.CONNECTION, "close")
            .send();

        assertThat("/selector/close status", response.getStatus(), is(HttpStatus.OK_200));
        assertThat("/selector/close response", response.getContentAsString(), startsWith("Closing selectors "));
    }

    private void assertRequestHello() throws InterruptedException, ExecutionException, TimeoutException
    {
        ContentResponse response = client.newRequest(server.getURI().resolve("/hello"))
            .method(HttpMethod.GET)
            .header(HttpHeader.CONNECTION, "close")
            .send();

        assertThat("/hello status", response.getStatus(), is(HttpStatus.OK_200));
        assertThat("/hello response", response.getContentAsString(), startsWith("Hello "));
    }

    public static class CustomServerConnector extends ServerConnector
    {
        private final CountDownLatch failedLatch;

        public CustomServerConnector(Server server, CountDownLatch failedLatch, int acceptors, int selectors)
        {
            super(server, acceptors, selectors);
            this.failedLatch = failedLatch;
        }

        @Override
        protected SelectorManager newSelectorManager(Executor executor, Scheduler scheduler, int selectors)
        {
            return new ServerConnectorManager(executor, scheduler, selectors)
            {
                @Override
                protected ManagedSelector newSelector(int id)
                {
                    return new CustomManagedSelector(this, id, failedLatch);
                }
            };
        }
    }

    public static class CustomManagedSelector extends ManagedSelector
    {
        private static final Logger LOG = Log.getLogger(CustomManagedSelector.class);
        private final CountDownLatch failedLatch;

        public CustomManagedSelector(SelectorManager selectorManager, int id, CountDownLatch failedLatch)
        {
            super(selectorManager, id);
            this.failedLatch = failedLatch;
        }

        @Override
        protected void onSelectFailed(Throwable cause) throws Exception
        {
            try
            {
                LOG.debug("onSelectFailed()", cause);
                this.startSelector();
            }
            finally
            {
                failedLatch.countDown();
            }
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
            resp.getWriter().printf("Closing selectors in %,d ms%n", DELAY_MS);
            scheduledExecutorService.schedule(new InterruptSelector(connector), DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    public static class AsyncCloseSelectorServlet extends HttpServlet
    {
        private static final int DELAY_MS = 200;
        private ServerConnector connector;
        private ScheduledExecutorService scheduledExecutorService;
        public CountDownLatch onErrorLatch = new CountDownLatch(1);

        public AsyncCloseSelectorServlet(ServerConnector connector)
        {
            this.connector = connector;
            scheduledExecutorService = Executors.newScheduledThreadPool(5);
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            resp.setContentType("text/plain");
            resp.setCharacterEncoding("utf-8");
            ServletOutputStream out = resp.getOutputStream();
            out.print("Closing selectors " + DELAY_MS);

            AsyncContext asyncContext = req.startAsync();
            asyncContext.setTimeout(0);
            asyncContext.addListener(new AsyncListener()
            {
                @Override
                public void onComplete(AsyncEvent event)
                {
                }

                @Override
                public void onTimeout(AsyncEvent event)
                {
                }

                @Override
                public void onError(AsyncEvent event)
                {
                    resp.setStatus(500);
                    event.getAsyncContext().complete();
                    onErrorLatch.countDown();
                }

                @Override
                public void onStartAsync(AsyncEvent event)
                {
                }
            });

            scheduledExecutorService.schedule(new InterruptSelector(connector), DELAY_MS, TimeUnit.MILLISECONDS);
            /* trigger EofException after selector close
            scheduledExecutorService.schedule(() ->
            {
                byte[] b = new byte[128 * 1024 * 1024];
                Arrays.fill(b, (byte)'x');
                try
                {
                    out.write(b);
                    out.flush();
                }
                catch (IOException e)
                {
                    e.printStackTrace(System.out);
                }
            }, DELAY_MS * 2, TimeUnit.MILLISECONDS);
             */
        }
    }
}