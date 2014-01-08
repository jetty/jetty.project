//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.Resource;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

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
        LocalConnector connector = new LocalConnector(server);
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
            connector.getResponses("GET / HTTP/1.0\n" + "Host: www.example.com.\n\n");

            assertTrue(handlerA.isHandled());
            assertFalse(handlerB.isHandled());
            assertFalse(handlerC.isHandled());

            handlerA.reset();
            handlerB.reset();
            handlerC.reset();

            connector.getResponses("GET / HTTP/1.0\n" + "Host: www.example2.com\n\n");

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
    public void testNamedConnector() throws Exception
    {
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        LocalConnector connectorN = new LocalConnector(server);
        connectorN.setName("name");
        server.setConnectors(new Connector[] { connector, connectorN });

        ContextHandler contextA = new ContextHandler("/");
        contextA.setVirtualHosts(new String[]{"www.example.com" });
        IsHandledHandler handlerA = new IsHandledHandler();
        contextA.setHandler(handlerA);

        ContextHandler contextB = new ContextHandler("/");
        IsHandledHandler handlerB = new IsHandledHandler();
        contextB.setHandler(handlerB);
        contextB.setVirtualHosts(new String[]{ "@name" });

        ContextHandler contextC = new ContextHandler("/");
        IsHandledHandler handlerC = new IsHandledHandler();
        contextC.setHandler(handlerC);

        HandlerCollection c = new HandlerCollection();
        c.addHandler(contextA);
        c.addHandler(contextB);
        c.addHandler(contextC);
        server.setHandler(c);

        server.start();
        try
        {
            connector.getResponses("GET / HTTP/1.0\n" + "Host: www.example.com.\n\n");
            assertTrue(handlerA.isHandled());
            assertFalse(handlerB.isHandled());
            assertFalse(handlerC.isHandled());
            handlerA.reset();
            handlerB.reset();
            handlerC.reset();

            connector.getResponses("GET / HTTP/1.0\n" + "Host: localhost\n\n");
            assertFalse(handlerA.isHandled());
            assertFalse(handlerB.isHandled());
            assertTrue(handlerC.isHandled());
            handlerA.reset();
            handlerB.reset();
            handlerC.reset();

            connectorN.getResponses("GET / HTTP/1.0\n" + "Host: www.example.com.\n\n");
            assertTrue(handlerA.isHandled());
            assertFalse(handlerB.isHandled());
            assertFalse(handlerC.isHandled());
            handlerA.reset();
            handlerB.reset();
            handlerC.reset();

            connectorN.getResponses("GET / HTTP/1.0\n" + "Host: localhost\n\n");
            assertFalse(handlerA.isHandled());
            assertTrue(handlerB.isHandled());
            assertFalse(handlerC.isHandled());
            handlerA.reset();
            handlerB.reset();
            handlerC.reset();

        }
        finally
        {
            server.stop();
        }

    }

    @Test
    public void testContextGetContext() throws Exception
    {
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.setConnectors(new Connector[] { connector });
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        ContextHandler rootA = new ContextHandler(contexts,"/");
        ContextHandler fooA = new ContextHandler(contexts,"/foo");
        ContextHandler foobarA = new ContextHandler(contexts,"/foo/bar");

        server.start();

        // System.err.println(server.dump());

        Assert.assertEquals(rootA._scontext,rootA._scontext.getContext("/"));
        Assert.assertEquals(fooA._scontext,rootA._scontext.getContext("/foo"));
        Assert.assertEquals(foobarA._scontext,rootA._scontext.getContext("/foo/bar"));
        Assert.assertEquals(foobarA._scontext,rootA._scontext.getContext("/foo/bar/bob.jsp"));
        Assert.assertEquals(rootA._scontext,rootA._scontext.getContext("/other"));
        Assert.assertEquals(fooA._scontext,rootA._scontext.getContext("/foo/other"));

        Assert.assertEquals(rootA._scontext,foobarA._scontext.getContext("/"));
        Assert.assertEquals(fooA._scontext,foobarA._scontext.getContext("/foo"));
        Assert.assertEquals(foobarA._scontext,foobarA._scontext.getContext("/foo/bar"));
        Assert.assertEquals(foobarA._scontext,foobarA._scontext.getContext("/foo/bar/bob.jsp"));
        Assert.assertEquals(rootA._scontext,foobarA._scontext.getContext("/other"));
        Assert.assertEquals(fooA._scontext,foobarA._scontext.getContext("/foo/other"));
    }

    @Test
    public void testLifeCycle() throws Exception
    {
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.setConnectors(new Connector[] { connector });
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        ContextHandler root = new ContextHandler(contexts,"/");
        root.setHandler(new ContextPathHandler());
        ContextHandler foo = new ContextHandler(contexts,"/foo");
        foo.setHandler(new ContextPathHandler());
        ContextHandler foobar = new ContextHandler(contexts,"/foo/bar");
        foobar.setHandler(new ContextPathHandler());

        // check that all contexts start normally
        server.start();
        assertThat(connector.getResponses("GET / HTTP/1.0\n\n"),Matchers.containsString("ctx=''"));
        assertThat(connector.getResponses("GET /foo/xxx HTTP/1.0\n\n"),Matchers.containsString("ctx='/foo'"));
        assertThat(connector.getResponses("GET /foo/bar/xxx HTTP/1.0\n\n"),Matchers.containsString("ctx='/foo/bar'"));

        // If we stop foobar, then requests will be handled by foo
        foobar.stop();
        assertThat(connector.getResponses("GET / HTTP/1.0\n\n"),Matchers.containsString("ctx=''"));
        assertThat(connector.getResponses("GET /foo/xxx HTTP/1.0\n\n"),Matchers.containsString("ctx='/foo'"));
        assertThat(connector.getResponses("GET /foo/bar/xxx HTTP/1.0\n\n"),Matchers.containsString("ctx='/foo'"));

        // If we shutdown foo then requests will be 503'd
        foo.shutdown().get();
        assertThat(connector.getResponses("GET / HTTP/1.0\n\n"),Matchers.containsString("ctx=''"));
        assertThat(connector.getResponses("GET /foo/xxx HTTP/1.0\n\n"),Matchers.containsString("503"));
        assertThat(connector.getResponses("GET /foo/bar/xxx HTTP/1.0\n\n"),Matchers.containsString("503"));

        // If we stop foo then requests will be handled by root
        foo.stop();
        assertThat(connector.getResponses("GET / HTTP/1.0\n\n"),Matchers.containsString("ctx=''"));
        assertThat(connector.getResponses("GET /foo/xxx HTTP/1.0\n\n"),Matchers.containsString("ctx=''"));
        assertThat(connector.getResponses("GET /foo/bar/xxx HTTP/1.0\n\n"),Matchers.containsString("ctx=''"));

        // If we start foo then foobar requests will be handled by foo
        foo.start();
        assertThat(connector.getResponses("GET / HTTP/1.0\n\n"),Matchers.containsString("ctx=''"));
        assertThat(connector.getResponses("GET /foo/xxx HTTP/1.0\n\n"),Matchers.containsString("ctx='/foo'"));
        assertThat(connector.getResponses("GET /foo/bar/xxx HTTP/1.0\n\n"),Matchers.containsString("ctx='/foo'"));

        // If we start foobar then foobar requests will be handled by foobar
        foobar.start();
        assertThat(connector.getResponses("GET / HTTP/1.0\n\n"),Matchers.containsString("ctx=''"));
        assertThat(connector.getResponses("GET /foo/xxx HTTP/1.0\n\n"),Matchers.containsString("ctx='/foo'"));
        assertThat(connector.getResponses("GET /foo/bar/xxx HTTP/1.0\n\n"),Matchers.containsString("ctx='/foo/bar'"));
    }


    @Test
    public void testContextVirtualGetContext() throws Exception
    {
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.setConnectors(new Connector[] { connector });
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        ContextHandler rootA = new ContextHandler(contexts,"/");
        rootA.setVirtualHosts(new String[] {"a.com"});

        ContextHandler rootB = new ContextHandler(contexts,"/");
        rootB.setVirtualHosts(new String[] {"b.com"});

        ContextHandler rootC = new ContextHandler(contexts,"/");
        rootC.setVirtualHosts(new String[] {"c.com"});


        ContextHandler fooA = new ContextHandler(contexts,"/foo");
        fooA.setVirtualHosts(new String[] {"a.com"});

        ContextHandler fooB = new ContextHandler(contexts,"/foo");
        fooB.setVirtualHosts(new String[] {"b.com"});


        ContextHandler foobarA = new ContextHandler(contexts,"/foo/bar");
        foobarA.setVirtualHosts(new String[] {"a.com"});

        server.start();

        // System.err.println(server.dump());

        Assert.assertEquals(rootA._scontext,rootA._scontext.getContext("/"));
        Assert.assertEquals(fooA._scontext,rootA._scontext.getContext("/foo"));
        Assert.assertEquals(foobarA._scontext,rootA._scontext.getContext("/foo/bar"));
        Assert.assertEquals(foobarA._scontext,rootA._scontext.getContext("/foo/bar/bob"));

        Assert.assertEquals(rootA._scontext,rootA._scontext.getContext("/other"));
        Assert.assertEquals(rootB._scontext,rootB._scontext.getContext("/other"));
        Assert.assertEquals(rootC._scontext,rootC._scontext.getContext("/other"));

        Assert.assertEquals(fooB._scontext,rootB._scontext.getContext("/foo/other"));
        Assert.assertEquals(rootC._scontext,rootC._scontext.getContext("/foo/other"));
    }


    @Test
    public void testVirtualHostWildcard() throws Exception
    {
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
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
    public void testVirtualHostManagement() throws Exception
    {
        ContextHandler context = new ContextHandler("/");

        // test singular
        context.setVirtualHosts(new String[] { "www.example.com"} );
        Assert.assertEquals(1,context.getVirtualHosts().length);

        // test adding two more
        context.addVirtualHosts(new String[] { "www.example2.com", "www.example3.com"});
        Assert.assertEquals(3,context.getVirtualHosts().length);

        // test adding existing context
        context.addVirtualHosts(new String[] { "www.example.com" });
        Assert.assertEquals(3,context.getVirtualHosts().length);

        // test removing existing
        context.removeVirtualHosts(new String[] { "www.example3.com" });
        Assert.assertEquals(2,context.getVirtualHosts().length);

        // test removing non-existent
        context.removeVirtualHosts(new String[] { "www.example3.com" });
        Assert.assertEquals(2,context.getVirtualHosts().length);

        // test removing all remaining and resets to null
        context.removeVirtualHosts(new String[] { "www.example.com", "www.example2.com" });
        Assert.assertEquals(null,context.getVirtualHosts());

    }

    @Test
    public void testAttributes() throws Exception
    {
        ContextHandler handler = new ContextHandler();
        handler.setServer(new Server());
        handler.setAttribute("aaa","111");
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

    @Test
    public void testProtected() throws Exception
    {
        ContextHandler handler = new ContextHandler();
        String[] protectedTargets = {"/foo-inf", "/bar-inf"};
        handler.setProtectedTargets(protectedTargets);

        assertTrue(handler.isProtectedTarget("/foo-inf/x/y/z"));
        assertFalse(handler.isProtectedTarget("/foo/x/y/z"));
        assertTrue(handler.isProtectedTarget("/foo-inf?x=y&z=1"));
        assertFalse(handler.isProtectedTarget("/foo-inf-bar"));

        protectedTargets = new String[4];
        System.arraycopy(handler.getProtectedTargets(), 0, protectedTargets, 0, 2);
        protectedTargets[2] = "/abc";
        protectedTargets[3] = "/def";
        handler.setProtectedTargets(protectedTargets);

        assertTrue(handler.isProtectedTarget("/foo-inf/x/y/z"));
        assertFalse(handler.isProtectedTarget("/foo/x/y/z"));
        assertTrue(handler.isProtectedTarget("/foo-inf?x=y&z=1"));
        assertTrue(handler.isProtectedTarget("/abc/124"));
        assertTrue(handler.isProtectedTarget("//def"));

        assertTrue(handler.isProtectedTarget("/ABC/7777"));
    }



    private void checkResourcePathsForExampleWebApp(String root) throws IOException
    {
        File testDirectory = setupTestDirectory();

        ContextHandler handler = new ContextHandler();

        assertTrue("Not a directory " + testDirectory,testDirectory.isDirectory());
        handler.setBaseResource(Resource.newResource(Resource.toURL(testDirectory)));

        List<String> paths = new ArrayList<String>(handler.getResourcePaths(root));
        assertEquals(2,paths.size());

        Collections.sort(paths);
        assertEquals("/WEB-INF/jsp/",paths.get(0));
        assertEquals("/WEB-INF/web.xml",paths.get(1));
    }

    private File setupTestDirectory() throws IOException
    {
        File tmpDir = new File( System.getProperty( "basedir",".") + "/target/tmp/ContextHandlerTest" );
        tmpDir=tmpDir.getCanonicalFile();
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

    private void checkWildcardHost(boolean succeed, Server server, String[] contextHosts, String[] requestHosts) throws Exception
    {
        LocalConnector connector = (LocalConnector)server.getConnectors()[0];
        ContextHandler context = (ContextHandler)server.getHandler();
        context.setVirtualHosts(contextHosts);

        IsHandledHandler handler = (IsHandledHandler)context.getHandler();
        for(String host : requestHosts)
        {
            connector.getResponses("GET / HTTP/1.1\n" + "Host: "+host+"\nConnection:close\n\n");
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

        @Override
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

    private static final class ContextPathHandler extends AbstractHandler
    {
        @Override
        public void handle(String s, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);

            response.setStatus(200);
            response.setContentType("text/plain; charset=utf-8");
            response.setHeader("Connection","close");
            PrintWriter writer = response.getWriter();
            writer.println("ctx='"+request.getContextPath()+"'");
        }
    }
}
