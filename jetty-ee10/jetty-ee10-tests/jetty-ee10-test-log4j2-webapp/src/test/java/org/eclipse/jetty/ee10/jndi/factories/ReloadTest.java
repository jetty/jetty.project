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

package org.eclipse.jetty.ee10.jndi.factories;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.providers.ContextProvider;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.xml.EnvironmentBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WorkDirExtension.class)
public class ReloadTest
{
    private static Environment ee10;
    private Server server;
    private Path warPath;

    @BeforeAll
    public static void initEnvironment() throws Exception
    {
        String environmentName = "ee10";
        ee10 = Environment.get(environmentName);
        if (ee10 == null)
        {
            ee10 = new EnvironmentBuilder(environmentName).build();
            Environment.set(ee10);
        }

        // === jetty-ee10-deploy.xml ===
        ee10.setAttribute("contextHandlerClass", org.eclipse.jetty.ee10.webapp.WebAppContext.class.getName());
    }

    @BeforeEach
    public void createWar() throws IOException
    {
        Path warTemp = MavenPaths.targetTestDir("ReloadTest-warPath");
        FS.ensureEmpty(warTemp);

        warPath = warTemp.resolve("jetty-ee10-log4j2.war");

        Path mavenWebAppPath = MavenPaths.targetDir().resolve("jetty-ee10-log4j2");
        Assertions.assertTrue(Files.isDirectory(mavenWebAppPath), "Maven webAppPath must exist");

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI uri = URI.create("jar:" + warPath.toUri().toASCIIString());
        // Use ZipFS so that we can create paths that are just "/"
        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            Path root = zipfs.getPath("/");
            IO.copyDir(mavenWebAppPath, root);
        }
    }

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testReload(WorkDir workdir) throws Exception
    {
        server = new Server(0);

        Path base = workdir.getEmptyPathDir();

        Path tempDir = base.resolve("work");
        FS.ensureEmpty(tempDir);
        server.setTempDirectory(tempDir.toString());

        Path webappsDir = base.resolve("webapps");
        FS.ensureDirExists(webappsDir);

        Path warFile = Files.copy(warPath, webappsDir.resolve("test.war"));

        String warXml = """
            <?xml version="1.0"  encoding="ISO-8859-1"?>
            <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://www.eclipse.org/jetty/configure_10_0.dtd">
            <Configure class="org.eclipse.jetty.ee10.webapp.WebAppContext">
               <Set name="contextPath">/test</Set>
               <Set name="war">$WAR</Set>
               <Set name="tempDirectory">$TMP/test</Set>
               <Set name="tempDirectoryPersistent">false</Set>
            </Configure>
            """
            .replace("$WAR", warFile.toString())
            .replace("$TMP", tempDir.toString());

        Path warXmlPath = webappsDir.resolve("test.xml");
        Files.writeString(warXmlPath, warXml, StandardCharsets.UTF_8);

        DeploymentManager deploymentManager = new DeploymentManager();
        ContextProvider contextProvider = new ContextProvider();
        contextProvider.setEnvironmentName(ee10.getName());
        contextProvider.setMonitoredDirName(webappsDir.toString());
        contextProvider.setScanInterval(1);
        deploymentManager.addAppProvider(contextProvider);

        server.addBean(deploymentManager);

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        deploymentManager.setContexts(contexts);

        server.start();

        Thread.sleep(2000);
        touch(warXmlPath);

        Thread.sleep(600000);
    }

    private void touch(Path path) throws IOException
    {
        System.err.println("touch: " + path);
        FileTime now = FileTime.fromMillis(System.currentTimeMillis());
        Files.setLastModifiedTime(path, now);
    }
}
