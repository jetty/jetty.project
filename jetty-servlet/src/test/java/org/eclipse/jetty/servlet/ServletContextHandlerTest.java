package org.eclipse.jetty.servlet;

import static org.junit.Assert.assertEquals;

import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandlerContainer;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.session.SessionHandler;
import org.junit.Test;

public class ServletContextHandlerTest
{

    @Test
    public void testFindContainer() throws Exception
    {
        Server server = new Server();

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        ServletContextHandler root = new ServletContextHandler(contexts,"/",ServletContextHandler.SESSIONS);
        
        SessionHandler session = root.getSessionHandler();
        ServletHandler servlet = root.getServletHandler();
        SecurityHandler security = new ConstraintSecurityHandler();
        root.setSecurityHandler(security);
        server.start();
        
        assertEquals(root, AbstractHandlerContainer.findContainerOf(server, ContextHandler.class, session));
        assertEquals(root, AbstractHandlerContainer.findContainerOf(server, ContextHandler.class, security));
        assertEquals(root, AbstractHandlerContainer.findContainerOf(server, ContextHandler.class, servlet));
    }
}
