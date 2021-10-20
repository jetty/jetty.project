package org.eclipse.jetty.test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.LogArrayByteBufferPool;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.server.NativeWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketMemoryTest
{
    private static final Logger LOG = Log.getLogger(WebSocketMemoryTest.class);

    private Server _server;
    private static final AtomicReference<CountDownLatch> _latchReference = new AtomicReference<>();

    @WebSocket
    public static class ServerSocket
    {
        @OnWebSocketMessage
        public void onMessage(Session session, String message) throws InterruptedException
        {
            CountDownLatch latch = _latchReference.get();
            latch.countDown();
            assertTrue(latch.await(20, TimeUnit.SECONDS));
            session.close(1000, "success");
        }
    }

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
//        _server.addBean(new LogArrayByteBufferPool(512, -1, -1, maxMemory, maxMemory));
        int maxMemory = 1024 * 1024 * 64;
//        ByteBufferPool bufferPool = new ArrayByteBufferPool(-1, -1, -1, -1, maxMemory, maxMemory);
        LogArrayByteBufferPool bufferPool = new LogArrayByteBufferPool(-1, 1024 * 1024, -1, maxMemory, maxMemory);
//        MappedByteBufferPool bufferPool = new MappedByteBufferPool(-1, -1, null, maxMemory, maxMemory);
        bufferPool.setDetailedDump(true);
//        ByteBufferPool bufferPool = new NullByteBufferPool();
        _server.addBean(bufferPool);
        ServerConnector _connector = new ServerConnector(_server);
        _connector.setPort(8080);
        _server.addConnector(_connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        WebSocketUpgradeFilter.configure(contextHandler);
        NativeWebSocketServletContainerInitializer.configure(contextHandler, ((servletContext, configuration) ->
        {
            WebSocketPolicy policy = configuration.getPolicy();
            policy.setMaxTextMessageBufferSize(Integer.MAX_VALUE);
            policy.setMaxTextMessageSize(Integer.MAX_VALUE);
            configuration.addMapping("/websocket", ServerSocket.class);
        }));

        contextHandler.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                CountDownLatch countDownLatch = _latchReference.get();
                if (countDownLatch != null)
                    assertThat(countDownLatch.getCount(), is(0L));

                int numThreads = Integer.parseInt(req.getParameter("numThreads"));
                _latchReference.compareAndSet(countDownLatch, new CountDownLatch(numThreads));
            }
        }), "/setCount");

        _server.setHandler(contextHandler);

        _server.addBean(new MBeanContainer(ManagementFactory.getPlatformMBeanServer()));
        _server.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
    }

    @Test
    public void server() throws Exception
    {
        _server.join();
    }
}
