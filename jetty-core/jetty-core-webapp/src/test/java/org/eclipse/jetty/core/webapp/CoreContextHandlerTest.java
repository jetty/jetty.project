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

package org.eclipse.jetty.core.webapp;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import org.eclipse.jetty.deploy.DeploymentScanner;
import org.eclipse.jetty.deploy.StandardDeployer;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class CoreContextHandlerTest extends AbstractCleanEnvironmentTest
{
    private static final String EXPECTED_MESSAGE_FROM_TEST_WEBAPP = "On the other side of the screen, it all looks so easy.";
    public WorkDir workDir;
    private Server server;
    private LocalConnector localConnector;

    @BeforeEach
    public void ensureCoreEnvironment()
    {
        Environment.ensure("core", CoreContextHandler.class);
    }

    private void startServer(Consumer<Server> serverConsumer) throws Exception
    {
        server = new Server();
        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        if (serverConsumer != null)
            serverConsumer.accept(server);

        server.start();
    }

    private void startServerWithDeploy(Path webappsDir, Path environmentsDir) throws Exception
    {
        Objects.requireNonNull(webappsDir);

        startServer((server) ->
        {
            ContextHandlerCollection contextHandlerCollection = new ContextHandlerCollection();
            server.setHandler(contextHandlerCollection);

            StandardDeployer deployer = new StandardDeployer(contextHandlerCollection);
            server.addBean(deployer);

            DeploymentScanner deploymentScanner = new DeploymentScanner(server);
            deploymentScanner.addWebappsDirectory(webappsDir);

            if (environmentsDir != null)
            {
                assertTrue(Files.isDirectory(environmentsDir), "Environments Dir invalid: " + environmentsDir);
                deploymentScanner.setEnvironmentsDirectory(environmentsDir);
            }

            server.addBean(deploymentScanner);

            DeploymentScanner.EnvironmentConfig coreConfig = deploymentScanner.configureEnvironment("core");
            coreConfig.setClassLoaderFactoryClassName(CoreContextHandler.CoreContextClassLoaderFactory.class.getName());
        });
    }

    @AfterEach
    public void stopAll()
    {
        LifeCycle.stop(server);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "demo.d", // nominated dir (backward compat)
        "demo" // normal dir
    })
    public void testDeployCoreDir(String dirname) throws Exception
    {
        Path baseDir = workDir.getEmptyPathDir();

        Path webapps = baseDir.resolve("webapps");
        FS.ensureDirExists(webapps);

        Path demoDir = webapps.resolve(dirname);
        FS.ensureDirExists(demoDir);

        Path srcZip = MavenPaths.targetDir().resolve("core-webapps/jetty-core-demo-webapp.zip");
        Assertions.assertTrue(Files.exists(srcZip), "Src Zip should exist: " + srcZip);
        unpack(srcZip, demoDir);

        // ensure that demo jar isn't in our test/server classpath.
        // it should only exist in the jar file on disk.
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.example.ExampleHandler"));

        Path demoXml = webapps.resolve("demo.xml");
        String demoXmlStr = """
            <?xml version="1.0"?>
            <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure.dtd">
            <Configure class="org.eclipse.jetty.core.webapp.CoreContextHandler">
              <Set name="contextPath">/demo</Set>
              <Set name="handler">
                <New class="org.example.ExampleHandler" />
              </Set>
            </Configure>
            """;
        Files.writeString(demoXml, demoXmlStr);

        startServerWithDeploy(webapps, null);

        String rawRequest = """
            GET /demo/ HTTP/1.1
            Host: local
            Connection: close
            
            """;

        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(200));
        String responseBody = response.getContent();
        assertThat(responseBody, containsString(Server.getVersion()));
        assertThat(responseBody, containsString("/demo/"));
        assertThat(responseBody, containsString("messages.size=1"));
        assertThat(responseBody, containsString(EXPECTED_MESSAGE_FROM_TEST_WEBAPP));
    }

    @Test
    public void testDeployArbitraryHandler() throws Exception
    {
        Path baseDir = workDir.getEmptyPathDir();

        Path webapps = baseDir.resolve("webapps");
        FS.ensureDirExists(webapps);

        Path demoXml = webapps.resolve("moved.xml");
        String demoXmlStr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://www.eclipse.org/jetty/configure_10_0.dtd">
            
            <!-- Simple handler to redirect from old path to new -->
            <Configure class="org.eclipse.jetty.server.handler.MovedContextHandler">
              <Set name="contextPath">/documentation</Set>
              <Set name="redirectURI">https://jetty.org/docs/</Set>
              <Set name="statusCode">302</Set>
            </Configure>
            """;
        Files.writeString(demoXml, demoXmlStr);

        startServerWithDeploy(webapps, null);

        String rawRequest = """
            GET /documentation/ HTTP/1.1
            Host: local
            Connection: close
            
            """;

        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(302));
        assertThat(response.get("Location"), is("https://jetty.org/docs/"));
    }

    @Test
    public void testDeployBaseResourceInXmlPointingOutsideWebappsDir() throws Exception
    {
        Path root = workDir.getEmptyPathDir();

        Path demobase = root.resolve("demobase");
        FS.ensureDirExists(demobase);

        Files.writeString(demobase.resolve("index.html"),
            """
                demobase index
                """);

        Path webapps = root.resolve("webapps");
        FS.ensureDirExists(webapps);

        Path demoXml = webapps.resolve("demo.xml");
        String demoXmlStr = """
            <?xml version="1.0"?>
            <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure.dtd">
            <Configure class="org.eclipse.jetty.core.webapp.CoreContextHandler">
              <Set name="contextPath">/demo</Set>
              <Set name="baseResourceAsPath">
                <Call class="java.nio.file.Path" name="of">
                  <Arg>%s</Arg>
                </Call>
              </Set>
              <Set name="handler">
                <New class="org.eclipse.jetty.server.handler.ResourceHandler" />
              </Set>
            </Configure>
            """.formatted(demobase.toString());
        Files.writeString(demoXml, demoXmlStr);

        startServerWithDeploy(webapps, null);

        String rawRequest = """
            GET /demo/ HTTP/1.1
            Host: local
            Connection: close
            
            """;

        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        String responseBody = response.getContent();
        assertThat(responseBody, containsString("demobase index"));
    }

    @Test
    public void testDeployCoreDirWithEnvironmentsProps() throws Exception
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
            <Configure class="org.eclipse.jetty.core.webapp.CoreContextHandler">
              <Set name="displayName"><Property name="custom.displayPrefix" default=""/> Demo</Set>
              <Set name="contextPath">/demo</Set>
            </Configure>
            """;
        Files.writeString(demoXml, demoXmlStr);

        startServerWithDeploy(webapps, environments);

        String rawRequest = """
            GET /demo/ HTTP/1.1
            Host: local
            Connection: close
            
            """;

        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        String responseBody = response.getContent();
        assertThat(responseBody, containsString("This is the static index.html"));

        ContextHandlerCollection contextHandlerCollection = (ContextHandlerCollection)server.getHandler();
        CoreContextHandler coreContextHandler = contextHandlerCollection.getBean(CoreContextHandler.class);
        assertNotNull(coreContextHandler);
        assertEquals("Customized Demo", coreContextHandler.getDisplayName());
    }

    @Test
    public void testDeployCoreDirWithEnvironmentsXml() throws Exception
    {
        Path root = workDir.getEmptyPathDir();

        Path environments = root.resolve("environments");
        FS.ensureDirExists(environments);
        Files.writeString(environments.resolve("core.xml"), """
            <?xml version="1.0"?>
            <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure.dtd">
            <Configure class="org.eclipse.jetty.core.webapp.CoreContextHandler">
              <Call name="addVirtualHosts">
                <Arg>
                  <Array type="string">
                    <Item>local</Item>
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
            <Configure class="org.eclipse.jetty.core.webapp.CoreContextHandler">
              <Set name="contextPath">/demo</Set>
            </Configure>
            """;
        Files.writeString(demoXml, demoXmlStr);

        startServerWithDeploy(webapps, environments);

        String rawRequest = """
            GET /demo/ HTTP/1.1
            Host: local
            Connection: close
            
            """;

        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        String responseBody = response.getContent();
        assertThat(responseBody, containsString("This is the static index.html"));

        ContextHandlerCollection contextHandlerCollection = (ContextHandlerCollection)server.getHandler();
        CoreContextHandler coreContextHandler = contextHandlerCollection.getBean(CoreContextHandler.class);
        assertNotNull(coreContextHandler);
        List<String> virtualHosts = coreContextHandler.getVirtualHosts();
        assertNotNull(virtualHosts);
        assertThat(virtualHosts.size(), is(1));
        assertThat(virtualHosts, contains("local"));
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
    public void testDeployFailureXml(String dirname) throws Exception
    {
        Path baseDir = workDir.getEmptyPathDir();

        Path webapps = baseDir.resolve("webapps");
        FS.ensureDirExists(webapps);

        Path demoDir = webapps.resolve(dirname);
        FS.ensureDirExists(demoDir);

        Path srcZip = MavenPaths.targetDir().resolve("core-webapps/jetty-core-demo-webapp.zip");
        Assertions.assertTrue(Files.exists(srcZip), "Src Zip should exist: " + srcZip);
        unpack(srcZip, demoDir);

        // ensure that demo jar isn't in our test/server classpath.
        // it should only exist in the jar file on disk.
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.example.ExampleHandler"));

        Path demoXml = webapps.resolve("demo.xml");
        String demoXmlStr = """
            <?xml version="1.0"?>
            <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure.dtd">
            <Configure class="org.eclipse.jetty.core.webapp.CoreContextHandler">
              <Set name="contextPath">/demo</Set>
              <Set name="handler">
                <New class="org.example.BogusHandler" /> <!-- THIS DOESN'T EXIST -->
              </Set>
            </Configure>
            """;
        Files.writeString(demoXml, demoXmlStr);

        try (StacklessLogging ignore = new StacklessLogging(DeploymentScanner.class))
        {
            Throwable throwable = assertThrows(Throwable.class, () -> startServerWithDeploy(webapps, null));

            // unwrap any ExecutionExceptions
            while (throwable.getCause() != null)
            {
                throwable = throwable.getCause();
            }

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
    public void testDeployFailureDeploy(String dirname) throws IOException
    {
        Path baseDir = workDir.getEmptyPathDir();

        Path webapps = baseDir.resolve("webapps");
        FS.ensureDirExists(webapps);

        Path demoDir = webapps.resolve(dirname);
        FS.ensureDirExists(demoDir);

        Path srcZip = MavenPaths.targetDir().resolve("core-webapps/jetty-core-bad-init-webapp.zip");
        Assertions.assertTrue(Files.exists(srcZip), "Src Zip should exist: " + srcZip);
        unpack(srcZip, demoDir);

        // ensure that demo jar isn't in our test/server classpath.
        // it should only exist in the jar file on disk.
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.example.ExampleHandler"));

        Path demoXml = webapps.resolve("demo.xml");
        String demoXmlStr = """
            <?xml version="1.0"?>
            <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure.dtd">
            <Configure class="org.eclipse.jetty.core.webapp.CoreContextHandler">
              <Set name="contextPath">/demo</Set>
              <Set name="handler">
                <New class="org.example.ExampleBadSetServerHandler" />
              </Set>
            </Configure>
            """;
        Files.writeString(demoXml, demoXmlStr);

        try (StacklessLogging ignore = new StacklessLogging(
            // screwy name courtesy of SerializedInvoker.onError() logic
            "org.eclipse.jetty.server.handler.ContextHandlerCollection$1",
            StandardDeployer.class.getName(),
            DeploymentScanner.class.getName(),
            Scanner.class.getName()))
        {
            Throwable throwable = assertThrows(Throwable.class, () -> startServerWithDeploy(webapps, null));

            // unwrap any ExecutionExceptions
            while (throwable.getCause() != null)
            {
                throwable = throwable.getCause();
            }

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
    public void testDeployFailureDoStart(String dirname) throws IOException
    {
        Path baseDir = workDir.getEmptyPathDir();

        Path webapps = baseDir.resolve("webapps");
        FS.ensureDirExists(webapps);

        Path demoDir = webapps.resolve(dirname);
        FS.ensureDirExists(demoDir);

        Path srcZip = MavenPaths.targetDir().resolve("core-webapps/jetty-core-bad-init-webapp.zip");
        Assertions.assertTrue(Files.exists(srcZip), "Src Zip should exist: " + srcZip);
        unpack(srcZip, demoDir);

        // ensure that demo jar isn't in our test/server classpath.
        // it should only exist in the jar file on disk.
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.example.ExampleHandler"));

        Path demoXml = webapps.resolve("demo.xml");
        String demoXmlStr = """
            <?xml version="1.0"?>
            <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure.dtd">
            <Configure class="org.eclipse.jetty.core.webapp.CoreContextHandler">
              <Set name="contextPath">/demo</Set>
              <Set name="handler">
                <New class="org.example.ExampleBadStartHandler" />
              </Set>
            </Configure>
            """;
        Files.writeString(demoXml, demoXmlStr);

        try (StacklessLogging ignore = new StacklessLogging(DeploymentScanner.class, StandardDeployer.class))
        {
            Throwable throwable = assertThrows(Throwable.class, () -> startServerWithDeploy(webapps, null));

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

    @Test
    public void testDeployExtraClassPath() throws Exception
    {
        Path baseDir = workDir.getEmptyPathDir();

        Path webapps = baseDir.resolve("webapps");
        FS.ensureDirExists(webapps);

        Path lib = baseDir.resolve("extra-lib"); // this is the $JETTY_BASE/extra-lib
        FS.ensureDirExists(lib);
        Path extraJar = lib.resolve("extra.jar");
        Map<String, String> env = new HashMap<>();
        env.put("create", "true");
        String testFileContent = "Hello from TEXT";
        URI uri = URI.create("jar:" + extraJar.toUri().toASCIIString());
        // Use ZipFS so that we can create paths that are just "/"
        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            Path root = zipfs.getPath("/");
            Path dir = root.resolve("org/example");
            Files.createDirectories(dir);
            Properties props = new Properties();
            props.setProperty("message", testFileContent);
            saveProperties(props, dir.resolve("example.properties"));
        }

        Path demoDir = webapps.resolve("demo");
        FS.ensureDirExists(demoDir);

        Path srcZip = MavenPaths.targetDir().resolve("core-webapps/jetty-core-demo-webapp.zip");
        Assertions.assertTrue(Files.exists(srcZip), "Src Zip should exist: " + srcZip);
        unpack(srcZip, demoDir);

        // ensure that demo jar isn't in our test/server classpath.
        // it should only exist in the jar file on disk.
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.example.ExampleHandler"));

        Path demoXml = webapps.resolve("demo.xml");
        String demoXmlStr = """
            <?xml version="1.0"?>
            <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure.dtd">
            <Configure class="org.eclipse.jetty.core.webapp.CoreContextHandler">
              <Set name="contextPath">/demo</Set>
              <Set name="handler">
                <New class="org.example.ExampleHandler" />
              </Set>
            </Configure>
            """;
        Files.writeString(demoXml, demoXmlStr);

        Path demoProperties = webapps.resolve("demo.properties");
        Properties props = new Properties();
        props.setProperty("jetty.deploy.core.extraClassPath", extraJar.toString());
        saveProperties(props, demoProperties);

        startServerWithDeploy(webapps, null);

        String rawRequest = """
            GET /demo/ HTTP/1.1
            Host: local
            Connection: close
            
            """;

        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        String responseBody = response.getContent();
        assertThat(responseBody, containsString(Server.getVersion()));
        assertThat(responseBody, containsString("/demo/"));
        assertThat(responseBody, containsString("messages.size=2"));
        assertThat(responseBody, containsString("]=" + EXPECTED_MESSAGE_FROM_TEST_WEBAPP));
        assertThat(responseBody, containsString("]=" + testFileContent));
    }

    @Test
    public void testEmbeddedExtraClassPath() throws Exception
    {
        Path baseDir = workDir.getEmptyPathDir();

        Path webapps = baseDir.resolve("webapps");
        FS.ensureDirExists(webapps);

        Path lib = baseDir.resolve("extra-lib"); // this is the $JETTY_BASE/extra-lib
        FS.ensureDirExists(lib);
        Path extraJar = lib.resolve("extra.jar");
        Map<String, String> env = new HashMap<>();
        env.put("create", "true");
        String testFileContent = "Hello from TEXT";
        URI uri = URI.create("jar:" + extraJar.toUri().toASCIIString());
        // Use ZipFS so that we can create paths that are just "/"
        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            Path root = zipfs.getPath("/");
            Path dir = root.resolve("org/example");
            Files.createDirectories(dir);
            Properties props = new Properties();
            props.setProperty("message", testFileContent);
            saveProperties(props, dir.resolve("example.properties"));
        }

        Path srcZip = MavenPaths.targetDir().resolve("core-webapps/jetty-core-demo-webapp.zip");
        Assertions.assertTrue(Files.exists(srcZip), "Src Zip should exist: " + srcZip);
        Path webappZip = Files.copy(srcZip, webapps.resolve(srcZip.getFileName()));

        // ensure that demo jar isn't in our test/server classpath.
        // it should only exist in the jar file on disk.
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.example.ExampleHandler"));

        URL[] extraClassPath = new URL[]{
            extraJar.toUri().toURL()
        };
        //noinspection resource
        URLClassLoader classLoader = new URLClassLoader(extraClassPath);

        startServer((server) ->
        {
            ContextHandlerCollection contextHandlerCollection = new ContextHandlerCollection();
            server.setHandler(contextHandlerCollection);

            CoreContextHandler contextHandler = new CoreContextHandler();
            contextHandler.setClassLoader(classLoader);
            ResourceFactory resourceFactory = contextHandler.getResourceFactory();
            Resource baseResource = resourceFactory.newResource(webappZip);
            contextHandler.setBaseResource(baseResource);

            contextHandlerCollection.addHandler(contextHandler);
        });

        String rawRequest = """
            GET /demo/ HTTP/1.1
            Host: local
            Connection: close
            
            """;

        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        String responseBody = response.getContent();
        assertThat(responseBody, containsString(Server.getVersion()));
        assertThat(responseBody, containsString("/demo/"));
        assertThat(responseBody, containsString("messages.size=2"));
        assertThat(responseBody, containsString("]=" + EXPECTED_MESSAGE_FROM_TEST_WEBAPP));
        assertThat(responseBody, containsString("]=" + testFileContent));
    }

    private void saveProperties(Properties props, Path file) throws IOException
    {
        try (OutputStream out = Files.newOutputStream(file))
        {
            props.store(out, "From " + CoreContextHandlerTest.class.getName());
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
