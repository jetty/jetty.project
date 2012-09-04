package org.eclipse.jetty.client;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.SelectChannelConnector;
import org.eclipse.jetty.server.Server;
import org.junit.After;

public class AbstractHttpClientTest
{
    protected Server server;
    protected HttpClient client;
    protected NetworkConnector connector;

    public void start(Handler handler) throws Exception
    {
        server = new Server();
        connector = new SelectChannelConnector(server);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();

        client = new HttpClient();
        client.start();
    }

    @After
    public void destroy() throws Exception
    {
        if (client != null)
            client.stop();
        if (server != null)
            server.stop();
    }
}
