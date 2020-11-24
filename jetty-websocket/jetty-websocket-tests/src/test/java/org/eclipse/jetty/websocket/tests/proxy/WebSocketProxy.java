package org.eclipse.jetty.websocket.tests.proxy;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketConnectionListener;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPartialListener;
import org.eclipse.jetty.websocket.api.WebSocketPingPongListener;
import org.eclipse.jetty.websocket.client.WebSocketClient;

public class WebSocketProxy
{
    private final WebSocketClient client = new WebSocketClient();
    private final URI serverUri = URI.create("ws://echo.websocket.org");
    private final ClientToProxy clientToProxy = new ClientToProxy();
    private final ProxyToServer proxyToServer = new ProxyToServer();

    public WebSocketProxy()
    {
        LifeCycle.start(client);
    }

    public WebSocketConnectionListener getWebSocketConnectionListener()
    {
        return clientToProxy;
    }

    public class ClientToProxy implements WebSocketPartialListener, WebSocketPingPongListener
    {
        private Session session;
        private FutureCallback pongWait;

        public Session getSession()
        {
            return session;
        }

        public void receivedPong()
        {
            if (pongWait != null)
            {
                pongWait.succeeded();
                pongWait = null;
            }
        }

        @Override
        public void onWebSocketConnect(Session session)
        {
            Future<Session> connect = null;
            try
            {
                this.session = session;
                connect = client.connect(proxyToServer, serverUri);
                connect.get();
            }
            catch (Exception e)
            {
                if (connect != null)
                    connect.cancel(true);
                throw new WebSocketException(e);
            }
        }

        @Override
        public void onWebSocketPartialBinary(ByteBuffer payload, boolean fin)
        {
            try
            {
                proxyToServer.getSession().getRemote().sendPartialBytes(payload, fin);
            }
            catch (Exception e)
            {
                throw new WebSocketException(e);
            }
        }

        @Override
        public void onWebSocketPartialText(String payload, boolean fin)
        {
            try
            {
                proxyToServer.getSession().getRemote().sendPartialString(payload, fin);
            }
            catch (Exception e)
            {
                throw new WebSocketException(e);
            }
        }

        @Override
        public void onWebSocketPing(ByteBuffer payload)
        {
            try
            {
                proxyToServer.getSession().getRemote().sendPing(payload);
                // Block until we get pong response back from server.
                // An automatic pong will occur from the implementation after we exit from here.
                pongWait.get();
            }
            catch (Exception e)
            {
                throw new WebSocketException(e);
            }
        }

        @Override
        public void onWebSocketPong(ByteBuffer payload)
        {
            try
            {
                // Notify the other side we have received a Pong.
                proxyToServer.receivedPong();
            }
            catch (Exception e)
            {
                throw new WebSocketException(e);
            }
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            cause.printStackTrace();

            try
            {
                // TODO: need to fail ProxyToServer as well.
                if (pongWait != null)
                    pongWait.cancel(true);
            }
            catch (Exception e)
            {
                throw new WebSocketException(e);
            }
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            try
            {
                Session session = proxyToServer.getSession();
                if (session != null)
                    session.close(statusCode, reason);
            }
            catch (Exception e)
            {
                throw new WebSocketException(e);
            }
        }
    }

    public class ProxyToServer implements WebSocketPartialListener, WebSocketPingPongListener
    {
        private Session session;
        private FutureCallback pongWait;

        public Session getSession()
        {
            return session;
        }

        public void receivedPong()
        {
            if (pongWait != null)
            {
                pongWait.succeeded();
                pongWait = null;
            }
        }

        @Override
        public void onWebSocketConnect(Session session)
        {
            this.session = session;
        }

        @Override
        public void onWebSocketPartialBinary(ByteBuffer payload, boolean fin)
        {
            try
            {
                clientToProxy.getSession().getRemote().sendPartialBytes(payload, fin);
            }
            catch (Exception e)
            {
                throw new WebSocketException(e);
            }
        }

        @Override
        public void onWebSocketPartialText(String payload, boolean fin)
        {
            try
            {
                clientToProxy.getSession().getRemote().sendPartialString(payload, fin);
            }
            catch (Exception e)
            {
                throw new WebSocketException(e);
            }
        }

        @Override
        public void onWebSocketPing(ByteBuffer payload)
        {
            try
            {
                clientToProxy.getSession().getRemote().sendPing(payload);
                // Block until we get pong response back from client.
                // An automatic pong will occur from the implementation after we exit from here.
                pongWait.get();
            }
            catch (Exception e)
            {
                throw new WebSocketException(e);
            }
        }

        @Override
        public void onWebSocketPong(ByteBuffer payload)
        {
            try
            {
                // Notify the other side we have received a Pong.
                clientToProxy.receivedPong();
            }
            catch (Exception e)
            {
                throw new WebSocketException(e);
            }
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            cause.printStackTrace();

            try
            {
                // TODO: need to fail ProxyToServer as well.
                if (pongWait != null)
                    pongWait.cancel(true);
            }
            catch (Exception e)
            {
                throw new WebSocketException(e);
            }
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            try
            {
                Session session = clientToProxy.getSession();
                if (session != null)
                    session.close(statusCode, reason);
            }
            catch (Exception e)
            {
                throw new WebSocketException(e);
            }
        }
    }
}
