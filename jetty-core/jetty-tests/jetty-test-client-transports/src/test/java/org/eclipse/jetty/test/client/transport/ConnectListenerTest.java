package org.eclipse.jetty.test.client.transport;

import java.net.ConnectException;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConnectListenerTest
{
    static Server server;
    static ServerConnector serverConnector;
    static ClientConnector clientConnector;
    static CountDownLatch beginLatch = new CountDownLatch(1);
    static CountDownLatch succesfulLatch = new CountDownLatch(1);
    static CountDownLatch failedLatch = new CountDownLatch(1);
    
    @BeforeAll
    public static void start() throws Exception
    {
        server = new Server();
        serverConnector = new ServerConnector(server);
        server.addConnector(serverConnector);
        server.start();
        
        clientConnector = new ClientConnector();
        clientConnector.addEventListener(new ClientConnector.ConnectListener()
        {
            @Override
            public void onConnectBegin(SocketChannel s, SocketAddress a)
            {
                beginLatch.countDown();
            }

            @Override
            public void onConnectSuccess(SocketChannel s)
            {
                succesfulLatch.countDown();
            }

            @Override
            public void onConnectFailure(SocketChannel s, Throwable x)
            {
                failedLatch.countDown();
            }
        });
    }
    
    @AfterAll
    public static void stop() throws Exception
    {
        server.stop();
    }
    
    @Test
    public void testConnectListenerSuccessful() throws Exception
    {
        try (HttpClient client = new HttpClient(new HttpClientTransportOverHTTP(clientConnector)))
        {
            client.start();
            URI uri = new URI("http://localhost:" + serverConnector.getLocalPort());
            Request req = client.newRequest(uri);
            req.send();
            
            assertTrue(beginLatch.await(5, TimeUnit.SECONDS));
            assertTrue(succesfulLatch.await(5, TimeUnit.SECONDS));
            
            client.stop();
        }
    }

    @Test
    public void testConnectListenerFailed() throws Exception
    {
        try (HttpClient client = new HttpClient(new HttpClientTransportOverHTTP(clientConnector)))
        {
            client.start();
            URI uri = new URI("http://localhost:" + (serverConnector.getLocalPort() - 1));
            Request req = client.newRequest(uri);
            try
            {
                req.send();
            }
            catch (Exception e)
            {
                if (e instanceof ConnectException)
                {
                    assertTrue(beginLatch.await(5, TimeUnit.SECONDS));
                    assertTrue(failedLatch.await(5, TimeUnit.SECONDS));
                }
            }
               
            client.stop();
        }
    }
}
