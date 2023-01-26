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

package org.eclipse.jetty.ee10.maven.plugin;

import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.CombinedResource;
import org.eclipse.jetty.util.resource.Resource;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestWebAppPropertyConverter
{
    static Path testDir;
    static String contextXml;
    static File tmpDir;
    static File classesDir;
    static File testClassesDir;
    static File jar1;
    static File jar2;
    static File war;
    static File override1;
    static File override2;
    static File webXml;

    @BeforeAll
    public static void setUp() throws Exception
    {
        testDir = MavenTestingUtils.getTargetTestingPath("TestWebApPropertyConverter");
        Files.createDirectories(testDir);
        contextXml = MavenTestingUtils.getTestResourcePathFile("embedder-context.xml").toFile().getAbsolutePath();
        tmpDir = new File(testDir.toFile(), "testToProperties");
        tmpDir.mkdirs();
        classesDir = new File(testDir.toFile(), "imaginaryClasses");
        classesDir.mkdirs();
        testClassesDir = new File(testDir.toFile(), "imaginaryTestClasses");
        testClassesDir.mkdirs();
        jar1 = new File(testDir.toFile(), "imaginary1.jar");
        jar1.createNewFile();
        jar2 = new File(testDir.toFile(), "imaginary2.jar");
        jar2.createNewFile();
        war = new File(testDir.toFile(), "imaginary.war");
        war.createNewFile();
        override1 = new File(testDir.toFile(), "override-web1.xml");
        override1.createNewFile();
        override2 = new File(testDir.toFile(), "override-web2.xml");
        override2.createNewFile();
        webXml = new File(testDir.toFile(), "web.xml");
        webXml.createNewFile();
    }

    @AfterAll
    public static void tearDown() throws Exception
    {
        IO.delete(testDir);
    }

    @Test
    public void testToProperties() throws Exception
    {
        File propsFile = new File(testDir.toFile(), "webapp.props");
        if (propsFile.exists())
            propsFile.delete();
        propsFile.createNewFile();

        MavenWebAppContext webApp = new MavenWebAppContext();
        webApp.setContextPath("/foo");
        webApp.setBaseResourceAsPath(MavenTestingUtils.getTestResourcePathDir("root"));
        webApp.setTempDirectory(tmpDir);
        webApp.setTempDirectoryPersistent(false);
        webApp.setClasses(classesDir);
        webApp.setTestClasses(testClassesDir);
        webApp.setWebInfLib(Arrays.asList(jar1, jar2));
        webApp.setWar(war.getAbsolutePath());
        webApp.addOverrideDescriptor(override1.getAbsolutePath());
        webApp.addOverrideDescriptor(override2.getAbsolutePath());
        WebAppPropertyConverter.toProperties(webApp, propsFile, contextXml);

        assertTrue(propsFile.exists());
        Properties props = new Properties();
        props.load(new FileInputStream(propsFile));
        assertEquals("/foo", props.get(WebAppPropertyConverter.CONTEXT_PATH));
        assertEquals(contextXml, props.get(WebAppPropertyConverter.CONTEXT_XML));
        assertEquals(tmpDir.getAbsolutePath(), props.get(WebAppPropertyConverter.TMP_DIR));
        assertEquals("false", props.get(WebAppPropertyConverter.TMP_DIR_PERSIST));
        assertEquals(classesDir.getAbsolutePath(), props.get(WebAppPropertyConverter.CLASSES_DIR));
        assertEquals(testClassesDir.getAbsolutePath(), props.get(WebAppPropertyConverter.TEST_CLASSES_DIR));
        assertEquals(String.join(",", jar1.getAbsolutePath(), jar2.getAbsolutePath()), props.get(WebAppPropertyConverter.LIB_JARS));
        assertEquals(war.getAbsolutePath(), props.get(WebAppPropertyConverter.WAR_FILE));
        assertEquals(WebAppContext.WEB_DEFAULTS_XML, props.get(WebAppPropertyConverter.DEFAULTS_DESCRIPTOR));
        assertEquals(String.join(",", override1.getAbsolutePath(), override2.getAbsolutePath()), props.get(WebAppPropertyConverter.OVERRIDE_DESCRIPTORS));
    }

    @Test
    public void testFromProperties() throws Exception
    {
        File base1 = new File(testDir.toFile(), "base1");
        base1.mkdirs();
        File base2 = new File(testDir.toFile(), "base2");
        base2.mkdirs();
        MavenWebAppContext webApp = new MavenWebAppContext();
        Properties props = new Properties();
        props.setProperty(WebAppPropertyConverter.BASE_DIRS, String.join(",", base1.getAbsolutePath(), base2.getAbsolutePath()));
        props.setProperty(WebAppPropertyConverter.CLASSES_DIR, classesDir.getAbsolutePath());
        props.setProperty(WebAppPropertyConverter.CONTEXT_PATH, "/foo");
        props.setProperty(WebAppPropertyConverter.CONTEXT_XML, contextXml);
        props.setProperty(WebAppPropertyConverter.LIB_JARS, String.join(",", jar1.getAbsolutePath(), jar2.getAbsolutePath()));
        props.setProperty(WebAppPropertyConverter.OVERRIDE_DESCRIPTORS, String.join(",", override1.getAbsolutePath(), override2.getAbsolutePath()));
        //props.setProperty(WebAppPropertyConverter.QUICKSTART_WEB_XML, value);
        props.setProperty(WebAppPropertyConverter.TEST_CLASSES_DIR, testClassesDir.getAbsolutePath());
        props.setProperty(WebAppPropertyConverter.TMP_DIR, tmpDir.getAbsolutePath());
        props.setProperty(WebAppPropertyConverter.TMP_DIR_PERSIST, "true");
        props.setProperty(WebAppPropertyConverter.WAR_FILE, war.getAbsolutePath());
        props.setProperty(WebAppPropertyConverter.WEB_XML, webXml.getAbsolutePath());
        WebAppPropertyConverter.fromProperties(webApp, props, new Server(), null);

        assertEquals("/embedder", webApp.getContextPath()); //the embedder-context file changes the context path
        assertEquals(classesDir, webApp.getClasses());
        assertEquals(testClassesDir, webApp.getTestClasses());
        assertThat(webApp.getWebInfLib(), Matchers.contains(jar1, jar2));
        assertThat(webApp.getOverrideDescriptors(), Matchers.contains(override1.getAbsolutePath(), override2.getAbsolutePath()));
        assertEquals(tmpDir, webApp.getTempDirectory());
        assertEquals(true, webApp.isTempDirectoryPersistent());
        assertEquals(war.getAbsolutePath(), webApp.getWar());
        assertEquals(webXml.getAbsolutePath(), webApp.getDescriptor());
        assertThat(webApp.getBaseResource(), instanceOf(CombinedResource.class));

        Resource combinedResource = webApp.getBaseResource();
        List<URI> actual = new ArrayList<>();
        for (Resource r : combinedResource)
            if (r != null)
                actual.add(r.getURI());
        URI[] expected = new URI[]{base1.toURI(), base2.toURI()};
        assertThat(actual, containsInAnyOrder(expected));
    }
}
