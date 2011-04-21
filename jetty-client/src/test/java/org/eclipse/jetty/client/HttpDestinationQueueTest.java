package org.eclipse.jetty.client;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.RejectedExecutionException;

import org.junit.Assert;
import org.junit.Test;

public class HttpDestinationQueueTest
{
    @Test
    public void testDestinationMaxQueueSize() throws Exception
    {
        HttpClient client = new HttpClient();
        client.setMaxConnectionsPerAddress(1);
        client.setMaxQueueSizePerAddress(1);
        client.start();

        ServerSocket server = new ServerSocket(0);

        // This will keep the connection busy
        HttpExchange exchange1 = new HttpExchange();
        exchange1.setMethod("GET");
        exchange1.setURL("http://localhost:" + server.getLocalPort() + "/exchange1");
        client.send(exchange1);

        // Read request so we are sure that this exchange is out of the queue
        Socket socket = server.accept();
        byte[] buffer = new byte[1024];
        StringBuilder request = new StringBuilder();
        while (true)
        {
            int read = socket.getInputStream().read(buffer);
            request.append(new String(buffer, 0, read, "UTF-8"));
            if (request.toString().endsWith("\r\n\r\n"))
                break;
        }
        Assert.assertTrue(request.toString().contains("exchange1"));

        // This will be queued
        HttpExchange exchange2 = new HttpExchange();
        exchange2.setMethod("GET");
        exchange2.setURL("http://localhost:" + server.getLocalPort() + "/exchange2");
        client.send(exchange2);

        // This will be rejected, since the connection is busy and the queue is full
        HttpExchange exchange3 = new HttpExchange();
        exchange3.setMethod("GET");
        exchange3.setURL("http://localhost:" + server.getLocalPort() + "/exchange3");
        try
        {
            client.send(exchange3);
            Assert.fail();
        }
        catch (RejectedExecutionException x)
        {
            // Expected
        }

        // Send the response to avoid exceptions in the console
        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes("UTF-8"));
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, exchange1.waitForDone());

        // Be sure that the second exchange can be sent
        request.setLength(0);
        while (true)
        {
            int read = socket.getInputStream().read(buffer);
            request.append(new String(buffer, 0, read, "UTF-8"));
            if (request.toString().endsWith("\r\n\r\n"))
                break;
        }
        Assert.assertTrue(request.toString().contains("exchange2"));

        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));
        socket.close();
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, exchange2.waitForDone());

        server.close();

        client.stop();
    }
}
