package org.eclipse.jetty.example.asyncrest;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.webapp.WebAppContext;

public class DemoServer
{
    public static void main(String[] args)
        throws Exception
    {
        String jetty_home = System.getProperty("jetty.home",".");

        Server server = new Server();
        
        Connector connector=new SelectChannelConnector();
        connector.setPort(Integer.getInteger("jetty.port",8080).intValue());
        server.setConnectors(new Connector[]{connector});
        
        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        webapp.setWar(jetty_home+"/target/example-async-rest-webapp-8.0.0.M0-SNAPSHOT");
        
        server.setHandler(webapp);
        
        server.start();
        server.join();
    }
}
