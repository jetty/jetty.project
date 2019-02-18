package org.eclipse.jetty.websocket.core.proxy;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;

class WebSocketProxy
{
    enum State
    {
        NOT_OPEN,
        CONNECTING,
        OPEN,
        ICLOSED,
        OCLOSED,
        CLOSED,
        FAILED
    }

    private final Object lock = new Object();

    WebSocketCoreClient client;
    private URI serverUri;

    public Client2Proxy client2Proxy = new Client2Proxy();
    public Server2Proxy server2Proxy = new Server2Proxy();

    public WebSocketProxy(WebSocketCoreClient client, URI serverUri)
    {
        this.client = client;
        this.serverUri = serverUri;
    }
    class Client2Proxy implements FrameHandler
    {
        private CoreSession client;
        private State state = State.NOT_OPEN;

        private Callback closeCallback;
        private Throwable error;

        public BlockingQueue<Frame> receivedFrames = new BlockingArrayQueue<>();
        protected CountDownLatch closed = new CountDownLatch(1);

        public State getState()
        {
            synchronized (this)
            {
                return state;
            }
        }

        @Override
        public void onOpen(CoreSession session, Callback callback)
        {
            System.err.println(toString() + " onOpen(): " + session);

            Throwable failure = null;
            synchronized (lock)
            {
                switch (state)
                {
                    case NOT_OPEN:
                        state = State.CONNECTING;
                        client = session;
                        break;

                    default:
                        failure = new IllegalStateException();
                        break;
                }
            }

            if (failure != null)
                callback.failed(failure);
            else
                server2Proxy.connect(Callback.from(() -> onOpenSuccess(callback), (t) -> onOpenFail(callback, t)));
        }

        private void onOpenSuccess(Callback callback)
        {
            System.err.println(toString() + " onOpenSuccess()");

            boolean failServer2Proxy = false;
            Throwable failure = null;
            synchronized (lock)
            {
                switch (state)
                {
                    case CONNECTING:
                        state = State.OPEN;
                        break;

                    case FAILED:
                        failure = error;
                        failServer2Proxy = true;
                        break;

                    default:
                        failure = new IllegalStateException();
                        break;
                }
            }

            if (failServer2Proxy)
                server2Proxy.fail(failure, callback);
            else if (failure != null)
                callback.failed(failure);
            else
                callback.succeeded();
        }

        private void onOpenFail(Callback callback, Throwable t)
        {
            System.err.println(toString() + " onOpenFail(): " + t);

            Throwable failure = t;
            synchronized (lock)
            {
                switch (state)
                {
                    case CONNECTING:
                        state = State.FAILED;
                        error = t;
                        break;

                    case FAILED:
                        failure = error;
                        break;

                    default:
                        failure = new IllegalStateException();
                        break;
                }
            }

            callback.failed(failure);
        }

        @Override
        public void onFrame(Frame frame, Callback callback)
        {
            System.err.println(toString() + " onFrame(): " + frame);
            receivedFrames.offer(Frame.copy(frame));

            Callback sendCallback = callback;
            Throwable failure = null;
            synchronized (lock)
            {
                switch (state)
                {
                    case OPEN:
                        if (frame.getOpCode() == OpCode.CLOSE)
                        {
                            state = State.ICLOSED;
                            closeCallback = callback;
                            sendCallback = Callback.from(()->{}, callback::failed);
                        }
                        break;

                    case OCLOSED:
                        if (frame.getOpCode() == OpCode.CLOSE)
                            state = State.CLOSED;
                        break;

                    case FAILED:
                        failure = error;
                        break;

                    default:
                        failure = new IllegalStateException();
                        break;
                }
            }

            if (failure != null)
                callback.failed(failure);
            else
                server2Proxy.send(frame, sendCallback);
        }

        @Override
        public void onError(Throwable failure, Callback callback)
        {
            System.err.println(toString() + " onError(): " + failure);

            boolean failServer2Proxy;
            synchronized (lock)
            {
                switch (state)
                {
                    case FAILED:
                    case CLOSED:
                        failServer2Proxy = false;
                        break;

                    default:
                        state = State.FAILED;
                        error = failure;
                        failServer2Proxy = true;
                        break;
                }
            }

            if (failServer2Proxy)
                server2Proxy.fail(failure,callback);
            else
                callback.failed(failure);
        }

