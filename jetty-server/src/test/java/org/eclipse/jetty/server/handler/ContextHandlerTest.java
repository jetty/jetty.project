//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
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
            connector.getResponse("GET / HTTP/1.0\n" + "Host: www.example.com.\n\n");

            Assert.assertTrue(handlerA.isHandled());
            Assert.assertFalse(handlerB.isHandled());
            Assert.assertFalse(handlerC.isHandled());

            handlerA.reset();
            handlerB.reset();
            handlerC.reset();

            connector.getResponse("GET / HTTP/1.0\n" + "Host: www.example2.com\n\n");

            Assert.assertFalse(handlerA.isHandled());
            Assert.assertTrue(handlerB.isHandled());
            Assert.assertFalse(handlerC.isHandled());

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
        contextA.setDisplayName("A");
        contextA.setVirtualHosts(new String[]{"www.example.com" });
        IsHandledHandler handlerA = new IsHandledHandler();
        contextA.setHandler(handlerA);

        ContextHandler contextB = new ContextHandler("/");
        contextB.setDisplayName("B");
        IsHandledHandler handlerB = new IsHandledHandler();
        contextB.setHandler(handlerB);
        contextB.setVirtualHosts(new String[]{ "@name" });

        ContextHandler contextC = new ContextHandler("/");
        contextC.setDisplayName("C");
        IsHandledHandler handlerC = new IsHandledHandler();
        contextC.setHandler(handlerC);

        ContextHandler contextD = new ContextHandler("/");
        contextD.setDisplayName("D");
        IsHandledHandler handlerD = new IsHandledHandler();
        contextD.setHandler(handlerD);
        contextD.setVirtualHosts(new String[]{ "www.example.com@name" });
        
        ContextHandler contextE = new ContextHandler("/");
        contextE.setDisplayName("E");
        IsHandledHandler handlerE = new IsHandledHandler();
        contextE.setHandler(handlerE);
        contextE.setVirtualHosts(new String[]{ "*.example.com" });
        
        ContextHandler contextF = new ContextHandler("/");
        contextF.setDisplayName("F");
        IsHandledHandler handlerF = new IsHandledHandler();
        contextF.setHandler(handlerF);
        contextF.setVirtualHosts(new String[]{ "*.example.com@name" });
        
        ContextHandler contextG = new ContextHandler("/");
        contextG.setDisplayName("G");
        IsHandledHandler handlerG = new IsHandledHandler();
        contextG.setHandler(handlerG);
        contextG.setVirtualHosts(new String[]{ "*.com@name" });
        
        ContextHandler contextH = new ContextHandler("/");
        contextH.setDisplayName("H");
        IsHandledHandler handlerH = new IsHandledHandler();
        contextH.setHandler(handlerH);
        contextH.setVirtualHosts(new String[]{ "*.com" });
        
        HandlerCollection c = new HandlerCollection();
        c.addHandler(contextA);
        c.addHandler(contextB);
        c.addHandler(contextC);
        c.addHandler(contextD);
        c.addHandler(contextE);
        c.addHandler(contextF);
        c.addHandler(contextG);
        c.addHandler(contextH);
        
        server.setHandler(c);

        server.start();
        try
        {
            connector.getResponse("GET / HTTP/1.0\n" + "Host: www.example.com.\n\n");
            Assert.assertTrue(handlerA.isHandled());
            Assert.assertFalse(handlerB.isHandled());
            Assert.assertFalse(handlerC.isHandled());
            Assert.assertFalse(handlerD.isHandled());
            Assert.assertFalse(handlerE.isHandled());
            Assert.assertFalse(handlerF.isHandled());
            Assert.assertFalse(handlerG.isHandled());
            Assert.assertFalse(handlerH.isHandled());
            handlerA.reset();
            handlerB.reset();
            handlerC.reset();
            handlerD.reset();
            handlerE.reset();
            handlerF.reset();
            handlerG.reset();
            handlerH.reset();

            connector.getResponse("GET / HTTP/1.0\n" + "Host: localhost\n\n");
            Assert.assertFalse(handlerA.isHandled());
            Assert.assertFalse(handlerB.isHandled());
            Assert.assertTrue(handlerC.isHandled());
            Assert.assertFalse(handlerD.isHandled());
            Assert.assertFalse(handlerE.isHandled());
            Assert.assertFalse(handlerF.isHandled());
            Assert.assertFalse(handlerG.isHandled());
            Assert.assertFalse(handlerH.isHandled());
            handlerA.reset();
            handlerB.reset();
            handlerC.reset();
            handlerD.reset();
            handlerE.reset();
            handlerF.reset();
            handlerG.reset();
            handlerH.reset();

            connectorN.getResponse("GET / HTTP/1.0\n" + "Host: www.example.com.\n\n");
            Assert.assertTrue(handlerA.isHandled());
            Assert.assertFalse(handlerB.isHandled());
            Assert.assertFalse(handlerC.isHandled());
            Assert.assertFalse(handlerD.isHandled());
            Assert.assertFalse(handlerE.isHandled());
            Assert.assertFalse(handlerF.isHandled());
            Assert.assertFalse(handlerG.isHandled());
            Assert.assertFalse(handlerH.isHandled());
            handlerA.reset();
            handlerB.reset();
            handlerC.reset();
            handlerD.reset();
            handlerE.reset();
            handlerF.reset();
            handlerG.reset();
            handlerH.reset();

            connectorN.getResponse("GET / HTTP/1.0\n" + "Host: localhost\n\n");
            Assert.assertFalse(handlerA.isHandled());
            Assert.assertTrue(handlerB.isHandled());
            Assert.assertFalse(handlerC.isHandled());
            Assert.assertFalse(handlerD.isHandled());
            Assert.assertFalse(handlerE.isHandled());
            Assert.assertFalse(handlerF.isHandled());
            Assert.assertFalse(handlerG.isHandled());
            Assert.assertFalse(handlerH.isHandled());
            handlerA.reset();
            handlerB.reset();
            handlerC.reset();
            handlerD.reset();
            handlerE.reset();
            handlerF.reset();
            handlerG.reset();
            handlerH.reset();

        }
        finally
        {
            server.stop();
        }
        
        // Reversed order to check priority when multiple matches
        HandlerCollection d = new HandlerCollection();
        d.addHandler(contextH);
        d.addHandler(contextG);
        d.addHandler(contextF);
        d.addHandler(contextE);
        d.addHandler(contextD);
        d.addHandler(contextC);
        d.addHandler(contextB);
        d.addHandler(contextA);
        
        
        server.setHandler(d);

        server.start();
        try
        {
            connector.getResponse("GET / HTTP/1.0\n" + "Host: www.example.com.\n\n");
            Assert.assertFalse(handlerA.isHandled());
            Assert.assertFalse(handlerB.isHandled());
            Assert.assertFalse(handlerC.isHandled());
            Assert.assertFalse(handlerD.isHandled());
            Assert.assertTrue(handlerE.isHandled());
            Assert.assertFalse(handlerF.isHandled());
            Assert.assertFalse(handlerG.isHandled());
            Assert.assertFalse(handlerH.isHandled());
            handlerA.reset();
            handlerB.reset();
            handlerC.reset();
            handlerD.reset();
            handlerE.reset();
            handlerF.reset();
            handlerG.reset();
            handlerH.reset();

            connector.getResponse("GET / HTTP/1.0\n" + "Host: localhost\n\n");
            Assert.assertFalse(handlerA.isHandled());
            Assert.assertFalse(handlerB.isHandled());
            Assert.assertTrue(handlerC.isHandled());
            Assert.assertFalse(handlerD.isHandled());
            Assert.assertFalse(handlerE.isHandled());
            Assert.assertFalse(handlerF.isHandled());
            Assert.assertFalse(handlerG.isHandled());
            Assert.assertFalse(handlerH.isHandled());
            handlerA.reset();
            handlerB.reset();
            handlerC.reset();
            handlerD.reset();
            handlerE.reset();
            handlerF.reset();
            handlerG.reset();
            handlerH.reset();

            connectorN.getResponse("GET / HTTP/1.0\n" + "Host: www.example.com.\n\n");
            Assert.assertFalse(handlerA.isHandled());
            Assert.assertFalse(handlerB.isHandled());
            Assert.assertFalse(handlerC.isHandled());
            Assert.assertFalse(handlerD.isHandled());
            Assert.assertFalse(handlerE.isHandled());
            Assert.assertTrue(handlerF.isHandled());
            Assert.assertFalse(handlerG.isHandled());
            Assert.assertFalse(handlerH.isHandled());
            handlerA.reset();
            handlerB.reset();
            handlerC.reset();
            handlerD.reset();
            handlerE.reset();
            handlerF.reset();
            handlerG.reset();
            handlerH.reset();

            connectorN.getResponse("GET / HTTP/1.0\n" + "Host: localhost\n\n");
            Assert.assertFalse(handlerA.isHandled());
            Assert.assertFalse(handlerB.isHandled());
            Assert.assertTrue(handlerC.isHandled());
            Assert.assertFalse(handlerD.isHandled());
            Assert.assertFalse(handlerE.isHandled());
            Assert.assertFalse(handlerF.isHandled());
            Assert.assertFalse(handlerG.isHandled());
            Assert.assertFalse(handlerH.isHandled());
            handlerA.reset();
            handlerB.reset();
            handlerC.reset();
            handlerD.reset();
            handlerE.reset();
            handlerF.reset();
            handlerG.reset();
            handlerH.reset();

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

        Assert.assertEquals(rootA._scontext, rootA._scontext.getContext("/"));
        Assert.assertEquals(fooA._scontext, rootA._scontext.getContext("/foo"));
        Assert.assertEquals(foobarA._scontext, rootA._scontext.getContext("/foo/bar"));
        Assert.assertEquals(foobarA._scontext, rootA._scontext.getContext("/foo/bar/bob.jsp"));
        Assert.assertEquals(rootA._scontext, rootA._scontext.getContext("/other"));
        Assert.assertEquals(fooA._scontext, rootA._scontext.getContext("/foo/other"));

        Assert.assertEquals(rootA._scontext, foobarA._scontext.getContext("/"));
        Assert.assertEquals(fooA._scontext, foobarA._scontext.getContext("/foo"));
        Assert.assertEquals(foobarA._scontext, foobarA._scontext.getContext("/foo/bar"));
        Assert.assertEquals(foobarA._scontext, foobarA._scontext.getContext("/foo/bar/bob.jsp"));
        Assert.assertEquals(rootA._scontext, foobarA._scontext.getContext("/other"));
        Assert.assertEquals(fooA._scontext, foobarA._scontext.getContext("/foo/other"));
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
        Assert.assertThat(connector.getResponse("GET / HTTP/1.0\n\n"), Matchers.containsString("ctx=''"));
        Assert.assertThat(connector.getResponse("GET /foo/xxx HTTP/1.0\n\n"), Matchers.containsString("ctx='/foo'"));
        Assert.assertThat(connector.getResponse("GET /foo/bar/xxx HTTP/1.0\n\n"), Matchers.containsString("ctx='/foo/bar'"));

        // If we stop foobar, then requests will be handled by foo
        foobar.stop();
        Assert.assertThat(connector.getResponse("GET / HTTP/1.0\n\n"), Matchers.containsString("ctx=''"));
        Assert.assertThat(connector.getResponse("GET /foo/xxx HTTP/1.0\n\n"), Matchers.containsString("ctx='/foo'"));
        Assert.assertThat(connector.getResponse("GET /foo/bar/xxx HTTP/1.0\n\n"), Matchers.containsString("ctx='/foo'"));

        // If we shutdown foo then requests will be 503'd
        foo.shutdown().get();
        Assert.assertThat(connector.getResponse("GET / HTTP/1.0\n\n"), Matchers.containsString("ctx=''"));
        Assert.assertThat(connector.getResponse("GET /foo/xxx HTTP/1.0\n\n"), Matchers.containsString("503"));
        Assert.assertThat(connector.getResponse("GET /foo/bar/xxx HTTP/1.0\n\n"), Matchers.containsString("503"));

        // If we stop foo then requests will be handled by root
        foo.stop();
        Assert.assertThat(connector.getResponse("GET / HTTP/1.0\n\n"), Matchers.containsString("ctx=''"));
        Assert.assertThat(connector.getResponse("GET /foo/xxx HTTP/1.0\n\n"), Matchers.containsString("ctx=''"));
        Assert.assertThat(connector.getResponse("GET /foo/bar/xxx HTTP/1.0\n\n"), Matchers.containsString("ctx=''"));

        // If we start foo then foobar requests will be handled by foo
        foo.start();
        Assert.assertThat(connector.getResponse("GET / HTTP/1.0\n\n"), Matchers.containsString("ctx=''"));
        Assert.assertThat(connector.getResponse("GET /foo/xxx HTTP/1.0\n\n"), Matchers.containsString("ctx='/foo'"));
        Assert.assertThat(connector.getResponse("GET /foo/bar/xxx HTTP/1.0\n\n"), Matchers.containsString("ctx='/foo'"));

        // If we start foobar then foobar requests will be handled by foobar
        foobar.start();
        Assert.assertThat(connector.getResponse("GET / HTTP/1.0\n\n"), Matchers.containsString("ctx=''"));
        Assert.assertThat(connector.getResponse("GET /foo/xxx HTTP/1.0\n\n"), Matchers.containsString("ctx='/foo'"));
        Assert.assertThat(connector.getResponse("GET /foo/bar/xxx HTTP/1.0\n\n"), Matchers.containsString("ctx='/foo/bar'"));
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

        Assert.assertEquals(rootA._scontext, rootA._scontext.getContext("/"));
        Assert.assertEquals(fooA._scontext, rootA._scontext.getContext("/foo"));
        Assert.assertEquals(foobarA._scontext, rootA._scontext.getContext("/foo/bar"));
        Assert.assertEquals(foobarA._scontext, rootA._scontext.getContext("/foo/bar/bob"));

        Assert.assertEquals(rootA._scontext, rootA._scontext.getContext("/other"));
        Assert.assertEquals(rootB._scontext, rootB._scontext.getContext("/other"));
        Assert.assertEquals(rootC._scontext, rootC._scontext.getContext("/other"));

        Assert.assertEquals(fooB._scontext, rootB._scontext.getContext("/foo/other"));
        Assert.assertEquals(rootC._scontext, rootC._scontext.getContext("/foo/other"));
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
        Assert.assertEquals(1, context.getVirtualHosts().length);

        // test adding two more
        context.addVirtualHosts(new String[] { "foo.com@connector1", "*.example2.com"});
        Assert.assertEquals(3, context.getVirtualHosts().length);

        // test adding existing context
        context.addVirtualHosts(new String[] { "www.example.com" });
        Assert.assertEquals(3, context.getVirtualHosts().length);

        // test removing existing
        context.removeVirtualHosts(new String[] { "*.example2.com" });
        Assert.assertEquals(2, context.getVirtualHosts().length);

        // test removing non-existent
        context.removeVirtualHosts(new String[] { "www.example3.com" });
        Assert.assertEquals(2, context.getVirtualHosts().length);

        // test removing all remaining and resets to null
        context.removeVirtualHosts(new String[] { "www.example.com", "foo.com@connector1" });
        Assert.assertArrayEquals(null, context.getVirtualHosts());

    }

    @Test
    public void testAttributes() throws Exception
    {
        ContextHandler handler = new ContextHandler();
        handler.setServer(new Server());
        handler.setAttribute("aaa","111");
        Assert.assertEquals("111", handler.getServletContext().getAttribute("aaa"));
        Assert.assertEquals(null, handler.getAttribute("bbb"));

        handler.start();

        handler.getServletContext().setAttribute("aaa","000");
        handler.setAttribute("ccc","333");
        handler.getServletContext().setAttribute("ddd","444");
        Assert.assertEquals("111", handler.getServletContext().getAttribute("aaa"));
        Assert.assertEquals(null, handler.getServletContext().getAttribute("bbb"));
        handler.getServletContext().setAttribute("bbb","222");
        Assert.assertEquals("333", handler.getServletContext().getAttribute("ccc"));
        Assert.assertEquals("444", handler.getServletContext().getAttribute("ddd"));

        Assert.assertEquals("111", handler.getAttribute("aaa"));
        Assert.assertEquals(null, handler.getAttribute("bbb"));
        Assert.assertEquals("333", handler.getAttribute("ccc"));
        Assert.assertEquals(null, handler.getAttribute("ddd"));

        handler.stop();

        Assert.assertEquals("111", handler.getServletContext().getAttribute("aaa"));
        Assert.assertEquals(null, handler.getServletContext().getAttribute("bbb"));
        Assert.assertEquals("333", handler.getServletContext().getAttribute("ccc"));
        Assert.assertEquals(null, handler.getServletContext().getAttribute("ddd"));
    }

    @Test
    public void testProtected() throws Exception
    {
        ContextHandler handler = new ContextHandler();
        String[] protectedTargets = {"/foo-inf", "/bar-inf"};
        handler.setProtectedTargets(protectedTargets);

        Assert.assertTrue(handler.isProtectedTarget("/foo-inf/x/y/z"));
        Assert.assertFalse(handler.isProtectedTarget("/foo/x/y/z"));
        Assert.assertTrue(handler.isProtectedTarget("/foo-inf?x=y&z=1"));
        Assert.assertFalse(handler.isProtectedTarget("/foo-inf-bar"));

        protectedTargets = new String[4];
        System.arraycopy(handler.getProtectedTargets(), 0, protectedTargets, 0, 2);
        protectedTargets[2] = "/abc";
        protectedTargets[3] = "/def";
        handler.setProtectedTargets(protectedTargets);

        Assert.assertTrue(handler.isProtectedTarget("/foo-inf/x/y/z"));
        Assert.assertFalse(handler.isProtectedTarget("/foo/x/y/z"));
        Assert.assertTrue(handler.isProtectedTarget("/foo-inf?x=y&z=1"));
        Assert.assertTrue(handler.isProtectedTarget("/abc/124"));
        Assert.assertTrue(handler.isProtectedTarget("//def"));

        Assert.assertTrue(handler.isProtectedTarget("/ABC/7777"));
    }

    @Test
    public void testIsShutdown()
    {
        ContextHandler handler = new ContextHandler();
        Assert.assertEquals(false, handler.isShutdown());
    }
    
    @Test
    public void testLogNameFromDisplayName() throws Exception
    {
        ContextHandler handler = new ContextHandler();
        handler.setServer(new Server());
        handler.setDisplayName("An Interesting Project: app.tast.ic");
        try
        {
            handler.start();
            assertThat("handler.get", handler.getLogger().getName(), is(ContextHandler.class.getName() + ".An_Interesting_Project__app_tast_ic"));
        }
        finally
        {
            handler.stop();
        }
    }
    
    @Test
    public void testLogNameFromContextPath_Deep() throws Exception
    {
        ContextHandler handler = new ContextHandler();
        handler.setServer(new Server());
        handler.setContextPath("/app/tast/ic");
        try
        {
            handler.start();
            assertThat("handler.get", handler.getLogger().getName(), is(ContextHandler.class.getName() + ".app_tast_ic"));
        }
        finally
        {
            handler.stop();
        }
    }
    
    @Test
    public void testLogNameFromContextPath_Root() throws Exception
    {
        ContextHandler handler = new ContextHandler();
        handler.setServer(new Server());
        handler.setContextPath("");
        try
        {
            handler.start();
            assertThat("handler.get", handler.getLogger().getName(), is(ContextHandler.class.getName() + ".ROOT"));
        }
        finally
        {
            handler.stop();
        }
    }
    
    @Test
    public void testLogNameFromContextPath_Undefined() throws Exception
    {
        ContextHandler handler = new ContextHandler();
        handler.setServer(new Server());
        try
        {
            handler.start();
            assertThat("handler.get", handler.getLogger().getName(), is(ContextHandler.class.getName() + ".ROOT"));
        }
        finally
        {
            handler.stop();
        }
    }
    
    @Test
    public void testLogNameFromContextPath_Empty() throws Exception
    {
        ContextHandler handler = new ContextHandler();
        handler.setServer(new Server());
        handler.setContextPath("");
        try
        {
            handler.start();
            assertThat("handler.get", handler.getLogger().getName(), is(ContextHandler.class.getName() + ".ROOT"));
        }
        finally
        {
            handler.stop();
        }
    }

    private void checkResourcePathsForExampleWebApp(String root) throws IOException
    {
        File testDirectory = setupTestDirectory();

        ContextHandler handler = new ContextHandler();

        Assert.assertTrue("Not a directory " + testDirectory, testDirectory.isDirectory());
        handler.setBaseResource(Resource.newResource(Resource.toURL(testDirectory)));

        List<String> paths = new ArrayList<>(handler.getResourcePaths(root));
        Assert.assertEquals(2, paths.size());

        Collections.sort(paths);
        Assert.assertEquals("/WEB-INF/jsp/", paths.get(0));
        Assert.assertEquals("/WEB-INF/web.xml", paths.get(1));
    }

    private File setupTestDirectory() throws IOException
    {
        File tmpDir = new File( System.getProperty( "basedir",".") + "/target/tmp/ContextHandlerTest" );
        tmpDir=tmpDir.getCanonicalFile();
        if (!tmpDir.exists())
            Assert.assertTrue(tmpDir.mkdirs());
        File tmp = File.createTempFile("cht",null, tmpDir );
        Assert.assertTrue(tmp.delete());
        Assert.assertTrue(tmp.mkdir());
        tmp.deleteOnExit();
        File root = new File(tmp,getClass().getName());
        Assert.assertTrue(root.mkdir());

        File webInf = new File(root,"WEB-INF");
        Assert.assertTrue(webInf.mkdir());

        Assert.assertTrue(new File(webInf, "jsp").mkdir());
        Assert.assertTrue(new File(webInf, "web.xml").createNewFile());

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
            connector.getResponse("GET / HTTP/1.1\n" + "Host: "+host+"\nConnection:close\n\n");
            if(succeed)
                Assert.assertTrue("'" + host + "' should have been handled.", handler.isHandled());
            else
                Assert.assertFalse("'" + host + "' should not have been handled.", handler.isHandled());
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
