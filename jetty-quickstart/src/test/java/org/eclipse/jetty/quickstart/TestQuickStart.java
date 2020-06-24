//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quickstart;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ListenerHolder;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TestQuickStart
 */
public class TestQuickStart
{
    File testDir;
    File webInf;
    Server server;

    @BeforeEach
    public void setUp()
    {
        testDir = MavenTestingUtils.getTargetTestingDir("foo");
        FS.ensureEmpty(testDir);
        webInf = new File(testDir, "WEB-INF");
        FS.ensureDirExists(webInf);
        server = new Server();
    }
    
    @AfterEach
    public void tearDown() throws Exception
    {
        server.stop();
    }

    @Test
    public void testProgrammaticOverrideOfDefaultServletMapping() throws Exception
    {
        File quickstartXml = new File(webInf, "quickstart-web.xml");
        assertFalse(quickstartXml.exists());

        //generate a quickstart-web.xml
        WebAppContext quickstart = new WebAppContext();
        quickstart.addConfiguration(new QuickStartConfiguration());
        quickstart.setAttribute(QuickStartConfiguration.MODE, QuickStartConfiguration.Mode.GENERATE);
        quickstart.setAttribute(QuickStartConfiguration.ORIGIN_ATTRIBUTE, "origin");
        quickstart.setResourceBase(testDir.getAbsolutePath());
        ServletHolder fooHolder = new ServletHolder();
        fooHolder.setServlet(new FooServlet());
        fooHolder.setName("foo");
        quickstart.getServletHandler().addServlet(fooHolder);
        ListenerHolder lholder = new ListenerHolder();
        lholder.setClassName("org.eclipse.jetty.quickstart.FooContextListener");
        quickstart.getServletHandler().addListener(lholder);
        server.setHandler(quickstart);
        server.setDryRun(true);
        server.start();

        assertTrue(quickstartXml.exists());

        //now run the webapp again purely from the generated quickstart
        WebAppContext webapp = new WebAppContext();
        webapp.setResourceBase(testDir.getAbsolutePath());
        webapp.addConfiguration(new QuickStartConfiguration());
        webapp.setAttribute(QuickStartConfiguration.MODE, QuickStartConfiguration.Mode.QUICKSTART);
        webapp.setClassLoader(new URLClassLoader(new URL[0], Thread.currentThread().getContextClassLoader()));
        server.setHandler(webapp);

        server.setDryRun(false);
        server.start();
        server.dumpStdErr();

        //verify that FooServlet is now mapped to / and not the DefaultServlet
        ServletHolder sh = webapp.getServletHandler().getMappedServlet("/").getServletHolder();
        assertNotNull(sh);
        assertEquals("foo", sh.getName());
    }

    @Test
    public void testDefaultContextPath() throws Exception
    {
        File quickstartXml = new File(webInf, "quickstart-web.xml");
        assertFalse(quickstartXml.exists());

        // generate a quickstart-web.xml
        WebAppContext quickstart = new WebAppContext();
        quickstart.setResourceBase(testDir.getAbsolutePath());
        quickstart.addConfiguration(new QuickStartConfiguration());
        quickstart.setAttribute(QuickStartConfiguration.MODE, QuickStartConfiguration.Mode.GENERATE);
        quickstart.setAttribute(QuickStartConfiguration.ORIGIN_ATTRIBUTE, "origin");
        quickstart.setDescriptor(MavenTestingUtils.getTestResourceFile("web.xml").getAbsolutePath());
        quickstart.setContextPath("/foo");
        server.setHandler(quickstart);
        server.setDryRun(true);
        server.start();
        assertEquals("/foo", quickstart.getContextPath());
        assertFalse(quickstart.isContextPathDefault());

        assertTrue(quickstartXml.exists());

        // quick start
        WebAppContext webapp = new WebAppContext();
        webapp.addConfiguration(new QuickStartConfiguration());
        quickstart.setAttribute(QuickStartConfiguration.MODE, QuickStartConfiguration.Mode.QUICKSTART);
        webapp.setResourceBase(testDir.getAbsolutePath());
        webapp.setClassLoader(new URLClassLoader(new URL[0], Thread.currentThread().getContextClassLoader()));
        server.setHandler(webapp);

        server.setDryRun(false);
        server.start();

        // verify the context path is the default-context-path
        assertEquals("/thisIsTheDefault", webapp.getContextPath());
        assertTrue(webapp.isContextPathDefault());
    }
    
    @Test
    public void testDefaultRequestAndResponseEncodings() throws Exception
    {
        File quickstartXml = new File(webInf, "quickstart-web.xml");
        assertFalse(quickstartXml.exists());

        // generate a quickstart-web.xml
        WebAppContext quickstart = new WebAppContext();
        quickstart.setResourceBase(testDir.getAbsolutePath());
        quickstart.addConfiguration(new QuickStartConfiguration());
        quickstart.setAttribute(QuickStartConfiguration.MODE, QuickStartConfiguration.Mode.GENERATE);
        quickstart.setAttribute(QuickStartConfiguration.ORIGIN_ATTRIBUTE, "origin");
        quickstart.setDescriptor(MavenTestingUtils.getTestResourceFile("web.xml").getAbsolutePath());
        quickstart.setContextPath("/foo");
        server.setHandler(quickstart);
        server.setDryRun(true);
        server.start();
        
        assertTrue(quickstartXml.exists());
        
        // quick start
        WebAppContext webapp = new WebAppContext();
        webapp.addConfiguration(new QuickStartConfiguration());
        quickstart.setAttribute(QuickStartConfiguration.MODE, QuickStartConfiguration.Mode.QUICKSTART);
        webapp.setResourceBase(testDir.getAbsolutePath());
        webapp.setClassLoader(new URLClassLoader(new URL[0], Thread.currentThread().getContextClassLoader()));
        server.setHandler(webapp);

        server.setDryRun(false);
        server.start();
        
        assertEquals("ascii", webapp.getDefaultRequestCharacterEncoding());
        assertEquals("utf-16", webapp.getDefaultResponseCharacterEncoding());
    }
    
    @Test
    public void testListenersNotCalledInPreConfigure() throws Exception
    {
        File quickstartXml = new File(webInf, "quickstart-web.xml");
        assertFalse(quickstartXml.exists());
        
        Server server = new Server();
        
        WebAppContext quickstart = new WebAppContext();
        quickstart.addConfiguration(new QuickStartConfiguration());
        quickstart.setAttribute(QuickStartConfiguration.MODE, QuickStartConfiguration.Mode.GENERATE);
        quickstart.setAttribute(QuickStartConfiguration.ORIGIN_ATTRIBUTE, "origin");

        //add a listener directly to the ContextHandler so it is there when we start -
        //if you add them to the ServletHandler (like StandardDescriptorProcessor does)
        //then they are not added to the ContextHandler in a pre-generate.
        quickstart.addEventListener(new FooContextListener());
        quickstart.setResourceBase(testDir.getAbsolutePath());
        server.setHandler(quickstart);
        server.setDryRun(true);

        server.start();
        assertTrue(quickstartXml.exists());
        assertEquals(0, FooContextListener.___initialized);
    }
}