        public void fail(Throwable failure, Callback callback)
        {
            System.err.println(toString() + " fail(): " + failure);

            Callback sendCallback = null;
            synchronized (lock)
            {
                switch (state)
                {
                    case OPEN:
                        state = State.FAILED;
                        sendCallback = Callback.from(callback, failure);
                        break;

                    case ICLOSED:
                        state = State.FAILED;
                        Callback doubleCallback = Callback.from(callback, closeCallback);
                        sendCallback = Callback.from(doubleCallback, failure);
                        break;

                    default:
                        state = State.FAILED;
                        break;
                }
            }

            if (sendCallback != null)
                client.close(CloseStatus.SHUTDOWN, failure.getMessage(), sendCallback);
            else
                callback.failed(failure);
        }

        @Override
        public void onClosed(CloseStatus closeStatus, Callback callback)
        {
            System.err.println(toString() + " onClosed(): " + closeStatus);
            closed.countDown();
            callback.succeeded();
        }

        public void send(Frame frame, Callback callback)
        {
            System.err.println(toString() + " send(): " + frame);

            Callback sendCallback = callback;
            Throwable failure = null;
            synchronized (lock)
            {
                switch (state)
                {
                    case OPEN:
                        if (frame.getOpCode() == OpCode.CLOSE)
                            state = State.OCLOSED;
                        break;

                    case ICLOSED:
                        if (frame.getOpCode() == OpCode.CLOSE)
                        {
                            state = State.CLOSED;
                            sendCallback = Callback.from(callback, closeCallback);
                        }
                        break;

                    case FAILED:
                        failure = error;
                        break;

                    default:
                        failure = new IllegalStateException();
                        break;
                }
            }

            if (failure != null)
                callback.failed(failure);
            else
                client.sendFrame(frame, sendCallback, false);
        }

        @Override
        public String toString()
        {
            synchronized (lock)
            {
                return "[Client2Proxy," + state + "] ";
            }
        }
    }

    class Server2Proxy implements FrameHandler
    {
        private CoreSession server;
        private State state = State.NOT_OPEN;

        private Callback closeCallback;
        private Throwable error;

        public BlockingQueue<Frame> receivedFrames = new BlockingArrayQueue<>();
        protected CountDownLatch closed = new CountDownLatch(1);

        public State getState()
        {
            synchronized (this)
            {
                return state;
            }
        }

        public void connect(Callback callback)
        {
            System.err.println(toString() + " connect()");

            Throwable failure = null;
            synchronized (lock)
            {
                switch (state)
                {
                    case NOT_OPEN:
                        try
                        {
                            state = State.CONNECTING;
                            client.connect(this, serverUri).whenComplete((s,t)->
                            {
                                if (t != null)
                                    onConnectFailure(t, callback);
                                else
                                    onConnectSuccess(s, callback);
                            });
                        }
                        catch (IOException e)
                        {
                            state = State.FAILED;
                            error = e;
                            failure = e;
                        }
                        break;

                    case FAILED:
                        failure = error;
                        break;

                    default:
                        failure = new IllegalStateException();
                        break;
                }
            }

            if (failure != null)
                callback.failed(failure);
        }

        private void onConnectSuccess(CoreSession s, Callback callback)
        {
            System.err.println(toString() + " onConnectSuccess(): " + s);

            Callback sendCallback = null;
            Throwable failure = null;
            synchronized (lock)
            {
                switch (state)
                {
                    case OPEN:
                        break;

                    case FAILED:
                        failure = error;
                        sendCallback = Callback.from(callback, failure);
                        break;

                    default:
                        failure = new IllegalStateException();
                        break;
                }
            }

            if (sendCallback != null)
                s.close(CloseStatus.SHUTDOWN, failure.getMessage(), sendCallback);
            else if (failure != null)
                callback.failed(failure);
            else
                callback.succeeded();
        }

