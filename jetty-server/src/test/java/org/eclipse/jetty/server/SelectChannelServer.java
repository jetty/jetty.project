package org.eclipse.jetty.server;

import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.log.Log;

public class SelectChannelServer
{
    public static void main(String[] s) throws Exception
    {
        System.setProperty("org.eclipse.jetty.LEVEL","DEBUG");
        Log.getRootLogger().setDebugEnabled(true);
        Server server = new Server();
        SelectChannelConnector connector = new SelectChannelConnector();
        connector.setPort(8080);
        server.addConnector(connector);
        server.start();
        server.join();
    }
}
