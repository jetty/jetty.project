//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.webapp;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.ee9.servlet.DefaultServlet;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(WorkDirExtension.class)
public class StandardDescriptorProcessorTest
{
    //TODO add tests for other methods
    Server _server;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        _server = new Server();
        _server.start();
    }

    @AfterEach
    public void afterEach() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testJettyApiDefaults(WorkDir workDir) throws Exception
    {
        //Test that the DefaultServlet named "default" defined by jetty api is not redefined by webdefault-ee10.xml
        Path docroot = workDir.getEmptyPathDir();
        WebAppContext wac = new WebAppContext();
        wac.setServer(_server);
        wac.setBaseResourceAsPath(docroot);
        ServletHolder defaultServlet = new ServletHolder(DefaultServlet.class);
        defaultServlet.setName("default");
        defaultServlet.setInitParameter("acceptRanges", "false");
        defaultServlet.setInitParameter("dirAllowed", "false");
        defaultServlet.setInitParameter("welcomeServlets", "true");
        defaultServlet.setInitParameter("redirectWelcome", "true");
        defaultServlet.setInitParameter("maxCacheSize", "10");
        defaultServlet.setInitParameter("maxCachedFileSize", "1");
        defaultServlet.setInitParameter("maxCacheFiles", "10");
        defaultServlet.setInitParameter("etags", "true");
        defaultServlet.setInitParameter("useFileMappedBuffer", "false");
        defaultServlet.setInitOrder(2);
        defaultServlet.setRunAsRole("foo");
        wac.getServletHandler().addServletWithMapping(defaultServlet, "/");
        wac.start();

        ServletHolder[] holders = wac.getServletHandler().getServlets();
        ServletHolder holder = null;
        for (ServletHolder h:holders)
        {
            if ("default".equals(h.getName()))
            {
                assertEquals(null, holder);
                holder = h;
            }
        }
        assertNotNull(holder);
        assertEquals("false", holder.getInitParameter("acceptRanges"));
        assertEquals("false", holder.getInitParameter("dirAllowed"));
        assertEquals("true", holder.getInitParameter("welcomeServlets"));
        assertEquals("true", holder.getInitParameter("redirectWelcome"));
        assertEquals("10", holder.getInitParameter("maxCacheSize"));
        assertEquals("1", holder.getInitParameter("maxCachedFileSize"));
        assertEquals("10", holder.getInitParameter("maxCacheFiles"));
        assertEquals("true", holder.getInitParameter("etags"));
        assertEquals("false", holder.getInitParameter("useFileMappedBuffer"));
        assertEquals(2, holder.getInitOrder());
        assertEquals("foo", holder.getRunAsRole());
    }

    @Test
    public void testDuplicateServletMappingsFromJettyApi(WorkDir workDir) throws Exception
    {
        Path docroot = workDir.getEmptyPathDir();
        WebAppContext wac = new WebAppContext();
        wac.setServer(_server);
        wac.setBaseResourceAsPath(docroot);
        wac.setThrowUnavailableOnStartupException(true);
        //add a mapping that will conflict with the webdefault-ee10.xml mapping
        //for the default servlet
        ServletHolder defaultServlet = new ServletHolder(DefaultServlet.class);
        defaultServlet.setName("noname");
        wac.getServletHandler().addServletWithMapping(defaultServlet, "/");
        try (StacklessLogging ignored = new StacklessLogging(WebAppContext.class))
        {
            assertThrows(RuntimeException.class, () -> wac.start());
        }
    }

    @Test
    public void testDuplicateServletMappingsFromDescriptors(WorkDir workDir) throws Exception
    {
        //Test that the DefaultServlet mapping from webdefault-ee10.xml can be overridden in web.xml
        Path docroot = workDir.getEmptyPathDir();
        Path webXml = MavenPaths.findTestResourceFile("web-redefine-mapping.xml");
        WebAppContext wac = new WebAppContext();
        wac.setServer(_server);
        wac.setBaseResourceAsPath(docroot);
        wac.setDescriptor(webXml.toUri().toURL().toString());
        wac.start();
        assertEquals("other", wac.getServletHandler().getServletMapping("/").getServletName());
    }

    @Test
    public void testBadDuplicateServletMappingsFromDescriptors(WorkDir workDir) throws Exception
    {
        //Test that the same mapping cannot be redefined to a different servlet in the same (non-default) descriptor
        Path docroot = workDir.getEmptyPathDir();
        Path webXml = MavenPaths.findTestResourceFile("web-redefine-mapping-fail.xml");
        WebAppContext wac = new WebAppContext();
        wac.setServer(_server);
        wac.setBaseResourceAsPath(docroot);
        wac.setDescriptor(webXml.toUri().toURL().toString());
        wac.setThrowUnavailableOnStartupException(true);
        try (StacklessLogging ignored = new StacklessLogging(WebAppContext.class))
        {
            assertThrows(RuntimeException.class, () -> wac.start());
        }
    }

    @Test
    public void testVisitSessionConfig(WorkDir workDir) throws Exception
    {
        Path docroot = workDir.getEmptyPathDir();
        Path webXml = MavenPaths.findTestResourceFile("web-session-config.xml");
        WebAppContext wac = new WebAppContext();
        wac.setServer(_server);
        wac.setBaseResourceAsPath(docroot);
        wac.setDescriptor(webXml.toUri().toURL().toString());
        wac.start();
        assertEquals(54, TimeUnit.SECONDS.toMinutes(wac.getSessionHandler().getMaxInactiveInterval()));
        
        //test the CookieConfig attributes and getters, and the getters on SessionHandler
        //name
        assertEquals("SPECIALSESSIONID", wac.getSessionHandler().getSessionCookieConfig().getName());
        assertEquals("SPECIALSESSIONID", wac.getSessionHandler().getSessionCookie());
        
        //comment
        assertEquals("nocomment", wac.getSessionHandler().getSessionCookieConfig().getComment());
        assertEquals("nocomment", wac.getSessionHandler().getSessionComment());
        
        //domain
        assertEquals("universe", wac.getSessionHandler().getSessionCookieConfig().getDomain());
        assertEquals("universe", wac.getSessionHandler().getSessionDomain());
        
        //path
        assertEquals("foo", wac.getSessionHandler().getSessionCookieConfig().getPath());
        assertEquals("foo", wac.getSessionHandler().getSessionPath());
        
        //max-age
        assertEquals(10, wac.getSessionHandler().getSessionCookieConfig().getMaxAge());
        assertEquals(10, wac.getSessionHandler().getMaxCookieAge());
        
        //secure
        assertEquals(false, wac.getSessionHandler().getSessionCookieConfig().isSecure());
        assertEquals(false, wac.getSessionHandler().isSecureCookies());
        
        //httponly
        assertEquals(false, wac.getSessionHandler().getSessionCookieConfig().isHttpOnly());
        assertEquals(false, wac.getSessionHandler().isHttpOnly());
    }
}
