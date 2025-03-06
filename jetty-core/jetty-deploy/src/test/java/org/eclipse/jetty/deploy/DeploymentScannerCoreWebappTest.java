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

package org.eclipse.jetty.deploy;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.CoreContextHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(WorkDirExtension.class)
public class DeploymentScannerCoreWebappTest extends AbstractCleanEnvironmentTest
{
    public WorkDir workDir;
    private final Server server = new Server();
    private final ContextHandlerCollection contexts = new ContextHandlerCollection();

    public void startServer(Deployer deployer, Object... beans) throws Exception
    {
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(contexts);
        server.addBean(deployer);
        for (Object bean : beans)
            server.addBean(bean);
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
            <Configure class="org.eclipse.jetty.server.handler.CoreContextHandler">
              <Set name="contextPath">/demo</Set>
              <Set name="handler">
                <New class="org.example.ExampleHandler" />
              </Set>
            </Configure>
            """;
        Files.writeString(demoXml, demoXmlStr);

        server.setHandler(contexts);
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        GoalDeployer goalDeployer = new GoalDeployer(contexts);
        server.addBean(goalDeployer);

        DeploymentScanner deploymentScanner = new DeploymentScanner(server, goalDeployer);
        deploymentScanner.addMonitoredDirectory(webapps);
        server.addBean(deploymentScanner);
        DeploymentScanner.EnvironmentConfig coreConfig = deploymentScanner.configureEnvironment("core");
        coreConfig.setContextHandlerClass(CoreContextHandler.class.getName());

        server.start();
        deploymentScanner.start();

        URI destURI = server.getURI().resolve("/demo/");
        HttpURLConnection http = (HttpURLConnection)destURI.toURL().openConnection();
        assertThat(http.getResponseCode(), is(200));
        String responseBody = IO.toString(http.getInputStream());
        assertThat(responseBody, containsString(Server.getVersion()));
        assertThat(responseBody, containsString(destURI.getPath()));
        assertThat(responseBody, containsString("it all looks so easy."));
    }

    /**
     * Test of a core deployment that will fail the DeploymentManager startup due to an exception triggered from the XML.
     * This is at a point in time before the Core app is even added to the ContextHandlerCollection
     */
    @Test
    public void testFailureXml() throws Exception
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
            <Configure class="org.eclipse.jetty.server.handler.CoreContextHandler">
              <Set name="contextPath">/demo</Set>
              <Set name="handler">
                <New class="org.example.BogusHandler" /> <!-- THIS DOESN'T EXIST -->
              </Set>
            </Configure>
            """;
        GoalDeployer goalDeployer = new GoalDeployer(contexts);
        DeploymentScanner scanner = new DeploymentScanner(server, goalDeployer);
        Files.writeString(demoXml, demoXmlStr);

        scanner.addMonitoredDirectory(webapps);
        DeploymentScanner.EnvironmentConfig coreConfig = scanner.configureEnvironment("core");
        coreConfig.setContextHandlerClass(CoreContextHandler.class.getName());

        try (StacklessLogging ignore = new StacklessLogging(DeploymentScanner.class))
        {
            Throwable throwable = assertThrows(Throwable.class, () -> startServer(goalDeployer, scanner));

            // unwrap any ExecutionExceptions
            while (throwable.getCause() != null)
                throwable = throwable.getCause();

            // Verify that we saw the message
            assertThat(throwable, instanceOf(ClassNotFoundException.class));
            assertThat(throwable.getMessage(), is("org.example.BogusHandler"));
        }
    }

    /**
     * Test of a core deployment that will fail DeploymentManager startup due to an exception during the
     * ContextHandlerCollection.deployHandler() step of the core app.
     */
    @Test
    public void testFailureDeploy() throws IOException
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
            <Configure class="org.eclipse.jetty.server.handler.CoreContextHandler">
              <Set name="contextPath">/demo</Set>
              <Set name="handler">
                <New class="org.example.ExampleBadSetServerHandler" />
              </Set>
            </Configure>
            """;
        Files.writeString(demoXml, demoXmlStr);

        GoalDeployer goalDeployer = new GoalDeployer(contexts);
        DeploymentScanner scanner = new DeploymentScanner(server, goalDeployer);
        scanner.addMonitoredDirectory(webapps);
        DeploymentScanner.EnvironmentConfig coreConfig = scanner.configureEnvironment("core");
        coreConfig.setContextHandlerClass(CoreContextHandler.class.getName());

        try (StacklessLogging ignore = new StacklessLogging(
            // screwy name courtesy of SerializedInvoker.onError() logic
            "org.eclipse.jetty.server.handler.ContextHandlerCollection$1",
            GoalDeployer.class.getName(),
            DeploymentScanner.class.getName(),
            Scanner.class.getName()))
        {
            Throwable throwable = assertThrows(Throwable.class, () -> startServer(goalDeployer, scanner));

            // unwrap any ExecutionExceptions
            while (throwable.getCause() != null)
                throwable = throwable.getCause();

            // Verify that we saw the message
            assertThat(throwable, instanceOf(RuntimeException.class));
            assertThat(throwable.getMessage(), is("Example of failing startup"));
        }
    }

    /**
     * Test of a core deployment that will fail DeploymentManager startup due to an exception during the
     * ContextHandler.doStart() step of the core app.
     */
    @Test
    public void testFailureDoStart() throws IOException
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
            <Configure class="org.eclipse.jetty.server.handler.CoreContextHandler">
              <Set name="contextPath">/demo</Set>
              <Set name="handler">
                <New class="org.example.ExampleBadStartHandler" />
              </Set>
            </Configure>
            """;
        Files.writeString(demoXml, demoXmlStr);

        GoalDeployer goalDeployer = new GoalDeployer(contexts);
        DeploymentScanner scanner = new DeploymentScanner(server, goalDeployer);
        scanner.addMonitoredDirectory(webapps);
        DeploymentScanner.EnvironmentConfig coreConfig = scanner.configureEnvironment("core");
        coreConfig.setContextHandlerClass(CoreContextHandler.class.getName());

        try (StacklessLogging ignore = new StacklessLogging(DeploymentScanner.class, GoalDeployer.class))
        {
            Throwable throwable = assertThrows(Throwable.class, () -> startServer(goalDeployer, scanner));

            // unwrap any ExecutionExceptions
            while (throwable instanceof ExecutionException ee)
            {
                throwable = ee.getCause();
            }

            // Verify that we saw the message
            assertThat(throwable, instanceOf(RuntimeException.class));
            assertThat(throwable.getMessage(), is("Example of failing startup"));
        }
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
