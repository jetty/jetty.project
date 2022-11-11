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

package org.eclipse.jetty.ee10.websocket.jakarta.tests;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketConfiguration;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.JAR;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Utility to build out exploded directory WebApps, in the /target/tests/ directory, for testing out servers that use jakarta.websocket endpoints.
 * <p>
 * This is particularly useful when the WebSocket endpoints are discovered via the jakarta.websocket annotation scanning.
 */
public class WSServer extends LocalServer implements LocalFuzzer.Provider
{
    private static final Logger LOG = LoggerFactory.getLogger(WSServer.class);
    private final Path testDir;
    private final ContextHandlerCollection contexts = new ContextHandlerCollection();

    public WSServer()
    {
        String baseDirName = Long.toString(Math.abs(new Random().nextLong()));
        this.testDir = MavenTestingUtils.getTargetTestingPath(baseDirName);
        if (Files.exists(testDir))
            throw new IllegalStateException("TestDir already exists.");
        FS.ensureDirExists(testDir);
    }

    public WSServer(Path testDir)
    {
        this.testDir = testDir;
    }

    public WebApp createWebApp(String contextName)
    {
        return new WebApp(contextName);
    }

    @Override
    protected Handler createRootHandler(Server server)
    {
        return contexts;
    }

    public class WebApp
    {
        private final WebAppContext context;
        private final Path contextDir;
        private final Path webInf;
        private final Path classesDir;
        private final Path libDir;

        private WebApp(String contextName)
        {
            // Ensure context directory.
            contextDir = testDir.resolve(contextName);
            FS.ensureEmpty(contextDir);

            // Ensure WEB-INF directories.
            webInf = contextDir.resolve("WEB-INF");
            FS.ensureDirExists(webInf);
            classesDir = webInf.resolve("classes");
            FS.ensureDirExists(classesDir);
            libDir = webInf.resolve("lib");
            FS.ensureDirExists(libDir);

            // Configure the WebAppContext.
            context = new WebAppContext();
            context.setContextPath("/" + contextName);
            context.setInitParameter("org.eclipse.jetty.ee10.servlet.Default.dirAllowed", "false");
            context.setBaseResourceAsPath(contextDir);
            context.setAttribute("org.eclipse.jetty.websocket.jakarta", Boolean.TRUE);
            context.addConfiguration(new JakartaWebSocketConfiguration());
        }

        public WebAppContext getWebAppContext()
        {
            return context;
        }

        public String getContextPath()
        {
            return context.getContextPath();
        }

        public Path getContextDir()
        {
            return contextDir;
        }

        public void createWebInf() throws IOException
        {
            copyWebInf("empty-web.xml");
        }

        public void copyWebInf(String testResourceName) throws IOException
        {
            File testWebXml = MavenTestingUtils.getTestResourceFile(testResourceName);
            Path webXml = webInf.resolve("web.xml");
            Files.deleteIfExists(webXml);
            IO.copy(testWebXml, webXml.toFile());
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

        public void copyLib(Class<?> clazz, String jarFileName) throws URISyntaxException, IOException
        {
            Path jarFile = libDir.resolve(jarFileName);

            URL codeSourceURL = clazz.getProtectionDomain().getCodeSource().getLocation();
            assertThat("Class CodeSource URL is file scheme", codeSourceURL.getProtocol(), is("file"));

            File sourceCodeSourceFile = new File(codeSourceURL.toURI());
            if (sourceCodeSourceFile.isDirectory())
            {
                LOG.info("Creating " + jarFile + " from " + sourceCodeSourceFile);
                JAR.create(sourceCodeSourceFile, jarFile.toFile());
            }
            else
            {
                LOG.info("Copying " + sourceCodeSourceFile + " to " + jarFile);
                IO.copy(sourceCodeSourceFile, jarFile.toFile());
            }
        }

        public void deploy()
        {
            contexts.addHandler(context);
            contexts.manage(context);
            context.setThrowUnavailableOnStartupException(true);
            if (LOG.isDebugEnabled())
                LOG.debug("{}", context.dump());
        }
    }
}
