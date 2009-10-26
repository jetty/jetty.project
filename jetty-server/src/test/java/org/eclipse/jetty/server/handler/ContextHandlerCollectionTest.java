// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.server.handler;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;

public class ContextHandlerCollectionTest extends TestCase
{
    public void testVirtualHostNormalization() throws Exception
    {
        Server server = new Server();
        LocalConnector connector = new LocalConnector();
        server.setConnectors(new Connector[]
        { connector });

        ContextHandler contextA = new ContextHandler("/");
        contextA.setVirtualHosts(new String[]
        { "www.example.com" });
        IsHandledHandler handlerA = new IsHandledHandler();
        contextA.setHandler(handlerA);

        ContextHandler contextB = new ContextHandler("/");
        IsHandledHandler handlerB = new IsHandledHandler();
        contextB.setHandler(handlerB);
        contextB.setVirtualHosts(new String[]
        { "www.example2.com." });

        ContextHandler contextC = new ContextHandler("/");
        IsHandledHandler handlerC = new IsHandledHandler();
        contextC.setHandler(handlerC);

        ContextHandlerCollection c = new ContextHandlerCollection();

        c.addHandler(contextA);
        c.addHandler(contextB);
        c.addHandler(contextC);

        server.setHandler(c);

        try
        {
            server.start();
            connector.getResponses("GET / HTTP/1.1\n" + "Host: www.example.com.\n\n");

            assertTrue(handlerA.isHandled());
            assertFalse(handlerB.isHandled());
            assertFalse(handlerC.isHandled());

            handlerA.reset();
            handlerB.reset();
            handlerC.reset();

            connector.getResponses("GET / HTTP/1.1\n" + "Host: www.example2.com\n\n");

            assertFalse(handlerA.isHandled());
            assertTrue(handlerB.isHandled());
            assertFalse(handlerC.isHandled());

        }
        finally
        {
            server.stop();
        }

    }
    
    public void testVirtualHostWildcard() throws Exception
    {
        Server server = new Server();
        LocalConnector connector = new LocalConnector();
        server.setConnectors(new Connector[] { connector });
        
        ContextHandler context = new ContextHandler("/");
        
        IsHandledHandler handler = new IsHandledHandler();
        context.setHandler(handler);

        ContextHandlerCollection c = new ContextHandlerCollection();
        c.addHandler(context);
        
        server.setHandler(c);

        try
        {
            server.start();
            checkWildcardHost(true,server,null,new String[] {"example.com", ".example.com", "vhost.example.com"});
            checkWildcardHost(false,server,new String[] {null},new String[] {"example.com", ".example.com", "vhost.example.com"});
            
            checkWildcardHost(true,server,new String[] {"example.com", "*.example.com"}, new String[] {"example.com", ".example.com", "vhost.example.com"});
            checkWildcardHost(false,server,new String[] {"example.com", "*.example.com"}, new String[] {"badexample.com", ".badexample.com", "vhost.badexample.com"});
            
            checkWildcardHost(false,server,new String[] {"*."}, new String[] {"anything.anything"});
            
            checkWildcardHost(true,server,new String[] {"*.example.com"}, new String[] {"vhost.example.com", ".example.com"});
            checkWildcardHost(false,server,new String[] {"*.example.com"}, new String[] {"vhost.www.example.com", "example.com", "www.vhost.example.com"});

            checkWildcardHost(true,server,new String[] {"*.sub.example.com"}, new String[] {"vhost.sub.example.com", ".sub.example.com"});
            checkWildcardHost(false,server,new String[] {"*.sub.example.com"}, new String[] {".example.com", "sub.example.com", "vhost.example.com"});
            
            checkWildcardHost(false,server,new String[] {"example.*.com","example.com.*"}, new String[] {"example.vhost.com", "example.com.vhost", "example.com"});            
        }
        finally
        {
            server.stop();
        }
    }

    private void checkWildcardHost(boolean succeed, Server server, String[] contextHosts, String[] requestHosts) throws Exception
    {
        LocalConnector connector = (LocalConnector)server.getConnectors()[0];
        ContextHandlerCollection handlerCollection = (ContextHandlerCollection)server.getHandler();
        ContextHandler context = (ContextHandler)handlerCollection.getHandlers()[0];
        IsHandledHandler handler = (IsHandledHandler)context.getHandler();

        context.setVirtualHosts(contextHosts);
        // trigger this manually; it's supposed to be called when adding the handler
        handlerCollection.mapContexts();
        
        for(String host : requestHosts)
        {
            connector.getResponses("GET / HTTP/1.1\n" + "Host: "+host+"\n\n");
            if(succeed) 
                assertTrue("'"+host+"' should have been handled.",handler.isHandled());
            else
                assertFalse("'"+host + "' should not have been handled.", handler.isHandled());
            handler.reset();
        }

    }
    
    public static final class IsHandledHandler extends AbstractHandler
    {
        private boolean handled;

        public boolean isHandled()
        {
            return handled;
        }

        public void handle(String s, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            this.handled = true;
        }

        public void reset()
        {
            handled = false;
        }
    }

}
