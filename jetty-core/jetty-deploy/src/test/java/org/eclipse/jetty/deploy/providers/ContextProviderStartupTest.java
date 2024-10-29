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

package org.eclipse.jetty.deploy.providers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import org.eclipse.jetty.deploy.BarContextHandler;
import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
import org.eclipse.jetty.server.Deployable;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link ContextProvider} as it starts up for the first time.
 */
@ExtendWith(WorkDirExtension.class)
public class ContextProviderStartupTest
{
    public WorkDir testdir;
    private static XmlConfiguredJetty jetty;

    @BeforeEach
    public void setupEnvironment() throws Exception
    {
        Path p = testdir.getEmptyPathDir();
        jetty = new XmlConfiguredJetty(p);

        Path resourceBase = jetty.getJettyBasePath().resolve("resourceBase");
        FS.ensureDirExists(resourceBase);
        jetty.setProperty("test.bar.resourceBase", resourceBase.toUri().toASCIIString());

        Files.writeString(resourceBase.resolve("text.txt"), "This is the resourceBase text");

        jetty.addConfiguration("jetty.xml");
        jetty.addConfiguration("jetty-http.xml");
        jetty.addConfiguration("jetty-deploymgr-contexts.xml");

        // Setup initial context
        jetty.copyWebapp("bar-core-context.xml", "bar.xml");

        // Should not throw an Exception
        jetty.load();
    }

    public void startJetty() throws Exception
    {
        // Start it
        jetty.start();
    }

    @AfterEach
    public void teardownEnvironment() throws Exception
    {
        LifeCycle.stop(jetty);
    }

    @Test
    public void testStartupContext() throws Exception
    {
        startJetty();

        // Check Server for Handlers
        jetty.assertContextHandlerExists("/bar");
    }

