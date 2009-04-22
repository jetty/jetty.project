package org.eclipse.jetty;

import java.lang.management.ManagementFactory;

import javax.management.MBeanServer;

import org.eclipse.jetty.http.security.Password;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.webapp.WebAppContext;

public class DemoServer
{
    public static void main(String[] args)
        throws Exception
    {
        if (args.length!=1)
        {
            System.err.println("Usage - java "+DemoServer.class+" webappdir|war");
            System.exit(1);
        }
            
        Server server = new Server();
        
        // setup JMX
        MBeanServer mbeanS = ManagementFactory.getPlatformMBeanServer();
        MBeanContainer mbeanC = new MBeanContainer(mbeanS);
        server.getContainer().addEventListener(mbeanC);
        server.addBean(mbeanC);

        // setup connector
        SelectChannelConnector connector = new SelectChannelConnector();
        connector.setPort(8080);
        server.addConnector(connector);
        
        // setup Login service
        HashLoginService login = new HashLoginService();
        login.putUser("jetty",new Password("password"),new String[]{"user"});
        login.putUser("admin",new Password("password"),new String[]{"user","admin"});
        server.addBean(login);
        
        // ContextHandlerCollection
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);
        
        // setup webapp
        WebAppContext context = new WebAppContext();
        context.setWar(args[0]); 
        context.setDefaultsDescriptor("../jetty-webapp/src/main/config/etc/webdefault.xml");
        contexts.addHandler(context);
        
        
        // start the server
        server.start(); 
        System.err.println(server.dump());
        server.join();
    }
}
