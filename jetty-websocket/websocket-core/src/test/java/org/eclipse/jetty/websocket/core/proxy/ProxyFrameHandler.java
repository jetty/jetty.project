package org.eclipse.jetty.websocket.core.proxy;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;

class ProxyFrameHandler implements FrameHandler
{
    private String name = "[ClientToProxy]";
    private URI serverUri;
    private WebSocketCoreClient client;
    private CoreSession clientSession;
    private AtomicReference<CoreSession> serverSession = new AtomicReference<>();
    private AtomicReference<Callback> closeFrameCallback = new AtomicReference<>();

    private static CoreSession EMPTY_SESSION = new CoreSession.Empty();

    protected BlockingQueue<Frame> receivedFrames = new BlockingArrayQueue<>();

    public ProxyFrameHandler(WebSocketCoreClient client, URI serverUri)
    {
        this.client = client;
        this.serverUri = serverUri;
    }

    @Override
    public void onOpen(CoreSession coreSession, Callback callback)
    {
        System.err.println(name + " onOpen: " + coreSession);
        clientSession = coreSession;

        try
        {
            ClientUpgradeRequest upgradeRequest = ClientUpgradeRequest.from(client, serverUri, new ServerToProxyFrameHandler());
            client.connect(upgradeRequest).whenComplete((s, t) ->
            {
                if (t != null)
                {
                    // If an onError callback was waiting to be completed in serverToProxyFH onOpen, then we must fail it.
                    while (true)
                    {
                        CoreSession session = serverSession.get();

                        if (session == null)
                        {
                            if (serverSession.compareAndSet(null, EMPTY_SESSION))
                                break;
                        }
                        else if (session == EMPTY_SESSION)
                        {
                            break;
                        }
                        else
                        {
                            if (serverSession.compareAndSet(session, EMPTY_SESSION))
                            {
                                if (session instanceof FailedCoreSession)
                                {
                                    FailedCoreSession failedSession = (FailedCoreSession)session;
                                    failedSession.failed(t);
                                    t.addSuppressed(failedSession.getThrowable());
                                }

                                break;
                            }
                        }
                    }

                    callback.failed(t);
                }
                else
                {
                    callback.succeeded();
                }
            });
        }
        catch (IOException e)
        {
            callback.failed(e);
        }
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        System.err.println(name + " onFrame(): " + frame);
        receivedFrames.offer(Frame.copy(frame));
        onFrame(serverSession.get(), frame, callback);
    }

    private void onFrame(CoreSession session, Frame frame, Callback callback)
    {
        if (frame.getOpCode() == OpCode.CLOSE)
        {
            if (closeFrameCallback.compareAndSet(null, callback))
                halfClose(session, frame, callback);
            else
                fullClose(session, frame, callback);
        }
        else
        {
            System.err.println(name + " forwardFrame(): " + frame);
            session.sendFrame(frame, callback, false);
        }
    }

    private void halfClose(CoreSession session, Frame frame , Callback callback)
    {
        Callback closeCallback = Callback.from(() -> {}, callback::failed);
        System.err.println(name + " halfClose()");
        session.sendFrame(frame, closeCallback, false);
    }

    private void fullClose(CoreSession session, Frame frame , Callback callback)
    {
        Callback closeCallback = Callback.from(closeFrameCallback.get(), callback);
        System.err.println(name + " fullClose()");
        session.sendFrame(frame, closeCallback, false);
    }


    @Override
    public void onError(Throwable cause, Callback callback)
    {
        System.err.println(name + " onError(): " + cause);
        cause.printStackTrace();

        while (true)
        {
            CoreSession session = serverSession.get();
            if (session == EMPTY_SESSION)
            {
                callback.failed(cause);
                break;
            }
            else if (session == null)
            {
                if (serverSession.compareAndSet(null, new FailedCoreSession(cause, callback)))
                    break;
            }
            else
            {
                if (serverSession.compareAndSet(session, EMPTY_SESSION))
                {
                    serverSession.get().close(CloseStatus.SHUTDOWN, cause.getMessage(), callback);
                    break;
                }
            }
        }
    }

    @Override
    public void onClosed(CloseStatus closeStatus, Callback callback)
    {
        System.err.println(name + " onClosed(): " + closeStatus);
        callback.succeeded();
    }


    class ServerToProxyFrameHandler implements FrameHandler
    {
        String name = "[ServerToProxy]";

        @Override
        public void onOpen(CoreSession coreSession, Callback callback)
        {
            if (!serverSession.compareAndSet(null, coreSession))
            {
                FailedCoreSession session = (FailedCoreSession)serverSession.get();
                session.failed();
                callback.failed(session.getThrowable());
                return;
            }

            callback.succeeded();
        }

        @Override
        public void onFrame(Frame frame, Callback callback)
        {
            System.err.println(name + " onFrame(): " + frame);
            receivedFrames.offer(Frame.copy(frame));
            ProxyFrameHandler.this.onFrame(clientSession, frame, callback);
        }

        @Override
        public void onError(Throwable cause, Callback callback)
        {
            System.err.println(name + " onError(): " + cause);
            cause.printStackTrace();
            clientSession.close(CloseStatus.SERVER_ERROR, cause.getMessage(), callback);
        }

        @Override
        public void onClosed(CloseStatus closeStatus, Callback callback)
        {
            System.err.println(name + " onClosed(): " + closeStatus);
            callback.succeeded();
        }
    }

    static class FailedCoreSession extends CoreSession.Empty
    {
        private Throwable throwable;
        private Callback callback;

        public FailedCoreSession(Throwable throwable, Callback callback)
        {
            this.throwable = throwable;
            this.callback = callback;
        }

        public Throwable getThrowable()
        {
            return throwable;
        }

        public Callback getCallback()
        {
            return callback;
        }

        public void failed(Throwable t)
        {
            throwable.addSuppressed(t);
            failed();
        }

        public void failed()
        {
            callback.failed(throwable);
        }
    }
}
