// ========================================================================
// $Id$
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.Assert;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @version $Revision$
 */
public class ContextHandlerTest
{
    @Test
    public void testGetResourcePathsWhenSuppliedPathEndsInSlash() throws Exception
    {
        checkResourcePathsForExampleWebApp("/WEB-INF/");
    }

    @Test
    public void testGetResourcePathsWhenSuppliedPathDoesNotEndInSlash() throws Exception
    {
        checkResourcePathsForExampleWebApp("/WEB-INF");
    }

    @Test
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

        HandlerCollection c = new HandlerCollection();

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

    @Test
    public void testVirtualHostWildcard() throws Exception
    {
        Server server = new Server();
        LocalConnector connector = new LocalConnector();
        server.setConnectors(new Connector[] { connector });

        ContextHandler context = new ContextHandler("/");

        IsHandledHandler handler = new IsHandledHandler();
        context.setHandler(handler);

        server.setHandler(context);

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

    @Test
    public void testAttributes() throws Exception
    {
        ContextHandler handler = new ContextHandler();
        handler.setAttribute("aaa","111");
        handler.getServletContext().setAttribute("bbb","222");
        assertEquals("111",handler.getServletContext().getAttribute("aaa"));
        assertEquals(null,handler.getAttribute("bbb"));

        handler.start();

        handler.getServletContext().setAttribute("aaa","000");
        handler.setAttribute("ccc","333");
        handler.getServletContext().setAttribute("ddd","444");
        assertEquals("111",handler.getServletContext().getAttribute("aaa"));
        assertEquals(null,handler.getServletContext().getAttribute("bbb"));
        handler.getServletContext().setAttribute("bbb","222");
        assertEquals("333",handler.getServletContext().getAttribute("ccc"));
        assertEquals("444",handler.getServletContext().getAttribute("ddd"));

        assertEquals("111",handler.getAttribute("aaa"));
        assertEquals(null,handler.getAttribute("bbb"));
        assertEquals("333",handler.getAttribute("ccc"));
        assertEquals(null,handler.getAttribute("ddd"));

        handler.stop();

        assertEquals("111",handler.getServletContext().getAttribute("aaa"));
        assertEquals(null,handler.getServletContext().getAttribute("bbb"));
        assertEquals("333",handler.getServletContext().getAttribute("ccc"));
        assertEquals(null,handler.getServletContext().getAttribute("ddd"));
    }

    private void checkResourcePathsForExampleWebApp(String root) throws IOException
    {
        File testDirectory = setupTestDirectory();

        ContextHandler handler = new ContextHandler();

        assertTrue("Not a directory " + testDirectory,testDirectory.isDirectory());
        handler.setBaseResource(Resource.newResource(testDirectory.toURI().toURL()));

        List<String> paths = new ArrayList<String>(handler.getResourcePaths(root));
        assertEquals(2,paths.size());

        Collections.sort(paths);
        assertEquals("/WEB-INF/jsp/",paths.get(0));
        assertEquals("/WEB-INF/web.xml",paths.get(1));
    }

    private File setupTestDirectory() throws IOException
    {
        File tmpDir = new File( System.getProperty( "basedir" ) + "/target/tmp/ContextHandlerTest" );
        if (!tmpDir.exists())
            assertTrue(tmpDir.mkdirs());
        File tmp = File.createTempFile("cht",null, tmpDir );
        assertTrue(tmp.delete());
        assertTrue(tmp.mkdir());
        tmp.deleteOnExit();
        File root = new File(tmp,getClass().getName());
        assertTrue(root.mkdir());

        File webInf = new File(root,"WEB-INF");
        assertTrue(webInf.mkdir());

        assertTrue(new File(webInf,"jsp").mkdir());
        assertTrue(new File(webInf,"web.xml").createNewFile());

        return root;
    }
    
    @Test
    public void testUncheckedPrintWriter() throws Exception
    {
        Server server = new Server();
        LocalConnector connector = new LocalConnector();
        server.setConnectors(new Connector[] { connector });
        ContextHandler context = new ContextHandler("/");
        WriterHandler handler = new WriterHandler();
        context.setHandler(handler);
        server.setHandler(context);

        try
        {
            server.start();
            
            context.setUncheckedPrintWriter(false);
            String response = connector.getResponses("GET / HTTP/1.1\n" + "Host: www.example.com.\n\n");

            Assert.assertTrue(response.indexOf("Goodbye")>0);
            Assert.assertTrue(response.indexOf("dead")<0);
            Assert.assertTrue(handler.error);
            Assert.assertTrue(handler.throwable==null);
            
            context.setUncheckedPrintWriter(true);
            Assert.assertTrue(response.indexOf("Goodbye")>0);
            response = connector.getResponses("GET / HTTP/1.1\n" + "Host: www.example.com.\n\n");

            Assert.assertTrue(response.indexOf("Goodbye")>0);
            Assert.assertTrue(response.indexOf("dead")<0);
            Assert.assertFalse(handler.error);
            Assert.assertFalse(handler.throwable==null);            
        }
        finally
        {
            server.stop();
        }
    }

    private void checkWildcardHost(boolean succeed, Server server, String[] contextHosts, String[] requestHosts) throws Exception
    {
        LocalConnector connector = (LocalConnector)server.getConnectors()[0];
        ContextHandler context = (ContextHandler)server.getHandler();
        context.setVirtualHosts(contextHosts);

        IsHandledHandler handler = (IsHandledHandler)context.getHandler();
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

    private static final class IsHandledHandler extends AbstractHandler
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
    
    private static final class WriterHandler extends AbstractHandler
    {
        boolean error;
        Throwable throwable;


        public void handle(String s, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            error = false;
            throwable=null;
            
            try
            {
                response.setStatus(200);
                response.setContentType("text/plain; charset=utf-8");
                response.setHeader("Connection","close");
                PrintWriter writer = response.getWriter();
                writer.write("Goodbye cruel world\n");
                writer.close();
                response.flushBuffer();
                writer.write("speaking from the dead");
                error=writer.checkError();
            }
            catch(Throwable th)
            {
                throwable=th;
            }
        }
    }
}
