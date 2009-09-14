package org.eclipse.jetty.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import java.net.ServerSocket;
import java.net.Socket;
import junit.framework.TestCase;

/**
 * @version $Revision$ $Date$
 */
public class ConnectionFailedTest extends TestCase
{
    public void testConnectionFailed() throws Exception
    {
        ServerSocket socket = new ServerSocket();
        socket.bind(null);
        int port=socket.getLocalPort();
        socket.close();
        
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        CountDownLatch latch = new CountDownLatch(1);
        HttpExchange exchange = new ConnectionFailedExchange(latch);
        exchange.setAddress(new Address("localhost", port)); 
        exchange.setURI("/");

        httpClient.send(exchange);

        boolean passed = latch.await(1000, TimeUnit.MILLISECONDS);
        assertTrue(passed);

        long wait = 100;
        long maxWait = 10 * wait;
        long curWait = wait;
        while (curWait < maxWait && !exchange.isDone(exchange.getStatus()))
        {
            Thread.sleep(wait);
            curWait += wait;
        }

        assertEquals(HttpExchange.STATUS_EXCEPTED, exchange.getStatus());
    }

    private class ConnectionFailedExchange extends HttpExchange
    {
        private final CountDownLatch latch;

        private ConnectionFailedExchange(CountDownLatch latch)
        {
            this.latch = latch;
        }

        @Override
        protected void onConnectionFailed(Throwable ex)
        {
            latch.countDown();
        }
    }
}
