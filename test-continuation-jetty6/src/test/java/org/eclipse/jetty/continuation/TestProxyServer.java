package org.eclipse.jetty.continuation;

import org.eclipse.jetty.servlets.ProxyServlet;
import org.mortbay.jetty.Connector;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.FilterHolder;
import org.mortbay.jetty.servlet.ServletHandler;
import org.mortbay.jetty.servlet.ServletHolder;

public class TestProxyServer
{
    public static void main(String[] args) throws Exception
    {
        Server server = new Server();
        SelectChannelConnector selectChannelConnector = new SelectChannelConnector();
        server.setConnectors(new Connector[]{ selectChannelConnector });
        selectChannelConnector.setPort(8080);
            
        Context servletContext = new Context(Context.NO_SECURITY|Context.NO_SESSIONS);
        server.setHandler(servletContext);
        ServletHandler servletHandler=servletContext.getServletHandler();
        
        
        ServletHolder proxy=new ServletHolder(ProxyServlet.Transparent.class);
        servletHandler.addServletWithMapping(proxy,"/ws/*");
        proxy.setInitParameter("ProxyTo","http://www.webtide.com");
        proxy.setInitParameter("Prefix","/ws");
        
        FilterHolder filter=servletHandler.addFilterWithMapping(ContinuationFilter.class,"/*",0);
        filter.setInitParameter("debug","true");
        
        server.start();
        server.join();
    }
}
