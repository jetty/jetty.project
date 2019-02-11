package org.eclipse.jetty.websocket.core.proxy;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

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
    private WebSocketCoreClient client = new WebSocketCoreClient();
    private CoreSession clientSession;
    private AtomicReference<CoreSession> serverSession = new AtomicReference<>();
    private AtomicReference<Callback> closeFrameCallback = new AtomicReference<>();

    public ProxyFrameHandler()
    {
        try
        {
            serverUri = new URI("ws://localhost:8080/server");
            client.start();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
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
                    // We have failed to create the client so onClosed will never be called
                    // so it is our responsibility to close the WebSocketCoreClient
                    try
                    {
                        client.stop();
                    }
                    catch (Exception e)
                    {
                        t.addSuppressed(e);
                    }

                    // If an onError callback was waiting to be completed in serverToProxyFH onOpen, then we must fail it.
                    CoreSession session = this.serverSession.get();
                    if (session instanceof FailedCoreSession)
                    {
                        FailedCoreSession failedSession = (FailedCoreSession)session;
                        failedSession.failed(t);
                        t.addSuppressed(failedSession.getThrowable());
                    }
                    else
                        throw new IllegalStateException("onOpen was called but this callback was failed?");

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
            clientSession.close(CloseStatus.SERVER_ERROR, e.getMessage(), Callback.from(callback,e));
        }
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        System.err.println(name + " onFrame(): " + frame);
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

        if (!serverSession.compareAndSet(null, new FailedCoreSession(cause, callback)))
            serverSession.get().close(CloseStatus.SHUTDOWN, cause.getMessage(), callback);
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

            try
            {
                client.stop();
                callback.succeeded();
            }
            catch (Exception e)
            {
                callback.failed(e);
            }
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
