package org.eclipse.jetty.client;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class HttpSenderTest
{
    private HttpClient client;

    @Before
    public void init() throws Exception
    {
        client = new HttpClient();
        client.start();
    }

    @After
    public void destroy() throws Exception
    {
        client.stop();
    }

    @Test
    public void test_Send_NoRequestContent() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        HttpConnection connection = new HttpConnection(client, endPoint, null);
        Request request = client.newRequest(URI.create("http://localhost/"));
        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(1);
        request.listener(new Request.Listener.Adapter()
        {
            @Override
            public void onHeaders(Request request)
            {
                headersLatch.countDown();
            }

            @Override
            public void onSuccess(Request request)
            {
                successLatch.countDown();
            }
        });
        connection.send(request, null);

        String requestString = endPoint.takeOutputString();
        Assert.assertTrue(requestString.startsWith("GET "));
        Assert.assertTrue(requestString.endsWith("\r\n\r\n"));
        Assert.assertTrue(headersLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(successLatch.await(5, TimeUnit.SECONDS));
    }

    @Slow
    @Test
    public void test_Send_NoRequestContent_IncompleteFlush() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint("", 16);
        HttpConnection connection = new HttpConnection(client, endPoint, null);
        Request request = client.newRequest(URI.create("http://localhost/"));
        connection.send(request, null);

        // This take will free space in the buffer and allow for the write to complete
        StringBuilder builder = new StringBuilder(endPoint.takeOutputString());

        // Wait for the write to complete
        TimeUnit.SECONDS.sleep(1);

        String chunk = endPoint.takeOutputString();
        while (chunk.length() > 0)
        {
            builder.append(chunk);
            chunk = endPoint.takeOutputString();
        }

        String requestString = builder.toString();
        Assert.assertTrue(requestString.startsWith("GET "));
        Assert.assertTrue(requestString.endsWith("\r\n\r\n"));
    }

    @Test
    public void test_Send_NoRequestContent_Exception() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        // Shutdown output to trigger the exception on write
        endPoint.shutdownOutput();
        HttpConnection connection = new HttpConnection(client, endPoint, null);
        Request request = client.newRequest(URI.create("http://localhost/"));
        final CountDownLatch failureLatch = new CountDownLatch(2);
        request.listener(new Request.Listener.Adapter()
        {
            @Override
            public void onFailure(Request request, Throwable x)
            {
                failureLatch.countDown();
            }
        });
        connection.send(request, new Response.Listener.Adapter()
        {
            @Override
            public void onFailure(Response response, Throwable failure)
            {
                failureLatch.countDown();
            }
        });

        Assert.assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_Send_NoRequestContent_IncompleteFlush_Exception() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint("", 16);
        HttpConnection connection = new HttpConnection(client, endPoint, null);
        Request request = client.newRequest(URI.create("http://localhost/"));
        final CountDownLatch failureLatch = new CountDownLatch(2);
        request.listener(new Request.Listener.Adapter()
        {
            @Override
            public void onFailure(Request request, Throwable x)
            {
                failureLatch.countDown();
            }
        });
        connection.send(request, new Response.Listener.Adapter()
        {
            @Override
            public void onFailure(Response response, Throwable failure)
            {
                failureLatch.countDown();
            }
        });

        // Shutdown output to trigger the exception on write
        endPoint.shutdownOutput();
        // This take will free space in the buffer and allow for the write to complete
        // although it will fail because we shut down the output
        endPoint.takeOutputString();

        Assert.assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_Send_SmallRequestContent_InOneBuffer() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        HttpConnection connection = new HttpConnection(client, endPoint, null);
        Request request = client.newRequest(URI.create("http://localhost/"));
        String content = "abcdef";
        request.content(new ByteBufferContentProvider(ByteBuffer.wrap(content.getBytes("UTF-8"))));
        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(1);
        request.listener(new Request.Listener.Adapter()
        {
            @Override
            public void onHeaders(Request request)
            {
                headersLatch.countDown();
            }

            @Override
            public void onSuccess(Request request)
            {
                successLatch.countDown();
            }
        });
        connection.send(request, null);

        String requestString = endPoint.takeOutputString();
        Assert.assertTrue(requestString.startsWith("GET "));
        Assert.assertTrue(requestString.endsWith("\r\n\r\n" + content));
        Assert.assertTrue(headersLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(successLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_Send_SmallRequestContent_InTwoBuffers() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        HttpConnection connection = new HttpConnection(client, endPoint, null);
        Request request = client.newRequest(URI.create("http://localhost/"));
        String content1 = "0123456789";
        String content2 = "abcdef";
        request.content(new ByteBufferContentProvider(ByteBuffer.wrap(content1.getBytes("UTF-8")), ByteBuffer.wrap(content2.getBytes("UTF-8"))));
        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(1);
        request.listener(new Request.Listener.Adapter()
        {
            @Override
            public void onHeaders(Request request)
            {
                headersLatch.countDown();
            }

            @Override
            public void onSuccess(Request request)
            {
                successLatch.countDown();
            }
        });
        connection.send(request, null);

        String requestString = endPoint.takeOutputString();
        Assert.assertTrue(requestString.startsWith("GET "));
        Assert.assertTrue(requestString.endsWith("\r\n\r\n" + content1 + content2));
        Assert.assertTrue(headersLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(successLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_Send_SmallRequestContent_Chunked_InTwoChunks() throws Exception
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        HttpConnection connection = new HttpConnection(client, endPoint, null);
        Request request = client.newRequest(URI.create("http://localhost/"));
        String content1 = "0123456789";
        String content2 = "ABCDEF";
        request.content(new ByteBufferContentProvider(ByteBuffer.wrap(content1.getBytes("UTF-8")), ByteBuffer.wrap(content2.getBytes("UTF-8")))
        {
            @Override
            public long length()
            {
                return -1;
            }
        });
        final CountDownLatch headersLatch = new CountDownLatch(1);
        final CountDownLatch successLatch = new CountDownLatch(1);
        request.listener(new Request.Listener.Adapter()
        {
            @Override
            public void onHeaders(Request request)
            {
                headersLatch.countDown();
            }

            @Override
            public void onSuccess(Request request)
            {
                successLatch.countDown();
            }
        });
        connection.send(request, null);

        String requestString = endPoint.takeOutputString();
        Assert.assertTrue(requestString.startsWith("GET "));
        String content = Integer.toHexString(content1.length()).toUpperCase() + "\r\n" + content1 + "\r\n";
        content += Integer.toHexString(content2.length()).toUpperCase() + "\r\n" + content2 + "\r\n";
        content += "0\r\n\r\n";
        Assert.assertTrue(requestString.endsWith("\r\n\r\n" + content));
        Assert.assertTrue(headersLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(successLatch.await(5, TimeUnit.SECONDS));
    }
}
