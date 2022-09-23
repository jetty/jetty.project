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

package org.eclipse.jetty.ee9.websocket.tests;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;

import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.ee9.websocket.server.config.JettyWebSocketConfiguration;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;

public class JettyWebSocketWebApp extends WebAppContext
{
    private static final Logger LOG = LoggerFactory.getLogger(JettyWebSocketWebApp.class);

    private final Path contextDir;
    private final Path webInf;
    private final Path classesDir;

    public JettyWebSocketWebApp(String contextName)
    {
        // Ensure context directory.
        Path testDir = MavenTestingUtils.getTargetTestingPath(JettyWebSocketWebApp.class.getName());
        contextDir = testDir.resolve(contextName);
        FS.ensureEmpty(contextDir);

        // Ensure WEB-INF directories.
        webInf = contextDir.resolve("WEB-INF");
        FS.ensureDirExists(webInf);
        classesDir = webInf.resolve("classes");
        FS.ensureDirExists(classesDir);

        // Configure the WebAppContext.
        setContextPath("/" + contextName);
        setBaseResource(this.getResourceFactory().newResource(contextDir));
        addConfiguration(new JettyWebSocketConfiguration());
    }

    public Path getContextDir()
    {
        return contextDir;
    }

    public void createWebXml() throws IOException
    {
        String emptyWebXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<web-app xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns=\"http://java.sun.com/xml/ns/javaee\" " +
            "xsi:schemaLocation=\"http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-app_3_0.xsd\" " +
            "metadata-complete=\"false\" version=\"3.0\"></web-app>";

        Path webXml = webInf.resolve("web.xml");
        try (FileWriter writer = new FileWriter(webXml.toFile()))
        {
            writer.write(emptyWebXml);
        }
    }

    public void copyWebXml(Path webXml) throws IOException
    {
        IO.copy(webXml.toFile(), webInf.resolve("web.xml").toFile());
    }

    public void copyClass(Class<?> clazz) throws Exception
    {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        String endpointPath = TypeUtil.toClassReference(clazz);
        URL classUrl = cl.getResource(endpointPath);
        assertThat("Class URL for: " + clazz, classUrl, notNullValue());
        Path destFile = classesDir.resolve(endpointPath);
        FS.ensureDirExists(destFile.getParent());
        File srcFile = new File(classUrl.toURI());
        IO.copy(srcFile, destFile.toFile());
    }
}
