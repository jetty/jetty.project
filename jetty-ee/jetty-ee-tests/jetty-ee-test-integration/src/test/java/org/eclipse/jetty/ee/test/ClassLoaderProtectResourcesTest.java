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

package org.eclipse.jetty.ee.test;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.ServletContainerInitializer;
import org.eclipse.jetty.ee.webapp.WebAppContext;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.example.webapp.ClassLoaderGetResourcesServlet;
import org.example.webapp.ServletContainerInitializerDiscoveryServlet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;

@ExtendWith(WorkDirExtension.class)
public class ClassLoaderProtectResourcesTest
{
    public WorkDir workDir;
    private Server server;
    private LocalConnector connector;

    public void startServer(WebAppContext webAppContext) throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        server.setHandler(webAppContext);
        server.start();
    }

    @AfterEach
    public void destroy()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testServiceLoaderVisibility() throws Exception
    {
        ClassLoader serverClassLoader = Thread.currentThread().getContextClassLoader();
        String resourceName = "META-INF/services/" + ServletContainerInitializer.class.getName();
        List<URL> allServiceFiles = Collections.list(serverClassLoader.getResources(resourceName));
        // Find the ee-apache-jsp URLs
        List<URI> eeApacheJspHits = allServiceFiles.stream()
            .map(ClassLoaderProtectResourcesTest::toJarURI)
            .filter(uri -> uri.toASCIIString().contains("ee-apache-jsp"))
            .toList();
        assertThat("Expecting some ee-apache-jsp SCI", eeApacheJspHits.size(), greaterThan(0));
        int expectedHitsFromServlet = allServiceFiles.size() - eeApacheJspHits.size();

        // Create webapp directory
        Path basePath = workDir.getEmptyPathDir();
        copyTestClassIntoWebapp(ServletContainerInitializerDiscoveryServlet.class, basePath);

        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        webapp.setBaseResourceAsPath(basePath);
        webapp.addServlet(ServletContainerInitializerDiscoveryServlet.class.getName(), "/lookup");

        // Protect a specific jar's SCI from being discovered.
        eeApacheJspHits.forEach(uri ->
            webapp.getHiddenClassMatcher().add(uri.toASCIIString()));

        startServer(webapp);

        String rawRequest = """
            GET /lookup HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """;
        String rawResponse = connector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getContent(), containsString("Service Count: %s%s".formatted(expectedHitsFromServlet, System.lineSeparator())));
    }

    private static URI toJarURI(URL url)
    {
        try
        {
            return URIUtil.unwrapContainer(url.toURI());
        }
        catch (URISyntaxException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testGetProtectedResources() throws Exception
    {
        // Create webapp directory
        Path basePath = workDir.getEmptyPathDir();
        copyTestClassIntoWebapp(ClassLoaderGetResourcesServlet.class, basePath);
        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        webapp.setBaseResourceAsPath(basePath);
        webapp.addServlet(ClassLoaderGetResourcesServlet.class.getName(), "/lookup");

        // The resource name we will be testing
        String resourceName = "META-INF/services/org.eclipse.jetty.http.HttpFieldPreEncoder";

        // Protect them from being discovered
        ClassLoader serverClassLoader = Thread.currentThread().getContextClassLoader();
        protectServerResource(serverClassLoader, resourceName, webapp);

        startServer(webapp);

        String rawRequest = """
            GET /lookup?resourceName=%s HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """.formatted(resourceName);
        String rawResponse = connector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getContent(), containsString("Hits: 0" + System.lineSeparator()));
    }

    private void protectServerResource(ClassLoader serverClassLoader, String resourceName, WebAppContext webapp) throws IOException, URISyntaxException
    {
        // Find resources that belong only on server side.
        List<URL> urls = Collections.list(serverClassLoader.getResources(resourceName));
        assert !urls.isEmpty();

        // Lets setup exclusions, by location ("file:///" urls), for these.
        for (URL url: urls)
        {
            URI uri = URIUtil.unwrapContainer(url.toURI());
            // This is the key configuration to allow protecting of server resources
            // even when using ClassLoader.getResource() or ClassLoader.getResources()
            webapp.getHiddenClassMatcher().add(uri.toASCIIString());
        }
    }

    private static void copyTestClassIntoWebapp(Class<?> clazz, Path webappRoot) throws IOException
    {
        String pathToCopy = TypeUtil.toClassReference(clazz);
        Path classFile = MavenPaths.targetDir().resolve("test-classes/" + pathToCopy);
        Assertions.assertTrue(Files.isRegularFile(classFile), "Class should exist file: " + classFile);

        Path classesDir = webappRoot.resolve("WEB-INF/classes");
        FS.ensureDirExists(classesDir);

        Path destFile = classesDir.resolve(pathToCopy);
        FS.ensureDirExists(destFile.getParent());
        Files.copy(classFile, destFile);
    }
}
