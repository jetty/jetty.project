package org.eclipse.jetty.server;

import org.eclipse.jetty.util.log.Log;

public class ChannelHttpServer
{
    public static void main(String[] s) throws Exception
    {
        System.setProperty("org.eclipse.jetty.LEVEL","DEBUG");
        Log.getRootLogger().setDebugEnabled(true);
        Server server = new Server();
        ChannelHttpConnector connector = new ChannelHttpConnector();
        connector.setPort(8080);
        server.addConnector(connector);
        server.setHandler(new DumpHandler());
        server.start();
        server.dumpStdErr();
        server.join();
    }
}
