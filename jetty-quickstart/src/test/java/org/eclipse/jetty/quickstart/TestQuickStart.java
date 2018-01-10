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


package org.eclipse.jetty.quickstart;

import static org.junit.Assert.*;

import java.io.File;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.Before;
import org.junit.Test;

/**
 * TestQuickStart
 *
 *
 */
public class TestQuickStart
{
    File testDir;
    File webInf;
    
    
    @Before
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
        quickstart.addEventListener(new FooContextListener());      
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

}
