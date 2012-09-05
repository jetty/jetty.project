package org.eclipse.jetty.client;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpHeader;
import org.junit.Assert;
import org.junit.Test;

public class HttpConnectionLifecycleTest extends AbstractHttpClientServerTest
{
    @Test
    public void test_SuccessfulRequest_ReturnsConnection() throws Exception
    {
        start(new EmptyHandler());

        String scheme = "http";
        String host = "localhost";
        int port = connector.getLocalPort();
        HttpDestination destination = (HttpDestination)client.getDestination(scheme, host, port);

        final BlockingQueue<Connection> idleConnections = destination.idleConnections();
        Assert.assertEquals(0, idleConnections.size());

        final BlockingQueue<Connection> activeConnections = destination.activeConnections();
        Assert.assertEquals(0, activeConnections.size());

        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(1);
        client.newRequest(host, port).send(new Response.Listener.Adapter()
        {
            @Override
            public void onHeaders(Response response)
            {
                Assert.assertEquals(0, idleConnections.size());
                Assert.assertEquals(1, activeConnections.size());
                headersLatch.countDown();
            }

            @Override
            public void onSuccess(Response response)
            {
                Assert.assertEquals(1, idleConnections.size());
                Assert.assertEquals(0, activeConnections.size());
                successLatch.countDown();
            }
        });

        Assert.assertTrue(headersLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(successLatch.await(5, TimeUnit.SECONDS));

        Assert.assertEquals(1, idleConnections.size());
        Assert.assertEquals(0, activeConnections.size());
    }

    @Test
    public void test_FailedRequest_RemovesConnection() throws Exception
    {
        start(new EmptyHandler());

        String scheme = "http";
        String host = "localhost";
        int port = connector.getLocalPort();
        HttpDestination destination = (HttpDestination)client.getDestination(scheme, host, port);

        final BlockingQueue<Connection> idleConnections = destination.idleConnections();
        Assert.assertEquals(0, idleConnections.size());

        final BlockingQueue<Connection> activeConnections = destination.activeConnections();
        Assert.assertEquals(0, activeConnections.size());

        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch failureLatch = new CountDownLatch(2);
        client.newRequest(host, port).listener(new Request.Listener.Adapter()
        {
            @Override
            public void onBegin(Request request)
            {
                activeConnections.peek().close();
                headersLatch.countDown();
            }

            @Override
            public void onFailure(Request request, Throwable failure)
            {
                failureLatch.countDown();
            }
        }).send(new Response.Listener.Adapter()
        {
            @Override
            public void onFailure(Response response, Throwable failure)
            {
                Assert.assertEquals(0, idleConnections.size());
                Assert.assertEquals(0, activeConnections.size());
                failureLatch.countDown();
            }
        });

        Assert.assertTrue(headersLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(failureLatch.await(5, TimeUnit.SECONDS));

        Assert.assertEquals(0, idleConnections.size());
        Assert.assertEquals(0, activeConnections.size());
    }

    @Test
    public void test_BadRequest_ReturnsConnection() throws Exception
    {
        start(new EmptyHandler());

        String scheme = "http";
        String host = "localhost";
        int port = connector.getLocalPort();
        HttpDestination destination = (HttpDestination)client.getDestination(scheme, host, port);

        final BlockingQueue<Connection> idleConnections = destination.idleConnections();
        Assert.assertEquals(0, idleConnections.size());

        final BlockingQueue<Connection> activeConnections = destination.activeConnections();
        Assert.assertEquals(0, activeConnections.size());

        final CountDownLatch successLatch = new CountDownLatch(1);
        client.newRequest(host, port)
                .listener(new Request.Listener.Adapter()
                {
                    @Override
                    public void onBegin(Request request)
                    {
                        // Remove the host header, this will make the request invalid
                        request.header(HttpHeader.HOST.asString(), null);
                    }
                })
                .send(new Response.Listener.Adapter()
                {
                    @Override
                    public void onSuccess(Response response)
                    {
                        Assert.assertEquals(1, idleConnections.size());
                        Assert.assertEquals(0, activeConnections.size());
                        successLatch.countDown();
                    }
                });

        Assert.assertTrue(successLatch.await(5, TimeUnit.SECONDS));

        Assert.assertEquals(1, idleConnections.size());
        Assert.assertEquals(0, activeConnections.size());
    }
}
