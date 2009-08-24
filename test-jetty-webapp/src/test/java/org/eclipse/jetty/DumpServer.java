package org.eclipse.jetty;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DebugHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.IncludableGzipFilter;

import com.acme.Dump;


public class DumpServer
{
    public static void main(String[] args)
        throws Exception
    {
        Server server = new Server(8080);
        DebugHandler debug = new DebugHandler();
        debug.setOutputStream(System.err);
        server.setHandler(debug);
        
        ServletContextHandler context = new ServletContextHandler(debug,"/",ServletContextHandler.SESSIONS);
        FilterHolder gzip=context.addFilter(IncludableGzipFilter.class,"/*",0);
        gzip.setInitParameter("uncheckedPrintWriter","true");
        context.addServlet(new ServletHolder(new Dump()), "/*");
        
        server.start();
        server.join();
    }

}
