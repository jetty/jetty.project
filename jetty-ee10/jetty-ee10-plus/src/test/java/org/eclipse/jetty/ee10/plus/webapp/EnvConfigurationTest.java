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

package org.eclipse.jetty.ee10.plus.webapp;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.eclipse.jetty.ee.WebAppClassLoading;
import org.eclipse.jetty.ee10.webapp.Configuration;
import org.eclipse.jetty.ee10.webapp.WebAppClassLoader;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.plus.jndi.NamingEntryUtil;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated("jndi entries")
public class EnvConfigurationTest
{
    Server _server;

    @BeforeEach
    public void setUp()
    {
        _server = new Server();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        if (_server != null)
            _server.stop();
    }

    @Test
    public void testWithOnlyJettyWebXml() throws Exception
    {
        Path testWebappDir = MavenTestingUtils.getTargetPath("test-classes/webapp-with-jetty-env-xml");
        assertTrue(Files.exists(testWebappDir));

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        _server.setHandler(context);
        context.setWar(testWebappDir.toFile().getAbsolutePath());
        _server.start();
        assertNotNull(NamingEntryUtil.lookupNamingEntry(context, "apricot"));
    }

    @Test
    public void testWithJettyEEWebXml() throws Exception
    {
        Path testWebappDir = MavenTestingUtils.getTargetPath("test-classes/webapp-with-jetty-ee10-env-xml");
        assertTrue(Files.exists(testWebappDir));

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        _server.setHandler(context);
        context.setWar(testWebappDir.toFile().getAbsolutePath());
        _server.start();
        assertNotNull(NamingEntryUtil.lookupNamingEntry(context, "peach"));
        assertNull(NamingEntryUtil.lookupNamingEntry(context, "cabbage"));
    }

    @Test
    public void testCompEnvCreation() throws Exception
    {
        EnvConfiguration envConfigurationA = new EnvConfiguration();
        EnvConfiguration envConfigurationB = new EnvConfiguration();
        WebAppContext webappA = null;
        WebAppContext webappB = null;
        try
        {
            webappA = new WebAppContext();
            webappA.setConfigurations(new Configuration[]{new PlusConfiguration(), new EnvConfiguration()});
            webappA.setClassLoader(new WebAppClassLoader(Thread.currentThread().getContextClassLoader(), webappA));

            //ensure that a java:comp/env Context was created for webappA
            envConfigurationA.preConfigure(webappA);
            Context namingContextA = getCompEnvFor(webappA);

            webappB = new WebAppContext();
            webappB.setConfigurations(new Configuration[]{new PlusConfiguration(), new EnvConfiguration()});
            webappB.setClassLoader(new WebAppClassLoader(Thread.currentThread().getContextClassLoader(), webappB));

            //ensure that a different java:comp/env Context was created for webappB
            envConfigurationB.preConfigure(webappB);
            Context namingContextB = getCompEnvFor(webappB);

            assertThat(namingContextA, is(not(namingContextB)));
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
        finally
        {
            envConfigurationA.deconfigure(webappA);
            envConfigurationB.deconfigure(webappB);
        }
    }

    @Test
    public void testPriorCompCreation() throws Exception
    {
        //pre-create java:comp on the app classloader
        new InitialContext().lookup("java:comp");
        //test that each webapp still gets its own naming Context
        testCompEnvCreation();
    }

    /**
     * Find the java:comp/env naming Context for the given webapp
     * @param webapp the WebAppContext whose naming comp/env Context to find
     * @return the comp/env naming Context specific to the given WebAppContext
     * @throws NamingException
     */
    private Context getCompEnvFor(WebAppContext webapp)
        throws NamingException
    {
        if (webapp == null)
            return null;

        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Context namingContext = null;
        try
        {
            Thread.currentThread().setContextClassLoader(webapp.getClassLoader());
            InitialContext ic = new InitialContext();
            namingContext = (Context)ic.lookup("java:comp/env");
            return namingContext;
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(oldLoader);
        }
    }

}
