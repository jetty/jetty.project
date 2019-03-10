package org.eclipse.jetty.websocket.tests;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

@WebSocket
public class EventSocket
{
    private static Logger LOG = Log.getLogger(EventSocket.class);

    protected Session session;
    private String behavior;
    public volatile Throwable failure = null;

    public BlockingQueue<String> receivedMessages = new BlockingArrayQueue<>();

    public CountDownLatch open = new CountDownLatch(1);
    public CountDownLatch error = new CountDownLatch(1);
    public CountDownLatch closed = new CountDownLatch(1);

    @OnWebSocketConnect
    public void onOpen(Session session)
    {
        this.session = session;
        behavior = session.getPolicy().getBehavior().name();
        LOG.info("{}  onOpen(): {}", toString(), session);
        open.countDown();
    }

    @OnWebSocketMessage
    public void onMessage(String message) throws IOException
    {
        LOG.info("{}  onMessage(): {}", toString(), message);
        receivedMessages.offer(message);
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason)
    {
        LOG.info("{}  onClose(): {}:{}", toString(), statusCode, reason);
        closed.countDown();
    }

    @OnWebSocketError
    public void onError(Throwable cause)
    {
        LOG.info("{}  onError(): {}", toString(), cause);
        failure = cause;
        error.countDown();
    }

    @Override
    public String toString()
    {
        return String.format("[%s@%s]", behavior, Integer.toHexString(hashCode()));
    }

    @WebSocket
    public static class EchoSocket extends EventSocket
    {
        @Override
        public void onMessage(String message) throws IOException
        {
            super.onMessage(message);
            session.getRemote().sendStringByFuture(message);
        }
    }
}
