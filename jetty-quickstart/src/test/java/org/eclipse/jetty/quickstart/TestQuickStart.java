//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

    @BeforeEach
    public void setUp()
    {
        testDir = MavenTestingUtils.getTargetTestingDir("foo");
        FS.ensureEmpty(testDir);
        webInf = new File(testDir, "WEB-INF");
        FS.ensureDirExists(webInf);
    }

    @Test
    public void testProgrammaticOverrideOfDefaultServletMapping() throws Exception
    {
        File quickstartXml = new File(webInf, "quickstart-web.xml");
        assertFalse(quickstartXml.exists());

        Server server = new Server();

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
        lholder.setListener(new FooContextListener());
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
        ServletHolder sh = webapp.getServletHandler().getMappedServlet("/").getResource();
        assertNotNull(sh);
        assertEquals("foo", sh.getName());

        server.stop();
    }

    @Test
    public void testDefaultContextPath() throws Exception
    {
        File quickstartXml = new File(webInf, "quickstart-web.xml");
        assertFalse(quickstartXml.exists());

        Server server = new Server();

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

        server.stop();
    }
}
