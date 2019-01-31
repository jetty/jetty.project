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
    String name = "[PROXY_SERVER]";

    URI serverUri;
    WebSocketCoreClient client = new WebSocketCoreClient();

    CoreSession clientSession;
    volatile CoreSession serverSession;


    AtomicReference<Callback> closeFrameCallback = new AtomicReference<>();

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
            ClientUpgradeRequest upgradeRequest = ClientUpgradeRequest.from(client, serverUri, new ProxyFrameHandlerClient());
            client.connect(upgradeRequest).whenComplete((s,t)->{
                if (t != null)
                {
                    callback.failed(t);
                }
                else
                {
                    serverSession = s;
                    callback.succeeded();
                }
            });
        }
        catch (IOException e)
        {
            e.printStackTrace();
            clientSession.close(CloseStatus.SERVER_ERROR, "proxy failed to connect to server", Callback.NOOP);
        }
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        System.err.println(name + " onFrame(): " + frame);
        onFrame(serverSession, frame, callback);
    }

    private void onFrame(CoreSession session, Frame frame, Callback callback)
    {
        if (frame.getOpCode() == OpCode.CLOSE)
        {

            Callback closeCallback = Callback.NOOP;

            // If we have already received a close frame then we can succeed both callbacks
            if (!closeFrameCallback.compareAndSet(null, callback))
            {
                closeCallback = Callback.from(()->
                {
                    closeFrameCallback.get().succeeded();
                    callback.succeeded();
                }, (t)->
                {
                    closeFrameCallback.get().failed(t);
                    callback.failed(t);
                });
            }

            session.sendFrame(frame, closeCallback, false);
            return;
        }
        else
        {
            session.sendFrame(Frame.copy(frame), callback, false);
        }
    }

    @Override
    public void onError(Throwable cause, Callback callback)
    {
        System.err.println(name + " onError(): " + cause);
        cause.printStackTrace();
        callback.succeeded();
    }

    @Override
    public void onClosed(CloseStatus closeStatus, Callback callback)
    {
        System.err.println(name + " onClosed(): " + closeStatus);
        callback.succeeded();
    }

    class ProxyFrameHandlerClient implements FrameHandler
    {
        String name = "[PROXY_CLIENT]";

        @Override
        public void onOpen(CoreSession coreSession, Callback callback)
        {
            serverSession = coreSession;
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
            callback.succeeded();
        }

        @Override
        public void onClosed(CloseStatus closeStatus, Callback callback)
        {
            System.err.println(name + " onClosed(): " + closeStatus);
            callback.succeeded();
        }
    }
}
