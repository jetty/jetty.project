package org.eclipse.jetty.websocket;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class WebSocketOverSSLTest
{
    private Server _server;
    private int _port;
    private WebSocket.Connection _connection;

    private void startServer(final WebSocket webSocket) throws Exception
    {
        _server = new Server();
        SslSelectChannelConnector connector = new SslSelectChannelConnector();
        _server.addConnector(connector);
        SslContextFactory cf = connector.getSslContextFactory();
        cf.setKeyStorePath(MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath());
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");
        _server.setHandler(new WebSocketHandler()
        {
            public WebSocket doWebSocketConnect(HttpServletRequest request, String protocol)
            {
                return webSocket;
            }
        });
        _server.start();
        _port = connector.getLocalPort();
    }

    private void startClient(final WebSocket webSocket) throws Exception
    {
        Assert.assertTrue(_server.isStarted());

        WebSocketClientFactory factory = new WebSocketClientFactory(new QueuedThreadPool(), new ZeroMaskGen());
        SslContextFactory cf = factory.getSslContextFactory();
        cf.setKeyStorePath(MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath());
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");
        factory.start();
        WebSocketClient client = new WebSocketClient(factory);
        _connection = client.open(new URI("wss://localhost:" + _port), webSocket).get(5, TimeUnit.SECONDS);
    }

    @After
    public void destroy() throws Exception
    {
        if (_connection != null)
            _connection.close();
        if (_server != null)
        {
            _server.stop();
            _server.join();
        }
    }

    @Test
    public void testWebSocketOverSSL() throws Exception
    {
        final String message = "message";
        final CountDownLatch serverLatch = new CountDownLatch(1);
        startServer(new WebSocket.OnTextMessage()
        {
            private Connection connection;

            public void onOpen(Connection connection)
            {
                this.connection = connection;
            }

            public void onMessage(String data)
            {
                try
                {
                    Assert.assertEquals(message, data);
                    connection.sendMessage(data);
                    serverLatch.countDown();
                }
                catch (IOException x)
                {
                    x.printStackTrace();
                }
            }

            public void onClose(int closeCode, String message)
            {
            }
        });
        final CountDownLatch clientLatch = new CountDownLatch(1);
        startClient(new WebSocket.OnTextMessage()
        {
            public void onOpen(Connection connection)
            {
            }

            public void onMessage(String data)
            {
                Assert.assertEquals(message, data);
                clientLatch.countDown();
            }

            public void onClose(int closeCode, String message)
            {
            }
        });
        _connection.sendMessage(message);

        Assert.assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }
}
