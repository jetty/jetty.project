package org.eclipse.jetty.test;

import java.io.IOException;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.strategy.ExecuteProduceConsume;
import org.eclipse.jetty.util.thread.strategy.ProduceExecuteConsume;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

@RunWith(Parameterized.class)
public class RebuildSelectorTest
{
    @Parameterized.Parameters(name = "{0}")
    public static List<ExecutionStrategy.Factory[]> params()
    {
        List<ExecutionStrategy.Factory[]> strategies = new ArrayList<>();
        strategies.add(new ExecutionStrategy.Factory[]{new ExecuteProduceConsume.Factory()});
        strategies.add(new ExecutionStrategy.Factory[]{new ProduceExecuteConsume.Factory()});
        return strategies;
    }

    private HttpClient client;
    private Server server;

    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Before
    public void startClient() throws Exception
    {
        client = new HttpClient();
        client.setIdleTimeout(2000);
        client.setMaxConnectionsPerDestination(1);
        client.start();
    }

    @After
    public void stopClient() throws Exception
    {
        client.stop();
    }

    public RebuildSelectorTest(ExecutionStrategy.Factory strategyFactory) throws Exception
    {
        server = new Server();

        CustomServerConnector connector = new CustomServerConnector(server, strategyFactory, 1, 1);
        connector.setPort(0);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(HelloServlet.class, "/hello");
        context.addServlet(CloseSelectorServlet.class, "/selector/close");
        context.setAttribute("connector", connector);

        HandlerList handlers = new HandlerList();
        handlers.addHandler(context);
        handlers.addHandler(new DefaultHandler());

        server.setHandler(handlers);

        server.start();
    }

    @Test
    public void testRebuildServerSelector() throws Exception
    {
        // Request /hello
        assertRequestHello();

        // Request /selector/close
        assertRequestSelectorClose();

        // Wait for selectors to close from action above
        TimeUnit.MILLISECONDS.sleep(1000);

        // Request /hello
        assertRequestHello();
    }

    private void assertRequestSelectorClose() throws InterruptedException, ExecutionException, TimeoutException
    {
        ContentResponse response = client.newRequest(server.getURI().resolve("/selector/close"))
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
        private final ExecutionStrategy.Factory strategyFactory;

        public CustomServerConnector(Server server, ExecutionStrategy.Factory strategyFactory, int acceptors, int selectors)
        {
            super(server, acceptors, selectors);
            this.strategyFactory = strategyFactory;
        }

        @Override
        public ExecutionStrategy.Factory getExecutionStrategyFactory()
        {
            return this.strategyFactory;
        }

        @Override
        protected SelectorManager newSelectorManager(Executor executor, Scheduler scheduler, int selectors)
        {
            return new ServerConnectorManager(executor, scheduler, selectors)
            {
                @Override
                protected ManagedSelector newSelector(int id)
                {
                    return new CustomManagedSelector(this, id, getExecutionStrategyFactory());
                }
            };
        }
    }

    public static class CustomManagedSelector extends ManagedSelector
    {
        private static final Logger LOG = Log.getLogger(CustomManagedSelector.class);

        public CustomManagedSelector(SelectorManager selectorManager, int id, ExecutionStrategy.Factory executionFactory)
        {
            super(selectorManager, id, executionFactory);
        }

        @Override
        protected void onSelectFailed(Throwable cause)
        {
            try
            {
                LOG.debug("onSelectFailed()", cause);
                this.startSelector();
            }
            catch (Exception ex)
            {
                LOG.warn(ex);
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

    public static class CloseSelectorServlet extends HttpServlet
    {
        private static final Logger LOG = Log.getLogger(CloseSelectorServlet.class);
        private static final int DELAY_MS = 500;
        private ServerConnector connector;
        private ScheduledExecutorService scheduledExecutorService;
        private InterruptSelector interruptSelectorRunnable;

        @Override
        public void init()
        {
            connector = (ServerConnector)getServletContext().getAttribute("connector");
            scheduledExecutorService = Executors.newScheduledThreadPool(5);
            interruptSelectorRunnable = new InterruptSelector();
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            resp.setContentType("text/plain");
            resp.setCharacterEncoding("utf-8");
            resp.getWriter().printf("Closing selectors in %,d ms%n", DELAY_MS);
            scheduledExecutorService.schedule(interruptSelectorRunnable, DELAY_MS, TimeUnit.MILLISECONDS);
        }

        private class InterruptSelector implements Runnable
        {
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
    }
}
