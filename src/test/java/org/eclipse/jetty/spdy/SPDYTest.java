package org.eclipse.jetty.spdy;

import java.net.InetSocketAddress;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.junit.After;

public class SPDYTest
{
    private Server server;
    private SPDYClient.Factory clientFactory;

    protected InetSocketAddress startServer(ServerSessionFrameListener listener) throws Exception
    {
        server = new Server();
        Connector connector = new SPDYServerConnector(listener);
        server.addConnector(connector);
        server.start();
        return new InetSocketAddress(connector.getLocalPort());
    }

    protected Session startClient(InetSocketAddress socketAddress, Session.FrameListener frameListener) throws Exception
    {
        if (clientFactory == null)
        {
            clientFactory = new SPDYClient.Factory();
            clientFactory.start();
        }
        return clientFactory.newSPDYClient().connect(socketAddress, frameListener).get();
    }

    @After
    public void destroy() throws Exception
    {
        if (clientFactory != null)
        {
            clientFactory.stop();
            clientFactory.join();
        }
        if (server != null)
        {
            server.stop();
            server.join();
        }
    }
}
