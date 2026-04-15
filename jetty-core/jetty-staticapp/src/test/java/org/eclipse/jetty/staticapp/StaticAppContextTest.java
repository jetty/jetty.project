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

package org.eclipse.jetty.staticapp;

import java.io.FileWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

import org.eclipse.jetty.deploy.DeploymentScanner;
import org.eclipse.jetty.deploy.StandardDeployer;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@ExtendWith(WorkDirExtension.class)
public class StaticAppContextTest
{
    public WorkDir workDir;
    private Server server;
    private LocalConnector localConnector;

    @BeforeEach
    public void ensureStaticEnvironment()
    {
        Environment.ensure("static", StaticAppContext.class);
    }

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    @AfterEach
    public void removeAllEnvironments()
    {
        Environment.removeAll();
    }

    private void startServer(Handler handler) throws Exception
    {
        server = new Server();
        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        server.setHandler(handler);
        server.start();
    }

    private void startServerWithDeploy(Consumer<DeploymentScanner> deploymentScannerConsumer) throws Exception
    {
        server = new Server();
        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        ContextHandlerCollection contextHandlerCollection = new ContextHandlerCollection();
        server.setHandler(contextHandlerCollection);

        StandardDeployer deployer = new StandardDeployer(contextHandlerCollection);
        server.addBean(deployer);
        DeploymentScanner deploymentScanner = new DeploymentScanner(server, deployer);
        DeploymentScanner.EnvironmentConfig environmentConfig = deploymentScanner.configureEnvironment("static");
        environmentConfig.setDefaultContextHandlerClass(StaticAppContext.class);
        server.addBean(deploymentScanner);

        if (deploymentScannerConsumer != null)
            deploymentScannerConsumer.accept(deploymentScanner);

        server.start();
    }

    @Test
    public void testDeployDefaultEmptyDir() throws Exception
    {
        Path webapps = workDir.getEmptyPathDir().resolve("webapps");
        FS.ensureEmpty(webapps);

        Path staticDir = webapps.resolve("static");
        FS.ensureEmpty(staticDir);

        startServerWithDeploy(ds -> ds.addWebappsDirectory(webapps));

        String rawResponse = localConnector.getResponse("""
            GET /static/ HTTP/1.1
            Host: local
            Connection: close
            
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        // should see a directory listing (as directory listing is enabled by default)
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), allOf(
            containsString("Directory: /static/"),
            containsString("<table class=\"listing\">")
        ));
    }

    @Test
    public void testDeployStaticDir() throws Exception
    {
        Path webapps = workDir.getEmptyPathDir().resolve("webapps");
        FS.ensureEmpty(webapps);

        Path staticDir = webapps.resolve("static");
        FS.ensureEmpty(staticDir);
        Files.writeString(staticDir.resolve("hello.txt"), "Hello from TEXT");

        startServerWithDeploy(ds -> ds.addWebappsDirectory(webapps));

        String rawResponse = localConnector.getResponse("""
            GET /static/hello.txt HTTP/1.1
            Host: local
            Connection: close
            
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("Hello from TEXT"));
    }

    @Test
    public void testDeployStaticJar() throws Exception
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

        startServerWithDeploy(ds -> ds.addWebappsDirectory(webapps));

