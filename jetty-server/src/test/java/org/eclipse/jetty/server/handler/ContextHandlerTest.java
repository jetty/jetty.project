//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.Resource;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            {connector});

        ContextHandler contextA = new ContextHandler("/");
        contextA.setVirtualHosts(new String[]
            {"www.example.com"});
        IsHandledHandler handlerA = new IsHandledHandler();
        contextA.setHandler(handlerA);

        ContextHandler contextB = new ContextHandler("/");
        IsHandledHandler handlerB = new IsHandledHandler();
        contextB.setHandler(handlerB);
        contextB.setVirtualHosts(new String[]
            {"www.example2.com."});

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

            assertTrue(handlerA.isHandled());
            assertFalse(handlerB.isHandled());
            assertFalse(handlerC.isHandled());

            handlerA.reset();
            handlerB.reset();
            handlerC.reset();

            connector.getResponse("GET / HTTP/1.0\n" + "Host: www.example2.com\n\n");

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
        server.setConnectors(new Connector[]{connector, connectorN});

        ContextHandler contextA = new ContextHandler("/");
        contextA.setDisplayName("A");
        contextA.setVirtualHosts(new String[]{"www.example.com"});
        IsHandledHandler handlerA = new IsHandledHandler();
        contextA.setHandler(handlerA);

        ContextHandler contextB = new ContextHandler("/");
        contextB.setDisplayName("B");
        IsHandledHandler handlerB = new IsHandledHandler();
        contextB.setHandler(handlerB);
        contextB.setVirtualHosts(new String[]{"@name"});

        ContextHandler contextC = new ContextHandler("/");
        contextC.setDisplayName("C");
        IsHandledHandler handlerC = new IsHandledHandler();
        contextC.setHandler(handlerC);

        ContextHandler contextD = new ContextHandler("/");
        contextD.setDisplayName("D");
        IsHandledHandler handlerD = new IsHandledHandler();
        contextD.setHandler(handlerD);
        contextD.setVirtualHosts(new String[]{"www.example.com@name"});

        ContextHandler contextE = new ContextHandler("/");
        contextE.setDisplayName("E");
        IsHandledHandler handlerE = new IsHandledHandler();
        contextE.setHandler(handlerE);
        contextE.setVirtualHosts(new String[]{"*.example.com"});

        ContextHandler contextF = new ContextHandler("/");
        contextF.setDisplayName("F");
        IsHandledHandler handlerF = new IsHandledHandler();
        contextF.setHandler(handlerF);
        contextF.setVirtualHosts(new String[]{"*.example.com@name"});

        ContextHandler contextG = new ContextHandler("/");
        contextG.setDisplayName("G");
        IsHandledHandler handlerG = new IsHandledHandler();
        contextG.setHandler(handlerG);
        contextG.setVirtualHosts(new String[]{"*.com@name"});

        ContextHandler contextH = new ContextHandler("/");
        contextH.setDisplayName("H");
        IsHandledHandler handlerH = new IsHandledHandler();
        contextH.setHandler(handlerH);
        contextH.setVirtualHosts(new String[]{"*.com"});

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
            assertTrue(handlerA.isHandled());
            assertFalse(handlerB.isHandled());
            assertFalse(handlerC.isHandled());
            assertFalse(handlerD.isHandled());
            assertFalse(handlerE.isHandled());
            assertFalse(handlerF.isHandled());
            assertFalse(handlerG.isHandled());
            assertFalse(handlerH.isHandled());
            handlerA.reset();
            handlerB.reset();
            handlerC.reset();
            handlerD.reset();
            handlerE.reset();
            handlerF.reset();
            handlerG.reset();
            handlerH.reset();

            connector.getResponse("GET / HTTP/1.0\n" + "Host: localhost\n\n");
            assertFalse(handlerA.isHandled());
            assertFalse(handlerB.isHandled());
            assertTrue(handlerC.isHandled());
            assertFalse(handlerD.isHandled());
            assertFalse(handlerE.isHandled());
            assertFalse(handlerF.isHandled());
            assertFalse(handlerG.isHandled());
            assertFalse(handlerH.isHandled());
            handlerA.reset();
            handlerB.reset();
            handlerC.reset();
            handlerD.reset();
            handlerE.reset();
            handlerF.reset();
            handlerG.reset();
            handlerH.reset();

            connectorN.getResponse("GET / HTTP/1.0\n" + "Host: www.example.com.\n\n");
            assertTrue(handlerA.isHandled());
            assertFalse(handlerB.isHandled());
            assertFalse(handlerC.isHandled());
            assertFalse(handlerD.isHandled());
            assertFalse(handlerE.isHandled());
            assertFalse(handlerF.isHandled());
            assertFalse(handlerG.isHandled());
            assertFalse(handlerH.isHandled());
            handlerA.reset();
            handlerB.reset();
            handlerC.reset();
            handlerD.reset();
            handlerE.reset();
            handlerF.reset();
            handlerG.reset();
            handlerH.reset();

            connectorN.getResponse("GET / HTTP/1.0\n" + "Host: localhost\n\n");
            assertFalse(handlerA.isHandled());
            assertTrue(handlerB.isHandled());
            assertFalse(handlerC.isHandled());
            assertFalse(handlerD.isHandled());
            assertFalse(handlerE.isHandled());
            assertFalse(handlerF.isHandled());
            assertFalse(handlerG.isHandled());
            assertFalse(handlerH.isHandled());
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
            assertFalse(handlerA.isHandled());
            assertFalse(handlerB.isHandled());
            assertFalse(handlerC.isHandled());
            assertFalse(handlerD.isHandled());
            assertTrue(handlerE.isHandled());
            assertFalse(handlerF.isHandled());
            assertFalse(handlerG.isHandled());
            assertFalse(handlerH.isHandled());
            handlerA.reset();
            handlerB.reset();
            handlerC.reset();
            handlerD.reset();
            handlerE.reset();
            handlerF.reset();
            handlerG.reset();
            handlerH.reset();

            connector.getResponse("GET / HTTP/1.0\n" + "Host: localhost\n\n");
            assertFalse(handlerA.isHandled());
            assertFalse(handlerB.isHandled());
            assertTrue(handlerC.isHandled());
            assertFalse(handlerD.isHandled());
            assertFalse(handlerE.isHandled());
            assertFalse(handlerF.isHandled());
            assertFalse(handlerG.isHandled());
            assertFalse(handlerH.isHandled());
            handlerA.reset();
            handlerB.reset();
            handlerC.reset();
            handlerD.reset();
            handlerE.reset();
            handlerF.reset();
            handlerG.reset();
            handlerH.reset();

            connectorN.getResponse("GET / HTTP/1.0\n" + "Host: www.example.com.\n\n");
            assertFalse(handlerA.isHandled());
            assertFalse(handlerB.isHandled());
            assertFalse(handlerC.isHandled());
            assertFalse(handlerD.isHandled());
            assertFalse(handlerE.isHandled());
            assertTrue(handlerF.isHandled());
            assertFalse(handlerG.isHandled());
            assertFalse(handlerH.isHandled());
            handlerA.reset();
            handlerB.reset();
            handlerC.reset();
            handlerD.reset();
            handlerE.reset();
            handlerF.reset();
            handlerG.reset();
            handlerH.reset();

            connectorN.getResponse("GET / HTTP/1.0\n" + "Host: localhost\n\n");
            assertFalse(handlerA.isHandled());
            assertFalse(handlerB.isHandled());
            assertTrue(handlerC.isHandled());
            assertFalse(handlerD.isHandled());
            assertFalse(handlerE.isHandled());
            assertFalse(handlerF.isHandled());
            assertFalse(handlerG.isHandled());
            assertFalse(handlerH.isHandled());
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
        server.setConnectors(new Connector[]{connector});
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        ContextHandler rootA = new ContextHandler(contexts, "/");
        ContextHandler fooA = new ContextHandler(contexts, "/foo");
        ContextHandler foobarA = new ContextHandler(contexts, "/foo/bar");

        server.start();

        // System.err.println(server.dump());

        assertEquals(rootA._scontext, rootA._scontext.getContext("/"));
        assertEquals(fooA._scontext, rootA._scontext.getContext("/foo"));
        assertEquals(foobarA._scontext, rootA._scontext.getContext("/foo/bar"));
        assertEquals(foobarA._scontext, rootA._scontext.getContext("/foo/bar/bob.jsp"));
        assertEquals(rootA._scontext, rootA._scontext.getContext("/other"));
        assertEquals(fooA._scontext, rootA._scontext.getContext("/foo/other"));

        assertEquals(rootA._scontext, foobarA._scontext.getContext("/"));
        assertEquals(fooA._scontext, foobarA._scontext.getContext("/foo"));
        assertEquals(foobarA._scontext, foobarA._scontext.getContext("/foo/bar"));
        assertEquals(foobarA._scontext, foobarA._scontext.getContext("/foo/bar/bob.jsp"));
        assertEquals(rootA._scontext, foobarA._scontext.getContext("/other"));
        assertEquals(fooA._scontext, foobarA._scontext.getContext("/foo/other"));
    }

    @Test
    public void testLifeCycle() throws Exception
    {
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.setConnectors(new Connector[]{connector});
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        ContextHandler root = new ContextHandler(contexts, "/");
        root.setHandler(new ContextPathHandler());
        ContextHandler foo = new ContextHandler(contexts, "/foo");
        foo.setHandler(new ContextPathHandler());
        ContextHandler foobar = new ContextHandler(contexts, "/foo/bar");
        foobar.setHandler(new ContextPathHandler());

        // check that all contexts start normally
        server.start();
        assertThat(connector.getResponse("GET / HTTP/1.0\n\n"), Matchers.containsString("ctx=''"));
        assertThat(connector.getResponse("GET /foo/xxx HTTP/1.0\n\n"), Matchers.containsString("ctx='/foo'"));
        assertThat(connector.getResponse("GET /foo/bar/xxx HTTP/1.0\n\n"), Matchers.containsString("ctx='/foo/bar'"));

        // If we stop foobar, then requests will be handled by foo
        foobar.stop();
        assertThat(connector.getResponse("GET / HTTP/1.0\n\n"), Matchers.containsString("ctx=''"));
        assertThat(connector.getResponse("GET /foo/xxx HTTP/1.0\n\n"), Matchers.containsString("ctx='/foo'"));
        assertThat(connector.getResponse("GET /foo/bar/xxx HTTP/1.0\n\n"), Matchers.containsString("ctx='/foo'"));

        // If we shutdown foo then requests will be 503'd
        foo.shutdown().get();
        assertThat(connector.getResponse("GET / HTTP/1.0\n\n"), Matchers.containsString("ctx=''"));
        assertThat(connector.getResponse("GET /foo/xxx HTTP/1.0\n\n"), Matchers.containsString("503"));
        assertThat(connector.getResponse("GET /foo/bar/xxx HTTP/1.0\n\n"), Matchers.containsString("503"));

        // If we stop foo then requests will be handled by root
        foo.stop();
        assertThat(connector.getResponse("GET / HTTP/1.0\n\n"), Matchers.containsString("ctx=''"));
        assertThat(connector.getResponse("GET /foo/xxx HTTP/1.0\n\n"), Matchers.containsString("ctx=''"));
        assertThat(connector.getResponse("GET /foo/bar/xxx HTTP/1.0\n\n"), Matchers.containsString("ctx=''"));

        // If we start foo then foobar requests will be handled by foo
        foo.start();
        assertThat(connector.getResponse("GET / HTTP/1.0\n\n"), Matchers.containsString("ctx=''"));
        assertThat(connector.getResponse("GET /foo/xxx HTTP/1.0\n\n"), Matchers.containsString("ctx='/foo'"));
        assertThat(connector.getResponse("GET /foo/bar/xxx HTTP/1.0\n\n"), Matchers.containsString("ctx='/foo'"));

        // If we start foobar then foobar requests will be handled by foobar
        foobar.start();
        assertThat(connector.getResponse("GET / HTTP/1.0\n\n"), Matchers.containsString("ctx=''"));
        assertThat(connector.getResponse("GET /foo/xxx HTTP/1.0\n\n"), Matchers.containsString("ctx='/foo'"));
        assertThat(connector.getResponse("GET /foo/bar/xxx HTTP/1.0\n\n"), Matchers.containsString("ctx='/foo/bar'"));
    }

    @Test
    public void testContextInitializationDestruction() throws Exception
    {
        Server server = new Server();
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        ContextHandler noServlets = new ContextHandler(contexts, "/noservlets");
        TestServletContextListener listener = new TestServletContextListener();
        noServlets.addEventListener(listener);
        server.start();
        assertEquals(1, listener.initialized);
        server.stop();
        assertEquals(1, listener.destroyed);
    }

    @Test
    public void testContextVirtualGetContext() throws Exception
    {
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.setConnectors(new Connector[]{connector});
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        ContextHandler rootA = new ContextHandler(contexts, "/");
        rootA.setVirtualHosts(new String[]{"a.com"});

        ContextHandler rootB = new ContextHandler(contexts, "/");
        rootB.setVirtualHosts(new String[]{"b.com"});

        ContextHandler rootC = new ContextHandler(contexts, "/");
        rootC.setVirtualHosts(new String[]{"c.com"});

        ContextHandler fooA = new ContextHandler(contexts, "/foo");
        fooA.setVirtualHosts(new String[]{"a.com"});

        ContextHandler fooB = new ContextHandler(contexts, "/foo");
        fooB.setVirtualHosts(new String[]{"b.com"});

        ContextHandler foobarA = new ContextHandler(contexts, "/foo/bar");
        foobarA.setVirtualHosts(new String[]{"a.com"});

        server.start();

        // System.err.println(server.dump());

        assertEquals(rootA._scontext, rootA._scontext.getContext("/"));
        assertEquals(fooA._scontext, rootA._scontext.getContext("/foo"));
        assertEquals(foobarA._scontext, rootA._scontext.getContext("/foo/bar"));
        assertEquals(foobarA._scontext, rootA._scontext.getContext("/foo/bar/bob"));

        assertEquals(rootA._scontext, rootA._scontext.getContext("/other"));
        assertEquals(rootB._scontext, rootB._scontext.getContext("/other"));
        assertEquals(rootC._scontext, rootC._scontext.getContext("/other"));

        assertEquals(fooB._scontext, rootB._scontext.getContext("/foo/other"));
        assertEquals(rootC._scontext, rootC._scontext.getContext("/foo/other"));
    }

    @Test
    public void testVirtualHostWildcard() throws Exception
    {
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.setConnectors(new Connector[]{connector});

        ContextHandler context = new ContextHandler("/");

        IsHandledHandler handler = new IsHandledHandler();
        context.setHandler(handler);

        server.setHandler(context);

        try
        {
            server.start();
            checkWildcardHost(true, server, null, new String[]{"example.com", ".example.com", "vhost.example.com"});
            checkWildcardHost(false, server, new String[]{null}, new String[]{
                "example.com", ".example.com", "vhost.example.com"
            });

            checkWildcardHost(true, server, new String[]{"example.com", "*.example.com"}, new String[]{
                "example.com", ".example.com", "vhost.example.com"
            });
            checkWildcardHost(false, server, new String[]{"example.com", "*.example.com"}, new String[]{
                "badexample.com", ".badexample.com", "vhost.badexample.com"
            });

            checkWildcardHost(false, server, new String[]{"*."}, new String[]{"anything.anything"});

            checkWildcardHost(true, server, new String[]{"*.example.com"}, new String[]{"vhost.example.com", ".example.com"});
            checkWildcardHost(false, server, new String[]{"*.example.com"}, new String[]{
                "vhost.www.example.com", "example.com", "www.vhost.example.com"
            });

            checkWildcardHost(true, server, new String[]{"*.sub.example.com"}, new String[]{
                "vhost.sub.example.com", ".sub.example.com"
            });
            checkWildcardHost(false, server, new String[]{"*.sub.example.com"}, new String[]{
                ".example.com", "sub.example.com", "vhost.example.com"
            });

            checkWildcardHost(false, server, new String[]{"example.*.com", "example.com.*"}, new String[]{
                "example.vhost.com", "example.com.vhost", "example.com"
            });
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testVirtualHostManagement()
    {
        ContextHandler context = new ContextHandler("/");

        // test singular
        context.setVirtualHosts(new String[]{"www.example.com"});
        assertEquals(1, context.getVirtualHosts().length);

        // test adding two more
        context.addVirtualHosts(new String[]{"foo.com@connector1", "*.example2.com"});
        assertEquals(3, context.getVirtualHosts().length);

        // test adding existing context
        context.addVirtualHosts(new String[]{"www.example.com"});
        assertEquals(3, context.getVirtualHosts().length);

        // test removing existing
        context.removeVirtualHosts(new String[]{"*.example2.com"});
        assertEquals(2, context.getVirtualHosts().length);

        // test removing non-existent
        context.removeVirtualHosts(new String[]{"www.example3.com"});
        assertEquals(2, context.getVirtualHosts().length);

        // test removing all remaining and resets to null
        context.removeVirtualHosts(new String[]{"www.example.com", "foo.com@connector1"});
        assertArrayEquals(null, context.getVirtualHosts());
    }

    @Test
    public void testAttributes() throws Exception
    {
        ContextHandler handler = new ContextHandler();
        handler.setServer(new Server());
        handler.setAttribute("aaa", "111");
        assertEquals("111", handler.getServletContext().getAttribute("aaa"));
        assertEquals(null, handler.getAttribute("bbb"));

        handler.start();

        handler.getServletContext().setAttribute("aaa", "000");
        handler.setAttribute("ccc", "333");
        handler.getServletContext().setAttribute("ddd", "444");
        assertEquals("111", handler.getServletContext().getAttribute("aaa"));
        assertEquals(null, handler.getServletContext().getAttribute("bbb"));
        handler.getServletContext().setAttribute("bbb", "222");
        assertEquals("333", handler.getServletContext().getAttribute("ccc"));
        assertEquals("444", handler.getServletContext().getAttribute("ddd"));

        assertEquals("111", handler.getAttribute("aaa"));
        assertEquals(null, handler.getAttribute("bbb"));
        assertEquals("333", handler.getAttribute("ccc"));
        assertEquals(null, handler.getAttribute("ddd"));

        handler.stop();

        assertEquals("111", handler.getServletContext().getAttribute("aaa"));
        assertEquals(null, handler.getServletContext().getAttribute("bbb"));
        assertEquals("333", handler.getServletContext().getAttribute("ccc"));
        assertEquals(null, handler.getServletContext().getAttribute("ddd"));
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

    @Test
    public void testIsShutdown()
    {
        ContextHandler handler = new ContextHandler();
        assertEquals(false, handler.isShutdown());
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
    public void testLogNameFromContextPathDeep() throws Exception
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
    public void testLogNameFromContextPathRoot() throws Exception
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
    public void testLogNameFromContextPathUndefined() throws Exception
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
    public void testLogNameFromContextPathEmpty() throws Exception
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
    public void testClassPathWithSpaces() throws IOException
    {
        ContextHandler handler = new ContextHandler();
        handler.setServer(new Server());
        handler.setContextPath("/");

        Path baseDir = MavenTestingUtils.getTargetTestingPath("testClassPath_WithSpaces");
        FS.ensureEmpty(baseDir);

        Path spacey = baseDir.resolve("and extra directory");
        FS.ensureEmpty(spacey);

        Path jar = spacey.resolve("empty.jar");
        FS.touch(jar);

        URLClassLoader cl = new URLClassLoader(new URL[]{jar.toUri().toURL()});
        handler.setClassLoader(cl);

        String classpath = handler.getClassPath();
        assertThat("classpath", classpath, containsString(jar.toString()));
    }

    private void checkResourcePathsForExampleWebApp(String root) throws IOException
    {
        File testDirectory = setupTestDirectory();

        ContextHandler handler = new ContextHandler();

        assertTrue(testDirectory.isDirectory(), "Not a directory " + testDirectory);
        handler.setBaseResource(Resource.newResource(Resource.toURL(testDirectory)));

        List<String> paths = new ArrayList<>(handler.getResourcePaths(root));
        assertEquals(2, paths.size());

        Collections.sort(paths);
        assertEquals("/WEB-INF/jsp/", paths.get(0));
        assertEquals("/WEB-INF/web.xml", paths.get(1));
    }

    private File setupTestDirectory() throws IOException
    {
        File tmpDir = new File(System.getProperty("basedir", ".") + "/target/tmp/ContextHandlerTest");
        tmpDir = tmpDir.getCanonicalFile();
        if (!tmpDir.exists())
            assertTrue(tmpDir.mkdirs());
        File tmp = File.createTempFile("cht", null, tmpDir);
        assertTrue(tmp.delete());
        assertTrue(tmp.mkdir());
        tmp.deleteOnExit();
        File root = new File(tmp, getClass().getName());
        assertTrue(root.mkdir());

        File webInf = new File(root, "WEB-INF");
        assertTrue(webInf.mkdir());

        assertTrue(new File(webInf, "jsp").mkdir());
        assertTrue(new File(webInf, "web.xml").createNewFile());

        return root;
    }

    private void checkWildcardHost(boolean succeed, Server server, String[] contextHosts, String[] requestHosts) throws Exception
    {
        LocalConnector connector = (LocalConnector)server.getConnectors()[0];
        ContextHandler context = (ContextHandler)server.getHandler();
        context.setVirtualHosts(contextHosts);

        IsHandledHandler handler = (IsHandledHandler)context.getHandler();
        for (String host : requestHosts)
        {
            connector.getResponse("GET / HTTP/1.1\n" + "Host: " + host + "\nConnection:close\n\n");
            if (succeed)
                assertTrue(handler.isHandled(), "'" + host + "' should have been handled.");
            else
                assertFalse(handler.isHandled(), "'" + host + "' should not have been handled.");
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
        public void handle(String s, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
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
        public void handle(String s, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            baseRequest.setHandled(true);

            response.setStatus(200);
            response.setContentType("text/plain; charset=utf-8");
            response.setHeader("Connection", "close");
            PrintWriter writer = response.getWriter();
            writer.println("ctx='" + request.getContextPath() + "'");
        }
    }

    private static class TestServletContextListener implements ServletContextListener
    {
        public int initialized = 0;
        public int destroyed = 0;

        @Override
        public void contextInitialized(ServletContextEvent sce)
        {
            initialized++;
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce)
        {
            destroyed++;
        }
    }
}