    @Test
    public void testStartupWithRelativeEnvironmentContext() throws Exception
    {
        Path jettyBase = jetty.getJettyBasePath();
        Path propsFile = Files.writeString(jettyBase.resolve("webapps/core.properties"), Deployable.ENVIRONMENT_XML + " = etc/core-context.xml", StandardOpenOption.CREATE_NEW);
        Path props2File = Files.writeString(jettyBase.resolve("webapps/core-other.properties"), Deployable.ENVIRONMENT_XML + ".other =  etc/core-context-other.xml", StandardOpenOption.CREATE_NEW);
        assertTrue(Files.exists(propsFile));
        assertTrue(Files.exists(props2File));
        Files.copy(MavenPaths.findTestResourceFile("etc/core-context.xml"), jettyBase.resolve("etc/core-context.xml"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(MavenPaths.findTestResourceFile("etc/core-context-other.xml"), jettyBase.resolve("etc/core-context-other.xml"), StandardCopyOption.REPLACE_EXISTING);

        jetty.copyWebapp("bar-core-context.properties", "bar.properties");
        startJetty();

        //check core-context.xml was applied to the produced context
        ContextHandler context = jetty.getContextHandler("/bar");
        assertNotNull(context);
        assertThat(context.getAttribute("core-context-0"), equalTo("core-context-0"));
        assertTrue(context instanceof BarContextHandler);
        //check core-context-other.xml was applied to the produced context
        assertThat(context.getAttribute("other"), equalTo("othervalue"));
        //check core-context-other.xml was applied AFTER core-context.xml
        assertThat(context.getAttribute("somename"), equalTo("othervalue"));
    }

    @Test
    public void testStartupWithAbsoluteEnvironmentContext() throws Exception
    {
        Path jettyBase = jetty.getJettyBasePath();
        Path propsFile = Files.writeString(jettyBase.resolve("webapps/core.properties"), Deployable.ENVIRONMENT_XML + " = " +
            MavenPaths.findTestResourceFile("etc/core-context.xml"), StandardOpenOption.CREATE_NEW);
        assertTrue(Files.exists(propsFile));
        Path props2File = Files.writeString(jettyBase.resolve("webapps/core-other.properties"), Deployable.ENVIRONMENT_XML + ".other =  " + MavenPaths.findTestResourceFile("etc/core-context-other.xml"), StandardOpenOption.CREATE_NEW);
         assertTrue(Files.exists(props2File));
        Files.copy(MavenPaths.findTestResourceFile("etc/core-context.xml"), jettyBase.resolve("etc/core-context.xml"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(MavenPaths.findTestResourceFile("etc/core-context-other.xml"), jettyBase.resolve("etc/core-context-other.xml"), StandardCopyOption.REPLACE_EXISTING);
        jetty.copyWebapp("bar-core-context.properties", "bar.properties");
        startJetty();

        //check core environment context xml was applied to the produced context
        ContextHandler context = jetty.getContextHandler("/bar");
        assertNotNull(context);
        assertThat(context.getAttribute("core-context-0"), equalTo("core-context-0"));
        assertTrue(context instanceof BarContextHandler);
        //check core-context-other.xml was applied to the produced context
        assertThat(context.getAttribute("other"), equalTo("othervalue"));
        //check core-context-other.xml was applied AFTER core-context.xml
        assertThat(context.getAttribute("somename"), equalTo("othervalue"));
    }

    @Test
    public void testNonEnvironmentPropertyFileNotApplied() throws Exception
    {
        Path jettyBase = jetty.getJettyBasePath();
        Path propsFile = Files.writeString(jettyBase.resolve("webapps/non-env.properties"), Deployable.ENVIRONMENT_XML + " = some/file/that/should/be/ignored.txt", StandardOpenOption.CREATE_NEW);
        Path propsEE8File = Files.writeString(jettyBase.resolve("webapps/ee8.properties"), Deployable.ENVIRONMENT_XML + " = some/file/that/should/be/ignored.txt", StandardOpenOption.CREATE_NEW);
        Path propsEE9File = Files.writeString(jettyBase.resolve("webapps/ee9.properties"), Deployable.ENVIRONMENT_XML + " = some/file/that/should/be/ignored.txt", StandardOpenOption.CREATE_NEW);
        Path propsEE10File = Files.writeString(jettyBase.resolve("webapps/ee10.properties"), Deployable.ENVIRONMENT_XML + " = some/file/that/should/be/ignored.txt", StandardOpenOption.CREATE_NEW);
        Path propsNonCoreFile = Files.writeString(jettyBase.resolve("webapps/not-core.properties"), Deployable.ENVIRONMENT_XML + " = some/file/that/should/be/ignored.txt", StandardOpenOption.CREATE_NEW);
        assertTrue(Files.exists(propsFile));
        assertTrue(Files.exists(propsEE8File));
        assertTrue(Files.exists(propsEE9File));
        assertTrue(Files.exists(propsEE10File));
        jetty.copyWebapp("bar-core-context.properties", "bar.properties");
        startJetty();

        //test that the context was deployed as expected and that the non-applicable properties files were ignored
        ContextHandler context = jetty.getContextHandler("/bar");
        assertNotNull(context);
        assertTrue(context instanceof BarContextHandler);
    }

    /**
     * Test that properties of the same name will be overridden, in the order of the name of the .properties file
     * @throws Exception
     */
    @Test
    public void testPropertyOverriding() throws Exception
    {
        Path jettyBase = jetty.getJettyBasePath();
        Path propsCoreAFile = Files.writeString(jettyBase.resolve("webapps/core-a.properties"), Deployable.ENVIRONMENT_XML + " = etc/a.xml", StandardOpenOption.CREATE_NEW);
        Path propsCoreBFile = Files.writeString(jettyBase.resolve("webapps/core-b.properties"), Deployable.ENVIRONMENT_XML + " = etc/b.xml", StandardOpenOption.CREATE_NEW);
        Path propsCoreCFile = Files.writeString(jettyBase.resolve("webapps/core-c.properties"), Deployable.ENVIRONMENT_XML + " = etc/c.xml", StandardOpenOption.CREATE_NEW);
        Path propsCoreDFile = Files.writeString(jettyBase.resolve("webapps/core-d.properties"), Deployable.ENVIRONMENT_XML + " = etc/d.xml", StandardOpenOption.CREATE_NEW);
        assertTrue(Files.exists(propsCoreAFile));
        assertTrue(Files.exists(propsCoreBFile));
        assertTrue(Files.exists(propsCoreCFile));
        assertTrue(Files.exists(propsCoreDFile));
        Path aPath = jettyBase.resolve("etc/a.xml");
        writeXmlDisplayName(aPath, "A WebApp");
        Path bPath = jettyBase.resolve("etc/b.xml");
        writeXmlDisplayName(bPath, "B WebApp");
        Path cPath = jettyBase.resolve("etc/c.xml");
        writeXmlDisplayName(cPath, "C WebApp");
        Path dPath = jettyBase.resolve("etc/d.xml");
        writeXmlDisplayName(dPath, "D WebApp");
        assertTrue(Files.exists(propsCoreAFile));
        assertTrue(Files.exists(propsCoreBFile));
        assertTrue(Files.exists(propsCoreCFile));
        assertTrue(Files.exists(propsCoreDFile));

        jetty.copyWebapp("bar-core-context.properties", "bar.properties");
        startJetty();

        ContextHandler context = jetty.getContextHandler("/bar");
        assertNotNull(context);
        assertEquals("D WebApp", context.getDisplayName());
    }

    /**
     * Test that properties defined in an environment-specific properties file
     * are used for substitution.
     *
     * @throws Exception
     */
    @Test
    public void testPropertySubstitution() throws Exception
    {
        Path jettyBase = jetty.getJettyBasePath();
        Path propsCoreAFile = Files.writeString(jettyBase.resolve("webapps/core.properties"), Deployable.ENVIRONMENT_XML + " = etc/core-context-sub.xml\ntest.displayName=DisplayName Set By Property", StandardOpenOption.CREATE_NEW);
        Files.copy(MavenPaths.findTestResourceFile("etc/core-context-sub.xml"), jettyBase.resolve("etc/core-context-sub.xml"), StandardCopyOption.REPLACE_EXISTING);
        jetty.copyWebapp("bar-core-context.properties", "bar.properties");
        startJetty();
        ContextHandler context = jetty.getContextHandler("/bar");
        assertNotNull(context);
        assertEquals("DisplayName Set By Property", context.getDisplayName());
    }

    private static void writeXmlDisplayName(Path filePath, String displayName) throws IOException
    {
        String content = "<!DOCTYPE Configure PUBLIC \"-//Jetty//Configure//EN\" \"https://jetty.org/configure_10_0.dtd\">\n" +
                            "<Configure class=\"org.eclipse.jetty.server.handler.ContextHandler\">\n" +
                            "    <Set name=\"displayName\">";

         Files.writeString(filePath, content + displayName + "</Set>\n</Configure>", StandardOpenOption.CREATE_NEW);
    }
}
