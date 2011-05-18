// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.deploy.bindings;

import java.io.File;

import junit.framework.Assert;

import org.eclipse.jetty.deploy.providers.ScanningAppProvider;
import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests {@link ScanningAppProvider} as it starts up for the first time.
 */
public class GlobalJettyXmlBindingTest
{
	@Rule
	public TestingDir testdir = new TestingDir();
    private static XmlConfiguredJetty jetty;

    @Before
    public void setupEnvironment() throws Exception
    {
        jetty = new XmlConfiguredJetty(testdir);
        jetty.addConfiguration("jetty.xml");

        // Setup initial context
        jetty.copyContext("foo.xml","foo.xml");
        jetty.copyWebapp("foo-webapp-1.war","foo.war");

    }

    @After
    public void teardownEnvironment() throws Exception
    {
        // Stop jetty.
        jetty.stop();
    }
    
    @Test
    public void testServerAndSystemClassesOverride() throws Exception
    {
        IO.copy(MavenTestingUtils.getTestResourceFile("context-binding-test-1.xml"), new File(jetty.getJettyHome(), "context-binding-test-1.xml"));
        
        jetty.addConfiguration("binding-test-contexts-1.xml");
        jetty.load();
        jetty.start();
        
        WebAppContext context = jetty.getWebAppContexts().get(0);
        
        Assert.assertNotNull(context);
        Assert.assertEquals(context.getDefaultServerClasses().length, context.getServerClasses().length - 1); // added a pattern
        //Assert.assertEquals(context.getDefaultSystemClasses().length,context.getSystemClasses().length + 1); // removed a patter
        
        boolean fooPackage = false;
        
        // we are inserting the foo package into the server classes
        for (String entry : context.getServerClasses())
        {
            if ("org.eclipse.foo.".equals(entry))
            {
                fooPackage = true;
            }
        }
        
        Assert.assertTrue(fooPackage);
        
      //  boolean jndiPackage = false;
        
        // this test overrides and we removed the jndi from the list so it
        // should test false
//        for (String entry : context.getSystemClasses())
//        {
//            if ("org.eclipse.jetty.jndi.".equals(entry))
//            {
//                jndiPackage = true;
//            }
//        }
//        
//        Assert.assertFalse(jndiPackage);
    }
}

