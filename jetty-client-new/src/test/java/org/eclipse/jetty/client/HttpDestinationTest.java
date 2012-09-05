package org.eclipse.jetty.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class HttpDestinationTest extends AbstractHttpClientServerTest
{
    @Before
    public void init() throws Exception
    {
        start(new EmptyHandler());
    }

    @Test
    public void test_FirstAcquire_WithEmptyQueue() throws Exception
    {
        HttpDestination destination = new HttpDestination(client, "http", "localhost", connector.getLocalPort());
        Connection connection = destination.acquire();

        // There are no available existing connections, so acquire() returns null
        Assert.assertNull(connection);

        // There are no queued requests, so the newly created connection will be idle
        connection = destination.idleConnections().poll(5, TimeUnit.SECONDS);
        Assert.assertNotNull(connection);
    }

    @Test
    public void test_SecondAcquire_AfterFirstAcquire_WithEmptyQueue_ReturnsSameConnection() throws Exception
    {
        HttpDestination destination = new HttpDestination(client, "http", "localhost", connector.getLocalPort());
        Connection connection1 = destination.acquire();

        // There are no available existing connections, so acquire() returns null
        Assert.assertNull(connection1);

        // There are no queued requests, so the newly created connection will be idle
        long start = System.nanoTime();
        while (connection1 == null && TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5)
        {
            connection1 = destination.idleConnections().peek();
            TimeUnit.MILLISECONDS.sleep(50);
        }
        Assert.assertNotNull(connection1);

        Connection connection2 = destination.acquire();
        Assert.assertSame(connection1, connection2);
    }

    @Test
    public void test_SecondAcquire_ConcurrentWithFirstAcquire_WithEmptyQueue_CreatesTwoConnections() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        HttpDestination destination = new HttpDestination(client, "http", "localhost", connector.getLocalPort())
        {
            @Override
            protected void process(Connection connection)
            {
                try
                {
                    latch.await(5, TimeUnit.SECONDS);
                    super.process(connection);
                }
                catch (InterruptedException x)
                {
                    x.printStackTrace();
                }
            }
        };
        Connection connection1 = destination.acquire();

        // There are no available existing connections, so acquire() returns null
        Assert.assertNull(connection1);

        Connection connection2 = destination.acquire();
        Assert.assertNull(connection2);

        latch.countDown();

        // There must be 2 idle connections
        Connection connection = destination.idleConnections().poll(5, TimeUnit.SECONDS);
        Assert.assertNotNull(connection);
        connection = destination.idleConnections().poll(5, TimeUnit.SECONDS);
        Assert.assertNotNull(connection);
    }

    @Test
    public void test_Acquire_Release_Acquire_ReturnsSameConnection() throws Exception
    {
        HttpDestination destination = new HttpDestination(client, "http", "localhost", connector.getLocalPort());
        Connection connection1 = destination.acquire();

        // There are no available existing connections, so acquire() returns null
        Assert.assertNull(connection1);

        // There are no queued requests, so the newly created connection will be idle
        long start = System.nanoTime();
        while (connection1 == null && TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5)
        {
            connection1 = destination.idleConnections().peek();
            TimeUnit.MILLISECONDS.sleep(50);
        }
        Assert.assertNotNull(connection1);

        destination.release(connection1);

        Connection connection2 = destination.acquire();
        Assert.assertSame(connection1, connection2);
    }

    @Slow
    @Test
    public void test_IdleConnection_IdleTimeout() throws Exception
    {
        long idleTimeout = 1000;
        client.setIdleTimeout(idleTimeout);

        HttpDestination destination = new HttpDestination(client, "http", "localhost", connector.getLocalPort());
        destination.acquire();

        // There are no queued requests, so the newly created connection will be idle
        Connection connection1 = null;
        long start = System.nanoTime();
        while (connection1 == null && TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5)
        {
            connection1 = destination.idleConnections().peek();
            TimeUnit.MILLISECONDS.sleep(50);
        }
        Assert.assertNotNull(connection1);

        TimeUnit.MILLISECONDS.sleep(2 * idleTimeout);

        connection1 = destination.idleConnections().poll();
        Assert.assertNull(connection1);
    }

}
