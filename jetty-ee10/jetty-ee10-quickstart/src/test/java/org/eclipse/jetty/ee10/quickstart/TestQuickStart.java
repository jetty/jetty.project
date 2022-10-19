//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.quickstart;

import java.io.File;
import java.util.Arrays;

import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ListenerHolder;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TestQuickStart
 */
public class TestQuickStart
{
    Server server;

    @BeforeEach
    public void setUp()
    {
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
        File testDir = MavenTestingUtils.getTargetTestingDir("pgoverride");
        FS.ensureEmpty(testDir);
        File webInf = new File(testDir, "WEB-INF");
        FS.ensureDirExists(webInf);
        
        File quickstartXml = new File(webInf, "quickstart-web.xml");
        assertFalse(quickstartXml.exists());

        //generate a quickstart-web.xml
        WebAppContext quickstart = new WebAppContext();
        quickstart.addConfiguration(new QuickStartConfiguration());
        quickstart.setAttribute(QuickStartConfiguration.MODE, QuickStartConfiguration.Mode.GENERATE);
        quickstart.setAttribute(QuickStartConfiguration.ORIGIN_ATTRIBUTE, "origin");
        quickstart.setBaseResourceAsPath(testDir.toPath());
        ServletHolder fooHolder = new ServletHolder();
        fooHolder.setServlet(new FooServlet());
        fooHolder.setName("foo");
        quickstart.getServletHandler().addServlet(fooHolder);
        ListenerHolder lholder = new ListenerHolder();
        lholder.setClassName("org.eclipse.jetty.ee10.quickstart.FooContextListener");
        quickstart.getServletHandler().addListener(lholder);
        server.setHandler(quickstart);
        server.setDryRun(true);
        server.start();

        assertTrue(quickstartXml.exists());

        //now run the webapp again
        WebAppContext webapp = new WebAppContext();
        webapp.setBaseResourceAsPath(testDir.toPath());
        webapp.addConfiguration(new QuickStartConfiguration());
        webapp.getServerClassMatcher().exclude("org.eclipse.jetty.ee10.quickstart.");
        webapp.setAttribute(QuickStartConfiguration.MODE, QuickStartConfiguration.Mode.QUICKSTART);
        //add in the servlet
        webapp.getServletHandler().addServlet(fooHolder);
        //add in the listener
        webapp.getServletHandler().addListener(lholder);
        
        server.setHandler(webapp);

        server.setDryRun(false);
        server.start();

        //verify that FooServlet is now mapped to / and not the DefaultServlet
        ServletHolder sh = webapp.getServletHandler().getMappedServlet("/").getServletHolder();
        assertNotNull(sh);
        assertThat(sh.getClassName(), Matchers.equalTo("org.eclipse.jetty.ee10.quickstart.FooServlet"));
    }

    @Test
    public void testDefaultContextPath() throws Exception
    {
        File testDir = MavenTestingUtils.getTargetTestingDir("dfltcp");
        FS.ensureEmpty(testDir);
        File webInf = new File(testDir, "WEB-INF");
        FS.ensureDirExists(webInf);
        
        File quickstartXml = new File(webInf, "quickstart-web.xml");
        assertFalse(quickstartXml.exists());

        // generate a quickstart-web.xml
        WebAppContext quickstart = new WebAppContext();
        quickstart.setBaseResourceAsPath(testDir.toPath());
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
        webapp.setAttribute(QuickStartConfiguration.MODE, QuickStartConfiguration.Mode.QUICKSTART);
        webapp.setBaseResourceAsPath(testDir.toPath());
        webapp.getServerClassMatcher().exclude("org.eclipse.jetty.ee10.quickstart.");
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
        File testDir = MavenTestingUtils.getTargetTestingDir("dfltenc");
        FS.ensureEmpty(testDir);
        File webInf = new File(testDir, "WEB-INF");
        FS.ensureDirExists(webInf);
        
        File quickstartXml = new File(webInf, "quickstart-web.xml");
        assertFalse(quickstartXml.exists());

        // generate a quickstart-web.xml
        WebAppContext quickstart = new WebAppContext();
        quickstart.setBaseResourceAsPath(testDir.toPath());
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
        webapp.setAttribute(QuickStartConfiguration.MODE, QuickStartConfiguration.Mode.QUICKSTART);
        webapp.setBaseResourceAsPath(testDir.toPath());
        webapp.getServerClassMatcher().exclude("org.eclipse.jetty.ee10.quickstart.");
        server.setHandler(webapp);

        server.setDryRun(false);
        server.start();
        
        assertEquals("ascii", webapp.getDefaultRequestCharacterEncoding());
        assertEquals("utf-16", webapp.getDefaultResponseCharacterEncoding());
    }
    
