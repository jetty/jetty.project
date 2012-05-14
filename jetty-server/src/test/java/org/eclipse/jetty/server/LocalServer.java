package org.eclipse.jetty.server;

public class LocalServer
{

    public static void main(String[] s) throws Exception
    {        
        Server server = new Server();
        LocalHttpConnector connector = new LocalHttpConnector();
        server.addConnector(connector);
        server.setHandler(new DumpHandler());
        server.start();
        server.dumpStdErr();
        
        System.err.println(connector.getResponses("GET / HTTP/1.0\r\n\r\n"));
        
        server.stop();
    }
}