        String rawResponse = localConnector.getResponse("""
            GET /test/hello.txt HTTP/1.1
            Host: local
            Connection: close
            
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("Hello from TEXT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"other", "dnd"})
    public void testDeployDefaultToUnknownEnvironment(String unknownEnvName) throws Exception
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
            Assertions.assertThrows(IllegalStateException.class, () ->
                startServerWithDeploy(ds -> ds.addWebappsDirectory(webapps))
            );
        }
    }

    @Test
    public void testDeployDefaultWithContent() throws Exception
    {
        Path webapps = workDir.getEmptyPathDir().resolve("webapps");
        FS.ensureEmpty(webapps);

        Path staticDir = webapps.resolve("static");
        FS.ensureEmpty(staticDir);
        Files.writeString(staticDir.resolve("test.txt"), "TEST TEXT");

        startServerWithDeploy(ds -> ds.addWebappsDirectory(webapps));

        String rawResponse = localConnector.getResponse("""
            GET /static/test.txt HTTP/1.1
            Host: local
            Connection: close
            
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), is("TEST TEXT"));

        // Directory listing (by default it is enabled)
        rawResponse = localConnector.getResponse("""
            GET /static/ HTTP/1.1
            Host: local
            Connection: close
            
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), allOf(
            containsString("Directory: /static/"),
            containsString("<table class=\"listing\">")
        ));
    }

    @Test
    public void testDeployCustomResourceHandlerXML() throws Exception
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
            <Configure class="org.eclipse.jetty.staticapp.StaticAppContext">
              <Set name="contextPath">/static</Set>
              <Set name="baseResourceAsString"><Property name="jetty.webapps"/>/static</Set>
              <Get name="resourceHandler">
                <Set name="dirAllowed">false</Set>
              </Get>
            </Configure>
            """);

        startServerWithDeploy(ds -> ds.addWebappsDirectory(webapps));

        String rawResponse = localConnector.getResponse("""
            GET /static/test.txt HTTP/1.1
            Host: local
            Connection: close
            
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), is("TEST TEXT"));

        // Directory listing (turned off in this configuration)
        rawResponse = localConnector.getResponse("""
            GET /static/ HTTP/1.1
            Host: local
            Connection: close
            
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.FORBIDDEN_403));
        assertThat(response.getContent(), allOf(
            not(containsString("Directory: /static/")),
            not(containsString("<table class=\"listing\">"))
        ));
    }

    @Test
    public void testDeployCustomResourceHandlerPropertiesDirAllowedFalse() throws Exception
    {
        Path webapps = workDir.getEmptyPathDir().resolve("webapps");
        FS.ensureEmpty(webapps);

        Path staticDir = webapps.resolve("static");
        FS.ensureEmpty(staticDir);
        Files.writeString(webapps.resolve("static.properties"), """
            jetty.deploy.baseResource.dirAllowed=false
            """);
        Files.writeString(staticDir.resolve("test.txt"), "TEST TEXT");

        startServerWithDeploy(ds -> ds.addWebappsDirectory(webapps));

        String rawResponse = localConnector.getResponse("""
            GET /static/test.txt HTTP/1.1
            Host: local
            Connection: close
            
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), is("TEST TEXT"));

        // Directory listing (turned off in this configuration)
        rawResponse = localConnector.getResponse("""
            GET /static/ HTTP/1.1
            Host: local
            Connection: close
            
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.FORBIDDEN_403));
        assertThat(response.getContent(), allOf(
            not(containsString("Directory: /static/")),
            not(containsString("<table class=\"listing\">"))
        ));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Fails on Windows")
    public void testDeployCustomResourceHandlerPropertiesBaseResourceAlt() throws Exception
    {
        Path root = workDir.getEmptyPathDir();
        Path webapps = root.resolve("webapps");
        FS.ensureEmpty(webapps);

        // Intentionally not in webapps directory
        Path staticDir = root.resolve("static");
        FS.ensureEmpty(staticDir);
        try (Writer out = Files.newBufferedWriter(webapps.resolve("static.properties")))
        {
            Properties props = new Properties();
            props.put("environment", "static");
            props.put("jetty.deploy.baseResource", staticDir.toString());
            props.put("jetty.deploy.baseResource.dirAllowed", "false");
            props.store(out, "");
        }
        Files.writeString(staticDir.resolve("test.txt"), "TEST TEXT");

        startServerWithDeploy(ds -> ds.addWebappsDirectory(webapps));

        String rawResponse = localConnector.getResponse("""
            GET /static/test.txt HTTP/1.1
            Host: local
            Connection: close
            
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), is("TEST TEXT"));

        // Directory listing (turned off in this configuration)
        rawResponse = localConnector.getResponse("""
            GET /static/ HTTP/1.1
            Host: local
            Connection: close
            
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.FORBIDDEN_403));
        assertThat(response.getContent(), allOf(
            not(containsString("Directory: /static/")),
            not(containsString("<table class=\"listing\">"))
        ));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Fails on Windows")
    public void testDeployStaticWithDirectoryWithPropertyBaseResource() throws Exception
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
        try (Writer out = Files.newBufferedWriter(webapps.resolve("static.properties")))
        {
            Properties props = new Properties();
            props.put("environment", "static");
            props.put("jetty.deploy.baseResource", alt.toString());
            props.store(out, "");
        }

        startServerWithDeploy(ds -> ds.addWebappsDirectory(webapps));

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
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), is("Hello from alt"));
    }

    @Test
    public void testEmbeddedDefaultNoBase() throws Exception
    {
        StaticAppContext staticContext = new StaticAppContext();
        staticContext.setContextPath("/static");

        startServer(staticContext);

        String rawResponse = localConnector.getResponse("""
            GET /static/ HTTP/1.1
            Host: local
            Connection: close
            
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(404));
    }

    @Test
    public void testEmbeddedDefaultWithContent() throws Exception
    {
        Path basedir = workDir.getEmptyPathDir();

        Files.writeString(basedir.resolve("test.txt"), "TEST TEXT");

        StaticAppContext staticContext = new StaticAppContext();
        staticContext.setContextPath("/static");
        staticContext.setBaseResourceAsPath(basedir);

        startServer(staticContext);

        String rawResponse = localConnector.getResponse("""
            GET /static/test.txt HTTP/1.1
            Host: local
            Connection: close
            
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), is("TEST TEXT"));

        // Directory listing (by default it is enabled)
        rawResponse = localConnector.getResponse("""
            GET /static/ HTTP/1.1
            Host: local
            Connection: close
            
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), allOf(
            containsString("Directory: /static/"),
            containsString("<table class=\"listing\">")
        ));
    }

    @Test
    public void testEmbeddedDefaultCustomResourceHandler() throws Exception
    {
        Path basedir = workDir.getEmptyPathDir();

        Files.writeString(basedir.resolve("test.txt"), "TEST TEXT");

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            ResourceHandler resourceHandler = new ResourceHandler();
            Resource resource = resourceFactory.newResource(basedir);
            resourceHandler.setBaseResource(resource);
            resourceHandler.setDirAllowed(false);

            StaticAppContext staticContext = new StaticAppContext("/static", resourceHandler);
            startServer(staticContext);

            String rawResponse = localConnector.getResponse("""
                GET /static/test.txt HTTP/1.1
                Host: local
                Connection: close
                
                """);
            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), is("TEST TEXT"));

            // Directory listing (turned off in this configuration)
            rawResponse = localConnector.getResponse("""
                GET /static/ HTTP/1.1
                Host: local
                Connection: close
                
                """);
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.getStatus(), is(HttpStatus.FORBIDDEN_403));
            assertThat(response.getContent(), allOf(
                not(containsString("Directory: /static/")),
                not(containsString("<table class=\"listing\">"))
            ));
        }
    }
}
