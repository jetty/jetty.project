package org.eclipse.jetty.websocket.core.proxy;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.BlockingQueue;

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

        @Override
        public void onOpen(CoreSession session, Callback callback)
        {
            System.err.println("[Client2Proxy] onOpen: " + session);

            synchronized (lock)
            {
                switch (state)
                {
                    case NOT_OPEN:
                        state = State.CONNECTING;
                        client = session;

                        Callback wrappedOnOpenCallback = new Callback()
                        {
                            @Override
                            public void succeeded()
                            {
                                synchronized (lock)
                                {
                                    switch (state)
                                    {
                                        case CONNECTING:
                                            state = State.OPEN;
                                            callback.succeeded();
                                            break;

                                        case FAILED:
                                            server2Proxy.fail(error, callback);
                                            break;

                                        default:
                                            callback.failed(new IllegalStateException());
                                    }
                                }
                            }

                            @Override
                            public void failed(Throwable x)
                            {
                                synchronized (lock)
                                {
                                    switch (state)
                                    {
                                        case CONNECTING:
                                            state = State.FAILED;
                                            error = x;
                                            callback.failed(x);
                                            break;

                                        case FAILED:
                                            callback.failed(x);
                                            break;

                                        default:
                                            callback.failed(new IllegalStateException());
                                    }
                                }
                            }
                        };

                        server2Proxy.connect(wrappedOnOpenCallback);
                        break;

                    default:
                        throw new IllegalStateException();
                }
            }
        }

        @Override
        public void onFrame(Frame frame, Callback callback)
        {
            System.err.println("[Client2Proxy] onFrame(): " + frame);
            receivedFrames.offer(Frame.copy(frame));

            synchronized (lock)
            {
                switch (state)
                {
                    case OPEN:
                        if (frame.getOpCode() == OpCode.CLOSE)
                        {
                            state = State.ICLOSED;
                            closeCallback = callback;
                            server2Proxy.send(frame, Callback.from(()->{}, callback::failed));
                        }
                        else
                        {
                            server2Proxy.send(frame, callback);
                        }
                        break;

                    case OCLOSED:
                        if (frame.getOpCode() == OpCode.CLOSE)
                            state = State.CLOSED;

                        server2Proxy.send(frame, callback);
                        break;

                    case FAILED:
                        callback.failed(error);

                    default:
                        callback.failed(new IllegalStateException());
                }
            }
        }

        @Override
        public void onError(Throwable failure, Callback callback)
        {
            System.err.println("[Client2Proxy] onError(): " + failure);
            failure.printStackTrace();

            synchronized (lock)
            {
                switch (state)
                {
                    case FAILED:
                    case CLOSED:
                        callback.failed(failure);
                        break;

                    default:
                        state = State.FAILED;
                        error = failure;
                        server2Proxy.fail(failure,callback);
                        break;
                }
            }

        }

        public void fail(Throwable failure, Callback callback)
        {
            System.err.println("[Client2Proxy] fail(): " + failure);

            synchronized (lock)
            {
                switch (state)
                {
                    case NOT_OPEN:
                        state = State.FAILED;
                        callback.failed(failure);
                        break;

                    case CONNECTING:
                        state = State.FAILED;
                        callback.failed(failure);
                        break;

                    case OPEN:
                        state = State.FAILED;
                        client.close(CloseStatus.SHUTDOWN, failure.getMessage(), Callback.from(callback, failure));
                        break;

                    case ICLOSED:
                        state = State.FAILED;
                        Callback doubleCallback = Callback.from(callback, closeCallback);
                        client.close(CloseStatus.SHUTDOWN, failure.getMessage(), Callback.from(doubleCallback, failure));

                    case FAILED:
                    case CLOSED:
                    case OCLOSED:
                        state = State.FAILED;
                        callback.failed(failure);
                        break;

                    default:
                        throw new IllegalStateException();
                }
            }
        }

        @Override
        public void onClosed(CloseStatus closeStatus, Callback callback)
        {
            System.err.println("[Client2Proxy] onClosed(): " + closeStatus);

            callback.succeeded();
        }


        public void send(Frame frame, Callback callback)
        {
            System.err.println("[Client2Proxy] onClosed(): " + frame);

            synchronized (lock)
            {
                switch (state)
                {
                    case OPEN:
                        if (frame.getOpCode() == OpCode.CLOSE)
                            state = State.OCLOSED;

                        client.sendFrame(frame, callback, false);
                        break;

                    case ICLOSED:
                        if (frame.getOpCode() == OpCode.CLOSE)
                        {
                            state = State.CLOSED;
                            client.sendFrame(frame, Callback.from(callback, closeCallback), false);
                        }
                        else
                        {
                            client.sendFrame(frame, callback, false);
                        }
                        break;

                    case FAILED:
                        callback.failed(error);
                        break;

                    default:
                        callback.failed(new IllegalStateException());
                }
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

        public void connect(Callback callback)
        {
            System.err.println("[Server2Proxy] connect()");

            synchronized (lock)
            {
                switch (state)
                {
                    case NOT_OPEN:
                        try
                        {
                            state = State.CONNECTING;
                            client.connect(this, serverUri).whenComplete((s,t)->{
                                if (t != null)
                                {
                                    synchronized (lock)
                                    {
                                        switch (state)
                                        {
                                            case CONNECTING:
                                                state = State.FAILED;
                                                callback.failed(t);
                                                break;

                                            case FAILED:
                                                callback.failed(t);
                                                break;

                                            default:
                                                callback.failed(new IllegalStateException());
                                        }
                                    }
                                }
                                else
                                {
                                    synchronized (lock)
                                    {
                                        switch (state)
                                        {
                                            case CONNECTING:
                                                state = State.OPEN;
                                                callback.succeeded();
                                                break;

                                            case FAILED:
                                                s.close(CloseStatus.SHUTDOWN, error.getMessage(), Callback.from(callback, error));
                                                break;

                                            default:
                                                callback.failed(new IllegalStateException());
                                        }
                                    }
                                }
                            });
                        }
                        catch (IOException e)
                        {
                            state = State.FAILED;
                            callback.failed(e);
                        }
                        break;

                    case FAILED:
                        callback.failed(error);
                        break;

                    default:
                        throw new IllegalStateException();
                }
            }
        }

        @Override
        public void onOpen(CoreSession session, Callback callback)
        {
            System.err.println("[Server2Proxy] onOpen(): " + session);

            synchronized (lock)
            {
                switch (state)
                {
                    case CONNECTING:
                        server = session;
                        callback.succeeded();
                        break;

                    case FAILED:
                        callback.failed(error);
                        break;

                    default:
                        callback.failed(new IllegalStateException());
                }
            }
        }

        @Override
        public void onFrame(Frame frame, Callback callback)
        {
            System.err.println("[Server2Proxy] onFrame(): " + frame);
            receivedFrames.offer(Frame.copy(frame));

            synchronized (lock)
            {
                switch (state)
                {
                    case OPEN:
                        if (frame.getOpCode() == OpCode.CLOSE)
                        {
                            state = State.ICLOSED;
                            closeCallback = callback;
                            client2Proxy.send(frame, Callback.from(()->{}, callback::failed));
                        }
                        else
                        {
                            client2Proxy.send(frame, callback);
                        }
                        break;

                    case OCLOSED:
                        if (frame.getOpCode() == OpCode.CLOSE)
                            state = State.CLOSED;

                        client2Proxy.send(frame, callback);
                        break;

                    case FAILED:
                        callback.failed(error);

                    default:
                        callback.failed(new IllegalStateException());
                }
            }
        }

        @Override
        public void onError(Throwable failure, Callback callback)
        {
            System.err.println("[Server2Proxy] onError(): " + failure);
            failure.printStackTrace();

            synchronized (lock)
            {
                switch (state)
                {
                    case FAILED:
                    case CLOSED:
                        callback.failed(failure);
                        break;

                    default:
                        state = State.FAILED;
                        error = failure;
                        client2Proxy.fail(failure,callback);
                        break;
                }
            }
        }

        @Override
        public void onClosed(CloseStatus closeStatus, Callback callback)
        {
            System.err.println("[Server2Proxy] onClosed(): " + closeStatus);

            callback.succeeded();
        }

        public void fail(Throwable failure, Callback callback)
        {
            System.err.println("[Server2Proxy] fail(): " + failure);

            synchronized (lock)
            {
                switch (state)
                {
                    case NOT_OPEN:
                        state = State.FAILED;
                        callback.failed(failure);
                        break;

                    case CONNECTING:
                        state = State.FAILED;
                        callback.failed(failure);
                        break;

                    case OPEN:
                        state = State.FAILED;
                        server.close(CloseStatus.SHUTDOWN, failure.getMessage(), Callback.from(callback, failure));
                        break;

                    case ICLOSED:
                        state = State.FAILED;
                        Callback doubleCallback = Callback.from(callback, closeCallback);
                        server.close(CloseStatus.SHUTDOWN, failure.getMessage(), Callback.from(doubleCallback, failure));

                    case FAILED:
                    case CLOSED:
                    case OCLOSED:
                        state = State.FAILED;
                        callback.failed(failure);
                        break;

                    default:
                        throw new IllegalStateException();
                }
            }
        }

        public void send(Frame frame, Callback callback)
        {
            System.err.println("[Server2Proxy] send(): " + frame);

            synchronized (lock)
            {
                switch (state)
                {
                    case OPEN:
                        if (frame.getOpCode() == OpCode.CLOSE)
                            state = State.OCLOSED;

                        server.sendFrame(frame, callback, false);
                        break;

                    case ICLOSED:
                        if (frame.getOpCode() == OpCode.CLOSE)
                        {
                            state = State.CLOSED;
                            server.sendFrame(frame, Callback.from(callback, closeCallback), false);
                        }
                        else
                        {
                            server.sendFrame(frame, callback, false);
                        }
                        break;

                    case FAILED:
                        callback.failed(error);
                        break;

                    default:
                        callback.failed(new IllegalStateException());
                }
            }
        }
    }
}