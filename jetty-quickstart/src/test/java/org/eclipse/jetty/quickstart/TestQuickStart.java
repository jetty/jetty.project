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
        QuickStartWebApp quickstart = new QuickStartWebApp();
        quickstart.setResourceBase(testDir.getAbsolutePath());
        quickstart.setMode(QuickStartConfiguration.Mode.GENERATE);
        quickstart.setGenerateOrigin(true);
        ServletHolder fooHolder = new ServletHolder();
        fooHolder.setServlet(new FooServlet());
        fooHolder.setName("foo");
        quickstart.getServletHandler().addServlet(fooHolder);
        ListenerHolder lholder = new ListenerHolder();
        lholder.setListener(new FooContextListener());
        quickstart.getServletHandler().addListener(lholder);
        server.setHandler(quickstart);
        server.start();
        server.stop();

        assertTrue(quickstartXml.exists());

        //now run the webapp again purely from the generated quickstart
        QuickStartWebApp webapp = new QuickStartWebApp();
        webapp.setResourceBase(testDir.getAbsolutePath());
        webapp.setMode(QuickStartConfiguration.Mode.QUICKSTART);
        webapp.setClassLoader(new URLClassLoader(new URL[0], Thread.currentThread().getContextClassLoader()));
        server.setHandler(webapp);

        server.start();

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
        QuickStartWebApp quickstart = new QuickStartWebApp();
        quickstart.setResourceBase(testDir.getAbsolutePath());
        quickstart.setMode(QuickStartConfiguration.Mode.GENERATE);
        quickstart.setGenerateOrigin(true);
        quickstart.setDescriptor(MavenTestingUtils.getTestResourceFile("web.xml").getAbsolutePath());
        quickstart.setContextPath("/foo");
        server.setHandler(quickstart);
        server.start();

        assertEquals("/foo", quickstart.getContextPath());
        assertFalse(quickstart.isContextPathDefault());
        server.stop();

        assertTrue(quickstartXml.exists());

        // quick start
        QuickStartWebApp webapp = new QuickStartWebApp();
        webapp.setResourceBase(testDir.getAbsolutePath());
        webapp.setMode(QuickStartConfiguration.Mode.QUICKSTART);
        webapp.setClassLoader(new URLClassLoader(new URL[0], Thread.currentThread().getContextClassLoader()));
        server.setHandler(webapp);

        server.start();

        // verify the context path is the default-context-path
        assertEquals("/thisIsTheDefault", webapp.getContextPath());
        assertTrue(webapp.isContextPathDefault());

        server.stop();
    }
}
