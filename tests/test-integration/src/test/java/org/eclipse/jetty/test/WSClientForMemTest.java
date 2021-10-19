package org.eclipse.jetty.test;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WSClientForMemTest
{
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789{}	\":;<>,.()[]".toCharArray();

    @Test
    public void test() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        WebSocketClient _client = new WebSocketClient(httpClient);
        _client.start();

        int numThreads = 25;
        int maxMessageSize = 1024 * 64;
        for (int msgSize = 1024; msgSize < maxMessageSize; msgSize += 512)
        {
            ContentResponse get = httpClient.GET("http://localhost:8080/setCount?numThreads=" + numThreads);
            assertThat(get.getStatus(), is(200));

            Callback.Completable completableFuture = new Callback.Completable()
            {
                final AtomicInteger count = new AtomicInteger(numThreads);

                @Override
                public void succeeded()
                {
                    if (count.decrementAndGet() == 0)
                        super.succeeded();
                }
            };

            int messageSize = msgSize;
            for (int i = 0; i < numThreads; i++)
            {
                new Thread(() ->
                {
                    try
                    {
                        ClientSocket clientSocket = new ClientSocket();
                        URI uri = URI.create("ws://localhost:8080/websocket");
                        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
                        upgradeRequest.addExtensions("permessage-deflate");
                        Session session = _client.connect(clientSocket, uri, upgradeRequest).get(5, TimeUnit.SECONDS);
                        assertTrue(session.getUpgradeResponse().getExtensions().stream().anyMatch(config -> config.getName().equals("permessage-deflate")));

                        session.getRemote().sendString(randomString(messageSize));
                        assertTrue(clientSocket.closeLatch.await(20, TimeUnit.SECONDS));
                        assertThat(clientSocket.code, is(1000));
                        assertThat(clientSocket.reason, is("success"));
                        completableFuture.complete(null);
                    }
                    catch (Throwable t)
                    {
                        completableFuture.failed(t);
                    }
                }).start();
            }

            completableFuture.get(20, TimeUnit.SECONDS);
        }
    }

    @WebSocket
    public static class ClientSocket
    {

        LinkedBlockingQueue<String> _textMessages = new LinkedBlockingQueue<>();
        private int code;
        private String reason;
        private final CountDownLatch closeLatch = new CountDownLatch(1);

        @OnWebSocketMessage
        public void onMessage(Session session, String message)
        {
            _textMessages.add(message);
        }

        @OnWebSocketError
        public void onError(Throwable t)
        {
            t.printStackTrace();
        }

        @OnWebSocketClose
        public void onClose(int code, String status)
        {
            this.code = code;
            this.reason = status;
            closeLatch.countDown();
        }
    }

    public String randomString(int len)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++)
        {
            sb.append(ALPHABET[(int)(Math.random() * ALPHABET.length)]);
        }
        return sb.toString();
    }
}
