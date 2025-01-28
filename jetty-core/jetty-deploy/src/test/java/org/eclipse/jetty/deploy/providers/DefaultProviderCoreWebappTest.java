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
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.deploy.AbstractCleanEnvironmentTest;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.CoreWebAppContext;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(WorkDirExtension.class)
public class DefaultProviderCoreWebappTest extends AbstractCleanEnvironmentTest
{
    public WorkDir workDir;
    private Server server;

    public void startServer(DefaultProvider provider) throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        DeploymentManager deploymentManager = new DeploymentManager();
        deploymentManager.setContexts(contexts);

        deploymentManager.addBean(provider);
        provider.setDeploymentManager(deploymentManager);
        server.addBean(deploymentManager);

        server.start();
    }

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testExampleCoreDir() throws Exception
    {
        Path baseDir = workDir.getEmptyPathDir();

        Path webapps = baseDir.resolve("webapps");
        FS.ensureDirExists(webapps);

        Path demoDir = webapps.resolve("demo.d");
        FS.ensureDirExists(demoDir);

        Path srcZip = MavenPaths.targetDir().resolve("core-webapps/jetty-test-core-example-webapp.zip");
        Assertions.assertTrue(Files.exists(srcZip), "Src Zip should exist: " + srcZip);
        unpack(srcZip, demoDir);

        // ensure that demo jar isn't in our test/server classpath.
        // it should only exist in the jar file on disk.
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.example.ExampleHandler"));

        Path demoXml = webapps.resolve("demo.xml");
        String demoXmlStr = """
            <?xml version="1.0"?>
            <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure.dtd">
            <Configure class="org.eclipse.jetty.server.handler.CoreWebAppContext">
              <Set name="contextPath">/demo</Set>
              <Set name="handler">
                <New class="org.example.ExampleHandler" />
              </Set>
            </Configure>
            """;
        Files.writeString(demoXml, demoXmlStr);

        DefaultProvider defaultProvider = new DefaultProvider();
        defaultProvider.addMonitoredDirectory(webapps);
        DefaultProvider.EnvironmentConfig coreConfig = defaultProvider.configureEnvironment("core");
        coreConfig.setContextHandlerClass(CoreWebAppContext.class.getName());

        startServer(defaultProvider);

        URI destURI = server.getURI().resolve("/demo/");
        HttpURLConnection http = (HttpURLConnection)destURI.toURL().openConnection();
        assertThat(http.getResponseCode(), is(200));
        String responseBody = IO.toString(http.getInputStream());
        assertThat(responseBody, containsString(Server.getVersion()));
        assertThat(responseBody, containsString(destURI.getPath()));
        assertThat(responseBody, containsString("it all looks so easy."));
    }

    private void unpack(Path srcPath, Path destPath) throws IOException
    {
        Map<String, String> env = new HashMap<>();

        URI jarUri = URIUtil.uriJarPrefix(srcPath.toUri(), "!/");
        try (FileSystem zipfs = FileSystems.newFileSystem(jarUri, env))
        {
            Path root = zipfs.getPath("/");
            IO.copyDir(root, destPath);
        }
    }
}
