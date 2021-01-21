//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ListenerHolder;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
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
        quickstart.setPreconfigure(true);
        quickstart.setGenerateOrigin(true);
        ServletHolder fooHolder = new ServletHolder();
        fooHolder.setServlet(new FooServlet());
        fooHolder.setName("foo");
        quickstart.getServletHandler().addServlet(fooHolder);
        ListenerHolder lholder = new ListenerHolder();
        lholder.setClassName("org.eclipse.jetty.quickstart.FooContextListener");
        quickstart.getServletHandler().addListener(lholder);
        server.setHandler(quickstart);
        server.start();
        server.stop();

        assertTrue(quickstartXml.exists());

        //now run the webapp again purely from the generated quickstart
        QuickStartWebApp webapp = new QuickStartWebApp();
        webapp.setResourceBase(testDir.getAbsolutePath());
        webapp.setPreconfigure(false);
        webapp.setClassLoader(Thread.currentThread().getContextClassLoader()); //only necessary for junit testing
        server.setHandler(webapp);

        server.start();

        //verify that FooServlet is now mapped to / and not the DefaultServlet
        ServletHolder sh = webapp.getServletHandler().getMappedServlet("/").getResource();
        assertNotNull(sh);
        assertEquals("foo", sh.getName());
        server.stop();
    }
    
    @Test
    public void testListenersNotCalledInPreConfigure() throws Exception
    {
        File quickstartXml = new File(webInf, "quickstart-web.xml");
        assertFalse(quickstartXml.exists());
        
        Server server = new Server();
        
        QuickStartWebApp quickstart = new QuickStartWebApp();

        //add a listener directly to the ContextHandler so it is there when we start -
        //if you add them to the ServletHandler (like StandardDescriptorProcessor does)
        //then they are not added to the ContextHandler in a pre-generate.
        quickstart.addEventListener(new FooContextListener());
        quickstart.setResourceBase(testDir.getAbsolutePath());
        quickstart.setPreconfigure(true);
        quickstart.setGenerateOrigin(true);
        server.setHandler(quickstart);
        server.start();
        server.stop();
        assertTrue(quickstartXml.exists());
        assertEquals(0, FooContextListener.___initialized);
    }
    
    @Test
    public void testListenersCalledIfGenerateWithStart() throws Exception
    {
        File quickstartXml = new File(webInf, "quickstart-web.xml");
        assertFalse(quickstartXml.exists());
        
        Server server = new Server();
        
        QuickStartWebApp quickstart = new QuickStartWebApp();
        quickstart.setAutoPreconfigure(true); //generate AND start the webapp

        //add a listener directly to the ContextHandler so it is there when we start
        quickstart.addEventListener(new FooContextListener());
        quickstart.setResourceBase(testDir.getAbsolutePath());
        quickstart.setGenerateOrigin(true);
        server.setHandler(quickstart);
        server.start();
        server.stop();
        assertTrue(quickstartXml.exists());
        assertEquals(1, FooContextListener.___initialized);
    }
}
