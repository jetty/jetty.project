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

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.StaticContextHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@ExtendWith(WorkDirExtension.class)
public class StaticContextHandlerTest
{
    public WorkDir workDir;
    private Server server;
    private LocalConnector localConnector;

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    private void startServer(Consumer<DeploymentScanner> deploymentScannerConsumer) throws Exception
    {
        server = new Server();
        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        ContextHandlerCollection contextHandlerCollection = new ContextHandlerCollection();
        server.setHandler(contextHandlerCollection);

        StandardDeployer deployer = new StandardDeployer(contextHandlerCollection);
        server.addBean(deployer);
        DeploymentScanner deploymentScanner = new DeploymentScanner(server, deployer);
        Environment.ensure("static", StaticContextHandler.class);
        DeploymentScanner.EnvironmentConfig environmentConfig = deploymentScanner.configureEnvironment("static");
        environmentConfig.setContextHandlerClass(StaticContextHandler.class.getName());
        server.addBean(deploymentScanner);

        if (deploymentScannerConsumer != null)
            deploymentScannerConsumer.accept(deploymentScanner);

        server.start();
    }

    @Test
    public void testDefaultEmptyDir() throws Exception
    {
        Path webapps = workDir.getEmptyPathDir().resolve("webapps");
        FS.ensureEmpty(webapps);

        Path staticDir = webapps.resolve("static");
        FS.ensureEmpty(staticDir);

        startServer(ds -> ds.addMonitoredDirectory(webapps));

        String rawResponse = localConnector.getResponse("""
            GET /static/ HTTP/1.1
            Host: local
            Connection: close
            
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        // should see a directory listing (as directory listing is enabled by default)
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), allOf(
            containsString("Directory: /static/"),
            containsString("<table class=\"listing\">")
        ));
    }

    @Test
    public void testStaticDir() throws Exception
    {
        Path webapps = workDir.getEmptyPathDir().resolve("webapps");
        FS.ensureEmpty(webapps);

        Path staticDir = webapps.resolve("static");
        FS.ensureEmpty(staticDir);
        Files.writeString(staticDir.resolve("hello.txt"), "Hello from TEXT");

        startServer(ds -> ds.addMonitoredDirectory(webapps));

        String rawResponse = localConnector.getResponse("""
            GET /static/hello.txt HTTP/1.1
            Host: local
            Connection: close
            
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), containsString("Hello from TEXT"));
    }

    @Test
    public void testStaticJar() throws Exception
    {
        Path webapps = workDir.getEmptyPathDir().resolve("webapps");
        FS.ensureEmpty(webapps);

        Path outputJar = webapps.resolve("test.jar");
        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        String testFileContent = "Hello from TEXT";
        URI uri = URI.create("jar:" + outputJar.toUri().toASCIIString());
        // Use ZipFS so that we can create paths that are just "/"
        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            Path root = zipfs.getPath("/");
            Files.writeString(root.resolve("hello.txt"), testFileContent, StandardOpenOption.CREATE);
        }

        startServer(ds -> ds.addMonitoredDirectory(webapps));

        String rawResponse = localConnector.getResponse("""
            GET /test/hello.txt HTTP/1.1
            Host: local
            Connection: close
            
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), containsString("Hello from TEXT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"other", "dnd"})
    public void testDefaultToUnknownEnvironment(String unknownEnvName) throws Exception
    {
        Path webapps = workDir.getEmptyPathDir().resolve("webapps");
        FS.ensureEmpty(webapps);

        Path staticDir = webapps.resolve("static");
        FS.ensureEmpty(staticDir);
        Files.writeString(staticDir.resolve("test.txt"), "TEST TEXT");
        Files.writeString(webapps.resolve("static.properties"), """
            environment=%s
            """.formatted(unknownEnvName));

        try (StacklessLogging ignored = new StacklessLogging(DeploymentScanner.class))
        {
            Assertions.assertThrows(IllegalArgumentException.class, () ->
                startServer(ds -> ds.addMonitoredDirectory(webapps))
            );
        }
    }

    @Test
    public void testDefaultWithContent() throws Exception
    {
        Path webapps = workDir.getEmptyPathDir().resolve("webapps");
        FS.ensureEmpty(webapps);

        Path staticDir = webapps.resolve("static");
        FS.ensureEmpty(staticDir);
        Files.writeString(staticDir.resolve("test.txt"), "TEST TEXT");

        startServer(ds -> ds.addMonitoredDirectory(webapps));

        String rawResponse = localConnector.getResponse("""
            GET /static/test.txt HTTP/1.1
            Host: local
            Connection: close
            
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is("TEST TEXT"));

        // Directory listing (by default it is enabled)
        rawResponse = localConnector.getResponse("""
            GET /static/ HTTP/1.1
            Host: local
            Connection: close
            
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), allOf(
            containsString("Directory: /static/"),
            containsString("<table class=\"listing\">")
        ));
    }

    @Test
    public void testCustomResourceHandlerXML() throws Exception
    {
        Path webapps = workDir.getEmptyPathDir().resolve("webapps");
        FS.ensureEmpty(webapps);

        Path staticDir = webapps.resolve("static");
        FS.ensureEmpty(staticDir);
        Files.writeString(webapps.resolve("static.properties"), "environment=static");
        Files.writeString(staticDir.resolve("test.txt"), "TEST TEXT");

        Files.writeString(webapps.resolve("static.xml"), """
            <?xml version="1.0"?>
            <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure.dtd">
            <Configure class="org.eclipse.jetty.server.handler.StaticContextHandler">
              <Set name="contextPath">/static</Set>
              <Set name="baseResourceAsString"><Property name="jetty.webapps"/>/static</Set>
              <Get name="resourceHandler">
                <Set name="dirAllowed">false</Set>
              </Get>
            </Configure>
            """);

        startServer(ds -> ds.addMonitoredDirectory(webapps));

        String rawResponse = localConnector.getResponse("""
            GET /static/test.txt HTTP/1.1
            Host: local
            Connection: close
            
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is("TEST TEXT"));

        // Directory listing (turned off in this configuration)
        rawResponse = localConnector.getResponse("""
            GET /static/ HTTP/1.1
            Host: local
            Connection: close
            
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(403));
        assertThat(response.getContent(), allOf(
            not(containsString("Directory: /static/")),
            not(containsString("<table class=\"listing\">"))
        ));
    }

    @Test
    public void testCustomResourceHandlerPropertiesDirAllowedFalse() throws Exception
    {
        Path webapps = workDir.getEmptyPathDir().resolve("webapps");
        FS.ensureEmpty(webapps);

        Path staticDir = webapps.resolve("static");
        FS.ensureEmpty(staticDir);
        Files.writeString(webapps.resolve("static.properties"), """
            jetty.deploy.baseResource.dirAllowed=false
            """);
        Files.writeString(staticDir.resolve("test.txt"), "TEST TEXT");

        startServer(ds -> ds.addMonitoredDirectory(webapps));

        String rawResponse = localConnector.getResponse("""
            GET /static/test.txt HTTP/1.1
            Host: local
            Connection: close
            
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is("TEST TEXT"));

        // Directory listing (turned off in this configuration)
        rawResponse = localConnector.getResponse("""
            GET /static/ HTTP/1.1
            Host: local
            Connection: close
            
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(403));
        assertThat(response.getContent(), allOf(
            not(containsString("Directory: /static/")),
            not(containsString("<table class=\"listing\">"))
        ));
    }

    @Test
    public void testCustomResourceHandlerPropertiesBaseResourceAlt() throws Exception
    {
        Path root = workDir.getEmptyPathDir();
        Path webapps = root.resolve("webapps");
        FS.ensureEmpty(webapps);

        // Intentionally not in webapps directory
        Path staticDir = root.resolve("static");
        FS.ensureEmpty(staticDir);
        Files.writeString(webapps.resolve("static.properties"), """
            environment=static
            jetty.deploy.baseResource=%s
            jetty.deploy.baseResource.dirAllowed=false
            """.formatted(staticDir.toString()));
        Files.writeString(staticDir.resolve("test.txt"), "TEST TEXT");

        startServer(ds -> ds.addMonitoredDirectory(webapps));

        String rawResponse = localConnector.getResponse("""
            GET /static/test.txt HTTP/1.1
            Host: local
            Connection: close
            
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is("TEST TEXT"));

        // Directory listing (turned off in this configuration)
        rawResponse = localConnector.getResponse("""
            GET /static/ HTTP/1.1
            Host: local
            Connection: close
            
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(403));
        assertThat(response.getContent(), allOf(
            not(containsString("Directory: /static/")),
            not(containsString("<table class=\"listing\">"))
        ));
    }

    @Test
    public void testStaticWithDirectoryWithPropertyBaseResource() throws Exception
    {
        Path root = workDir.getEmptyPathDir();

        Path alt = root.resolve("alt");
        FS.ensureEmpty(alt);
        Files.writeString(alt.resolve("hello.txt"), "Hello from alt");

        Path webapps = root.resolve("webapps");
        FS.ensureEmpty(webapps);

        Path staticDir = webapps.resolve("static");
        FS.ensureEmpty(staticDir);
        Files.writeString(staticDir.resolve("test.txt"), "TEST TEXT");
        Files.writeString(webapps.resolve("static.properties"), """
            environment=static
            jetty.deploy.baseResource=%s
            """.formatted(alt.toString()));

        startServer(ds -> ds.addMonitoredDirectory(webapps));

        String rawResponse = localConnector.getResponse("""
            GET /static/test.txt HTTP/1.1
            Host: local
            Connection: close
            
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(404));

        rawResponse = localConnector.getResponse("""
            GET /static/hello.txt HTTP/1.1
            Host: local
            Connection: close
            
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is("Hello from alt"));
    }
}