        private void onConnectFailure(Throwable t, Callback callback)
        {
            System.err.println(toString() + " onConnectFailure(): " + t);

            Throwable failure = t;
            synchronized (lock)
            {
                switch (state)
                {
                    case CONNECTING:
                        state = State.FAILED;
                        error = t;
                        break;

                    case FAILED:
                        failure = error;
                        break;

                    default:
                        failure = new IllegalStateException();
                        break;
                }
            }
            callback.failed(failure);
        }

        @Override
        public void onOpen(CoreSession session, Callback callback)
        {
            System.err.println(toString() + " onOpen(): " + session);

            Throwable failure = null;
            synchronized (lock)
            {
                switch (state)
                {
                    case CONNECTING:
                        state = State.OPEN;
                        server = session;
                        break;

                    case FAILED:
                        failure = error;
                        break;

                    default:
                        failure = new IllegalStateException();
                        break;
                }
            }

            if (failure != null)
                callback.failed(failure);
            else
                callback.succeeded();
        }

        @Override
        public void onFrame(Frame frame, Callback callback)
        {
            System.err.println(toString() + " onFrame(): " + frame);
            receivedFrames.offer(Frame.copy(frame));

            Callback sendCallback = callback;
            Throwable failure = null;
            synchronized (lock)
            {
                switch (state)
                {
                    case OPEN:
                        if (frame.getOpCode() == OpCode.CLOSE)
                        {
                            state = State.ICLOSED;
                            closeCallback = callback;
                            sendCallback = Callback.from(()->{}, callback::failed);
                        }
                        break;

                    case OCLOSED:
                        if (frame.getOpCode() == OpCode.CLOSE)
                            state = State.CLOSED;
                        break;

                    case FAILED:
                        failure = error;
                        break;

                    default:
                        failure = new IllegalStateException();
                        break;
                }
            }

            if (failure != null)
                callback.failed(failure);
            else
                client2Proxy.send(frame, sendCallback);

        }

        @Override
        public void onError(Throwable failure, Callback callback)
        {
            System.err.println(toString() + " onError(): " + failure);

            boolean failClient2Proxy = false;
            synchronized (lock)
            {
                switch (state)
                {
                    case FAILED:
                    case CLOSED:
                        break;

                    default:
                        state = State.FAILED;
                        error = failure;
                        failClient2Proxy = true;
                        break;
                }
            }

            if (failClient2Proxy)
                client2Proxy.fail(failure,callback);
            else
                callback.failed(failure);
        }

        @Override
        public void onClosed(CloseStatus closeStatus, Callback callback)
        {
            System.err.println(toString() + " onClosed(): " + closeStatus);
            closed.countDown();
            callback.succeeded();
        }

        public void fail(Throwable failure, Callback callback)
        {
            System.err.println(toString() + " fail(): " + failure);

            Callback sendCallback = null;
            synchronized (lock)
            {
                switch (state)
                {
                    case OPEN:
                        state = State.FAILED;
                        sendCallback = Callback.from(callback, failure);
                        break;

                    case ICLOSED:
                        state = State.FAILED;
                        Callback doubleCallback = Callback.from(callback, closeCallback);
                        sendCallback =  Callback.from(doubleCallback, failure);

                    default:
                        state = State.FAILED;
                        break;
                }
            }

            if (sendCallback != null)
                server.close(CloseStatus.SHUTDOWN, failure.getMessage(), sendCallback);
            else
                callback.failed(failure);
        }

        public void send(Frame frame, Callback callback)
        {
            System.err.println(toString() + " send(): " + frame);

            Callback sendCallback = callback;
            Throwable failure = null;
            synchronized (lock)
            {
                switch (state)
                {
                    case OPEN:
                        if (frame.getOpCode() == OpCode.CLOSE)
                            state = State.OCLOSED;
                        break;

                    case ICLOSED:
                        if (frame.getOpCode() == OpCode.CLOSE)
                        {
                            state = State.CLOSED;
                            sendCallback = Callback.from(callback, closeCallback);
                        }
                        break;

                    case FAILED:
                        failure = error;
                        break;

                    default:
                        failure = new IllegalStateException();
                        break;
                }
            }

            if (failure != null)
                callback.failed(failure);
            else
                server.sendFrame(frame, sendCallback, false);
        }

        @Override
        public String toString()
        {
            synchronized (lock)
            {
                return "[Server2Proxy," + state + "] ";
            }
        }
    }
}