    @Test
    public void testListenersNotCalledInPreConfigure() throws Exception
    {
        File testDir = MavenTestingUtils.getTargetTestingDir("listeners");
        FS.ensureEmpty(testDir);
        File webInf = new File(testDir, "WEB-INF");
        FS.ensureDirExists(webInf);
        
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
        quickstart.setBaseResourceAsPath(testDir.toPath());
        server.setHandler(quickstart);
        server.setDryRun(true);

        server.start();
        assertTrue(quickstartXml.exists());
        assertEquals(0, FooContextListener.___initialized);
    }
    
    @Test
    public void testDuplicateGenerationFromContextXml() throws Exception
    {
        File testDir = MavenTestingUtils.getTargetTestingDir("dups");
        FS.ensureEmpty(testDir);
        File webInf = new File(testDir, "WEB-INF");
        FS.ensureDirExists(webInf);
        
        File quickstartXml = new File(webInf, "quickstart-web.xml");
        assertFalse(quickstartXml.exists());

        //no servlets, filters or listeners defined in web.xml
        WebAppContext quickstart = new WebAppContext();
        quickstart.addConfiguration(new QuickStartConfiguration());
        quickstart.setWar(testDir.toURI().toURL().toExternalForm());
        quickstart.setAttribute(QuickStartConfiguration.MODE, QuickStartConfiguration.Mode.GENERATE);
        quickstart.setDescriptor(MavenTestingUtils.getTestResourceFile("web.xml").getAbsolutePath());

        //apply the context xml file
        XmlConfiguration xmlConfig = new XmlConfiguration(ResourceFactory.root().newResource(MavenTestingUtils.getTestResourceFile("context.xml").toPath()));
        xmlConfig.configure(quickstart);

        //generate the quickstart
        server.setHandler(quickstart);
        server.setDryRun(true);
        server.start();
        
        assertTrue(quickstartXml.exists());
        assertTrue(server.isStopped());
        
        //Make a new webappcontext to mimic starting the server over again with
        //a freshly applied context xml
        quickstart = new WebAppContext();
        //need visibility of FooServlet, FooFilter, FooContextListener when we quickstart
        quickstart.getServerClassMatcher().exclude("org.eclipse.jetty.ee10.quickstart.");
        quickstart.addConfiguration(new QuickStartConfiguration());
        quickstart.setWar(testDir.toURI().toURL().toExternalForm());
        quickstart.setDescriptor(MavenTestingUtils.getTestResourceFile("web.xml").getAbsolutePath());
        quickstart.setAttribute(QuickStartConfiguration.MODE, QuickStartConfiguration.Mode.AUTO);
        server.setHandler(quickstart);
        
        //apply the context xml file like a restart would
        xmlConfig.configure(quickstart);
        server.setDryRun(false);
        
        //restart the server
        server.start();
               
        //test that we only get 1 FoOServlet, FooFilter and FooContextListener
        ServletHolder[] servlets = quickstart.getServletHandler().getServlets();
        assertNotNull(servlets);
        assertEquals(1,
            Arrays.stream(servlets).filter(s -> "org.eclipse.jetty.ee10.quickstart.FooServlet".equals(s.getClassName())).count());
        
        FilterHolder[] filters = quickstart.getServletHandler().getFilters();
        assertNotNull(filters);
        assertEquals(1,
            Arrays.stream(filters).filter(f -> "org.eclipse.jetty.ee10.quickstart.FooFilter".equals(f.getClassName())).count());
        
        ListenerHolder[] listeners = quickstart.getServletHandler().getListeners();
        assertNotNull(listeners);
        assertEquals(1,
            Arrays.stream(listeners).filter(l -> "org.eclipse.jetty.ee10.quickstart.FooContextListener".equals(l.getClassName())).count());
    }
}
