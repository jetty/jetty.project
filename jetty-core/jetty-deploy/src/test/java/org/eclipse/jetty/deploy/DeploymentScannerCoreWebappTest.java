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
import java.util.List;
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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(WorkDirExtension.class)
public class DeploymentScannerCoreWebappTest extends AbstractCleanEnvironmentTest
{
    public WorkDir workDir;
    private final Server server = new Server();
    private final ContextHandlerCollection contexts = new ContextHandlerCollection();

    public void startServer(Object... beans) throws Exception
    {
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(contexts);
        for (Object bean : beans)
            server.addBean(bean);
        server.start();
    }

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "demo.d", // nominated dir (backward compat)
        "demo" // normal dir
    })
    public void testExampleCoreDir(String dirname) throws Exception
    {
        Path baseDir = workDir.getEmptyPathDir();

        Path webapps = baseDir.resolve("webapps");
        FS.ensureDirExists(webapps);

        Path demoDir = webapps.resolve(dirname);
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

        DeploymentScanner deploymentScanner = new DeploymentScanner(server);
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

    @Test
    public void testExampleCoreDirWithEnvironmentsProps() throws Exception
    {
        Path root = workDir.getEmptyPathDir();

        Path environments = root.resolve("environments");
        FS.ensureDirExists(environments);
        Files.writeString(environments.resolve("core.properties"), """
            custom.displayPrefix=Customized
            """);

        Path webapps = root.resolve("webapps");
        FS.ensureDirExists(webapps);

        Path demoDir = webapps.resolve("demo");
        FS.ensureDirExists(demoDir);

        Path staticDir = demoDir.resolve("static");
        FS.ensureDirExists(staticDir);
        Files.writeString(staticDir.resolve("index.html"), """
            This is the static index.html
            """);

        Path demoXml = webapps.resolve("demo.xml");
        String demoXmlStr = """
            <?xml version="1.0"?>
            <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure.dtd">
            <Configure class="org.eclipse.jetty.server.handler.CoreContextHandler">
              <Set name="displayName"><Property name="custom.displayPrefix" default=""/> Demo</Set>
              <Set name="contextPath">/demo</Set>
            </Configure>
            """;
        Files.writeString(demoXml, demoXmlStr);

        server.setHandler(contexts);
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        DeploymentScanner deploymentScanner = new DeploymentScanner(server);
        deploymentScanner.addMonitoredDirectory(webapps);
        deploymentScanner.setEnvironmentsDirectory(environments);
        server.addBean(deploymentScanner);
        DeploymentScanner.EnvironmentConfig coreConfig = deploymentScanner.configureEnvironment("core");
        coreConfig.setContextHandlerClass(CoreContextHandler.class.getName());

        server.start();
        deploymentScanner.start();

        URI destURI = server.getURI().resolve("/demo/");
        HttpURLConnection http = (HttpURLConnection)destURI.toURL().openConnection();
        assertThat(http.getResponseCode(), is(200));
        String responseBody = IO.toString(http.getInputStream());
        assertThat(responseBody, containsString("This is the static index.html"));

        ContextHandlerCollection contextHandlerCollection = (ContextHandlerCollection)server.getHandler();
        CoreContextHandler coreContextHandler = contextHandlerCollection.getBean(CoreContextHandler.class);
        assertNotNull(coreContextHandler);
        assertEquals("Customized Demo", coreContextHandler.getDisplayName());
    }

    @Test
    public void testExampleCoreDirWithEnvironmentsXml() throws Exception
    {
        Path root = workDir.getEmptyPathDir();

        Path environments = root.resolve("environments");
        FS.ensureDirExists(environments);
        Files.writeString(environments.resolve("core.xml"), """
            <?xml version="1.0"?>
            <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure.dtd">
            <Configure class="org.eclipse.jetty.server.handler.CoreContextHandler">
              <Call name="addVirtualHosts">
                <Arg>
                  <Array type="string">
                    <Item>localhost</Item>
                  </Array>
                </Arg>
              </Call>
            </Configure>
            """);

        Path webapps = root.resolve("webapps");
        FS.ensureDirExists(webapps);

        Path demoDir = webapps.resolve("demo");
        FS.ensureDirExists(demoDir);

        Path staticDir = demoDir.resolve("static");
        FS.ensureDirExists(staticDir);
        Files.writeString(staticDir.resolve("index.html"), """
            This is the static index.html
            """);

        Path demoXml = webapps.resolve("demo.xml");
        String demoXmlStr = """
            <?xml version="1.0"?>
            <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure.dtd">
            <Configure class="org.eclipse.jetty.server.handler.CoreContextHandler">
              <Set name="contextPath">/demo</Set>
            </Configure>
            """;
        Files.writeString(demoXml, demoXmlStr);

        server.setHandler(contexts);
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        DeploymentScanner deploymentScanner = new DeploymentScanner(server);
        deploymentScanner.addMonitoredDirectory(webapps);
        deploymentScanner.setEnvironmentsDirectory(environments);
        server.addBean(deploymentScanner);
        DeploymentScanner.EnvironmentConfig coreConfig = deploymentScanner.configureEnvironment("core");
        coreConfig.setContextHandlerClass(CoreContextHandler.class.getName());

        server.start();
        deploymentScanner.start();

        URI destURI = server.getURI().resolve("/demo/");
        Assumptions.assumeTrue(destURI.getHost().equals("localhost"));
        HttpURLConnection http = (HttpURLConnection)destURI.toURL().openConnection();
        assertThat(http.getResponseCode(), is(200));
        String responseBody = IO.toString(http.getInputStream());
        assertThat(responseBody, containsString("This is the static index.html"));

        ContextHandlerCollection contextHandlerCollection = (ContextHandlerCollection)server.getHandler();
        CoreContextHandler coreContextHandler = contextHandlerCollection.getBean(CoreContextHandler.class);
        assertNotNull(coreContextHandler);
        List<String> virtualHosts = coreContextHandler.getVirtualHosts();
        assertNotNull(virtualHosts);
        assertThat(virtualHosts.size(), is(1));
        assertThat(virtualHosts, contains("localhost"));
    }

    /**
     * Test of a core deployment that will fail the Deployer startup due to an exception triggered from the XML.
     * This is at a point in time before the Core app is even added to the ContextHandlerCollection
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "demo.d", // nominated dir (backward compat)
        "demo" // normal dir
    })
    public void testFailureXml(String dirname) throws Exception
    {
        Path baseDir = workDir.getEmptyPathDir();

        Path webapps = baseDir.resolve("webapps");
        FS.ensureDirExists(webapps);

        Path demoDir = webapps.resolve(dirname);
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
        DeploymentScanner scanner = new DeploymentScanner(server);
        Files.writeString(demoXml, demoXmlStr);

        scanner.addMonitoredDirectory(webapps);
        DeploymentScanner.EnvironmentConfig coreConfig = scanner.configureEnvironment("core");
        coreConfig.setContextHandlerClass(CoreContextHandler.class.getName());

        try (StacklessLogging ignore = new StacklessLogging(DeploymentScanner.class))
        {
            Throwable throwable = assertThrows(Throwable.class, () -> startServer(scanner));

            // unwrap any ExecutionExceptions
            while (throwable.getCause() != null)
                throwable = throwable.getCause();

            // Verify that we saw the message
            assertThat(throwable, instanceOf(ClassNotFoundException.class));
            assertThat(throwable.getMessage(), is("org.example.BogusHandler"));
        }
    }

    /**
     * Test of a core deployment that will fail Deployer startup due to an exception during the
     * ContextHandlerCollection.deployHandler() step of the core app.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "demo.d", // nominated dir (backward compat)
        "demo" // normal dir
    })
    public void testFailureDeploy(String dirname) throws IOException
    {
        Path baseDir = workDir.getEmptyPathDir();

        Path webapps = baseDir.resolve("webapps");
        FS.ensureDirExists(webapps);

        Path demoDir = webapps.resolve(dirname);
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

        DeploymentScanner scanner = new DeploymentScanner(server);
        scanner.addMonitoredDirectory(webapps);
        DeploymentScanner.EnvironmentConfig coreConfig = scanner.configureEnvironment("core");
        coreConfig.setContextHandlerClass(CoreContextHandler.class.getName());

        try (StacklessLogging ignore = new StacklessLogging(
            // screwy name courtesy of SerializedInvoker.onError() logic
            "org.eclipse.jetty.server.handler.ContextHandlerCollection$1",
            StandardDeployer.class.getName(),
            DeploymentScanner.class.getName(),
            Scanner.class.getName()))
        {
            Throwable throwable = assertThrows(Throwable.class, () -> startServer(scanner));

            // unwrap any ExecutionExceptions
            while (throwable.getCause() != null)
                throwable = throwable.getCause();

            // Verify that we saw the message
            assertThat(throwable, instanceOf(RuntimeException.class));
            assertThat(throwable.getMessage(), is("Example of failing startup"));
        }
    }

    /**
     * Test of a core deployment that will fail Deployer startup due to an exception during the
     * ContextHandler.doStart() step of the core app.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "demo.d", // nominated dir (backward compat)
        "demo" // normal dir
    })
    public void testFailureDoStart(String dirname) throws IOException
    {
        Path baseDir = workDir.getEmptyPathDir();

        Path webapps = baseDir.resolve("webapps");
        FS.ensureDirExists(webapps);

        Path demoDir = webapps.resolve(dirname);
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

        DeploymentScanner scanner = new DeploymentScanner(server);
        scanner.addMonitoredDirectory(webapps);
        DeploymentScanner.EnvironmentConfig coreConfig = scanner.configureEnvironment("core");
        coreConfig.setContextHandlerClass(CoreContextHandler.class.getName());

        try (StacklessLogging ignore = new StacklessLogging(DeploymentScanner.class, StandardDeployer.class))
        {
            Throwable throwable = assertThrows(Throwable.class, () -> startServer(scanner));

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
