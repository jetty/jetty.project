package org.eclipse.jetty.server.ssl;

import java.io.IOException;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.HandshakeCompletedEvent;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.ssl.SslContextFactory;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.io.nio.SelectorManager;
import org.eclipse.jetty.io.nio.SslSelectChannelEndPoint;
import org.eclipse.jetty.server.AsyncHttpConnection;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class SslTruncationAttackTest
{
    private Server server;
    private SslSelectChannelConnector connector;
    private SSLContext sslContext;
    private AtomicBoolean endPointClosed;
    private AtomicLong handleCount;

    @Before
    public void initServer() throws Exception
    {
        handleCount = new AtomicLong();
        endPointClosed = new AtomicBoolean();

        server = new Server();
        connector = new SslSelectChannelConnector()
        {
            @Override
            protected SelectChannelEndPoint newEndPoint(SocketChannel channel, SelectorManager.SelectSet selectSet, SelectionKey key) throws IOException
            {
                return new SslSelectChannelEndPoint(getSslBuffers(), channel, selectSet, key, createSSLEngine(channel))
                {
                    @Override
                    public void close() throws IOException
                    {
                        endPointClosed.compareAndSet(false, true);
                        super.close();
                    }
                };
            }

            @Override
            protected Connection newConnection(SocketChannel channel, SelectChannelEndPoint endpoint)
            {
                AsyncHttpConnection connection=new AsyncHttpConnection(this, endpoint, server)
                {
                    @Override
                    public Connection handle() throws IOException
                    {
                        handleCount.incrementAndGet();
                        return super.handle();
                    }
                };
                ((HttpParser)connection.getParser()).setForceContentBuffer(true);
                return connection;
            }
        };
        server.addConnector(connector);

        String keystorePath = System.getProperty("basedir", ".") + "/src/test/resources/keystore";
        SslContextFactory sslContextFactory = connector.getSslContextFactory();
        sslContextFactory.setKeyStore(keystorePath);
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setKeyManagerPassword("keypwd");
        sslContextFactory.setTrustStore(keystorePath);
        sslContextFactory.setTrustStorePassword("storepwd");

        server.setHandler(new EmptyHandler());

        server.start();

        sslContext = sslContextFactory.getSslContext();
    }

    @After
    public void destroyServer() throws Exception
    {
        server.stop();
        server.join();
    }

    /**
     * A SSL truncation attack is when the remote peer sends a TCP FIN
     * without having sent the SSL close alert.
     * We need to handle this condition carefully to avoid spinning
     * on the selector and by closing the connection altogether.
     *
     * @throws Exception if the test fails
     */
    @Test
    public void testTruncationAttackAfterReading() throws Exception
    {
        Socket socket = new Socket("localhost", connector.getLocalPort());
        SSLSocket sslSocket = (SSLSocket)sslContext.getSocketFactory().createSocket(socket, socket.getInetAddress().getHostName(), socket.getPort(), true);
        sslSocket.setUseClientMode(true);
        final CountDownLatch handshakeLatch = new CountDownLatch(1);
        sslSocket.addHandshakeCompletedListener(new HandshakeCompletedListener()
        {
            public void handshakeCompleted(HandshakeCompletedEvent handshakeCompletedEvent)
            {
                handshakeLatch.countDown();
            }
        });
        sslSocket.startHandshake();

        Assert.assertTrue(handshakeLatch.await(1, TimeUnit.SECONDS));

        String request = "" +
                "GET / HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "\r\n";
        sslSocket.getOutputStream().write(request.getBytes("UTF-8"));

        byte[] buffer = new byte[1024];
        StringBuilder response = new StringBuilder();
        sslSocket.setSoTimeout(1000);
        while (true)
        {
            int read = sslSocket.getInputStream().read(buffer);
            response.append(new String(buffer, 0, read, "UTF-8"));
            if (response.indexOf("\r\n\r\n") >= 0)
                break;
        }

        handleCount.set(0);

        // Send TCP FIN without SSL close alert
        socket.close();

        // Sleep for a while to detect eventual spin looping
        TimeUnit.SECONDS.sleep(1);

        Assert.assertTrue("handle() invocations", handleCount.get()<=1);
        Assert.assertTrue("endpoint not closed", endPointClosed.get());
    }

    /**
     * This test is currently failing because we are looping on SslSCEP.unwrap()
     * to fill the buffer, so there is a case where we loop once, read some data
     * loop again and read -1, but we can't close the connection yet as we have
     * to notify the application (not sure that this is necessary... must assume
     * the data is truncated, so it's not that safe to pass it to the application).
     * This case needs to be revisited, and it also requires a review of the
     * Connection:close case, especially on the client side.
     * @throws Exception if the test fails
     */
    @Ignore
    @Test
    public void testTruncationAttackBeforeReading() throws Exception
    {
        Socket socket = new Socket("localhost", connector.getLocalPort());
        SSLSocket sslSocket = (SSLSocket)sslContext.getSocketFactory().createSocket(socket, socket.getInetAddress().getHostName(), socket.getPort(), true);
        sslSocket.setUseClientMode(true);
        final CountDownLatch handshakeLatch = new CountDownLatch(1);
        sslSocket.addHandshakeCompletedListener(new HandshakeCompletedListener()
        {
            public void handshakeCompleted(HandshakeCompletedEvent handshakeCompletedEvent)
            {
                handshakeLatch.countDown();
            }
        });
        sslSocket.startHandshake();

        Assert.assertTrue(handshakeLatch.await(1, TimeUnit.SECONDS));

        String request = "" +
                "GET / HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "\r\n";
        sslSocket.getOutputStream().write(request.getBytes("UTF-8"));

        // Do not read the response, just close the underlying socket

        handleCount.set(0);

        // Send TCP FIN without SSL close alert
        socket.close();

        // Sleep for a while to detect eventual spin looping
        TimeUnit.SECONDS.sleep(1);

        Assert.assertEquals("handle() invocations", 1, handleCount.get());
        Assert.assertTrue("endpoint not closed", endPointClosed.get());
    }

    @Test
    public void testTruncationAttackAfterHandshake() throws Exception
    {
        Socket socket = new Socket("localhost", connector.getLocalPort());
        SSLSocket sslSocket = (SSLSocket)sslContext.getSocketFactory().createSocket(socket, socket.getInetAddress().getHostName(), socket.getPort(), true);
        sslSocket.setUseClientMode(true);
        final CountDownLatch handshakeLatch = new CountDownLatch(1);
        sslSocket.addHandshakeCompletedListener(new HandshakeCompletedListener()
        {
            public void handshakeCompleted(HandshakeCompletedEvent handshakeCompletedEvent)
            {
                handshakeLatch.countDown();
            }
        });
        sslSocket.startHandshake();

        Assert.assertTrue(handshakeLatch.await(1, TimeUnit.SECONDS));

        handleCount.set(0);

        // Send TCP FIN without SSL close alert
        socket.close();

        // Sleep for a while to detect eventual spin looping
        TimeUnit.SECONDS.sleep(1);

        Assert.assertTrue("endpoint closed", endPointClosed.get());
        Assert.assertEquals("handle() invocations", 1, handleCount.get());
    }


    @Test
    public void testTruncationAttackBeforeHandshake() throws Exception
    {
        Socket socket = new Socket("localhost", connector.getLocalPort());
        SSLSocket sslSocket = (SSLSocket)sslContext.getSocketFactory().createSocket(socket, socket.getInetAddress().getHostName(), socket.getPort(), true);
        sslSocket.setUseClientMode(true);

        // Wait for the socket to be connected
        TimeUnit.SECONDS.sleep(1);

        handleCount.set(0);

        // Send TCP FIN without SSL close alert
        socket.close();

        // Sleep for a while to detect eventual spin looping
        TimeUnit.SECONDS.sleep(1);

        Assert.assertEquals("handle() invocations", 1, handleCount.get());
        Assert.assertTrue("endpoint not closed", endPointClosed.get());
    }

    private class EmptyHandler extends AbstractHandler
    {
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
        }
    }
}
