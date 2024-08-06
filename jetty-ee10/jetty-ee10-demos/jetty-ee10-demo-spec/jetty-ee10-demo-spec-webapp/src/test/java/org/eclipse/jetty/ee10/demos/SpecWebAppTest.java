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

package org.eclipse.jetty.ee10.demos;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.example.MockDataSource;
import org.example.MockUserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(WorkDirExtension.class)
public class SpecWebAppTest
{
    private Server server;
    private HttpClient client;

    @BeforeEach
    public void setup(WorkDir workDir) throws Exception
    {
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        Path webappDir = prepareWebAppDir(workDir);

        WebAppContext webapp = new WebAppContext();
        ResourceFactory resourceFactory = ResourceFactory.of(webapp);
        webapp.setContextPath("/");
        webapp.setWarResource(resourceFactory.newResource(webappDir));
        webapp.setAttribute(
            "org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern",
            ".*/jakarta.servlet-api-[^/]*\\.jar$|.*/[^/]*taglibs.*\\.jar$");

        HashLoginService hashLoginService = new HashLoginService();
        hashLoginService.setName("Test Realm");
        Path realmFile = MavenPaths.findTestResourceFile("ee10-demo-realm.properties");
        Resource realmResource = ResourceFactory.of(server).newResource(realmFile);
        hashLoginService.setConfig(realmResource);
        SecurityHandler securityHandler = webapp.getSecurityHandler();
        securityHandler.setLoginService(hashLoginService);

        new org.eclipse.jetty.plus.jndi.Resource(webapp, "jdbc/mydatasource", new MockDataSource());
        new org.eclipse.jetty.ee10.plus.jndi.Transaction("ee10", new MockUserTransaction());

        server.setHandler(webapp);
        server.start();

        client = new HttpClient();
        client.start();
    }

    private Path prepareWebAppDir(WorkDir workDir) throws IOException
    {
        Path webappDir = workDir.getEmptyPathDir();
        Path srcWebapp = MavenPaths.projectBase().resolve("src/main/webapp");
        IO.copyDir(srcWebapp, webappDir);

        Path webappClassesDir = webappDir.resolve("WEB-INF/classes");
        FS.ensureDirExists(webappClassesDir);
        Path classesDir = MavenPaths.projectBase().resolve("target/classes");
        IO.copyDir(classesDir, webappClassesDir);

        Path libDir = webappDir.resolve("WEB-INF/lib");
        FS.ensureDirExists(libDir);
        copyDependency("jetty-ee10-demo-container-initializer", libDir);
        copyDependency("jetty-ee10-demo-web-fragment", libDir);

        return webappDir;
    }

    private void copyDependency(String depName, Path libDir) throws IOException
    {
        // sinply use copy:dependency from maven...
        Path targetDir = MavenPaths.projectBase().resolve("target");
        Path jarFile = targetDir.resolve(depName + ".jar");
        if (Files.exists(jarFile))
        {
            Files.copy(jarFile, libDir.resolve(depName + ".jar"));
            return;
        }

        Path depPath = MavenPaths.projectBase().resolve("../" + depName).normalize();
        if (!Files.isDirectory(depPath))
            fail("Dependency not found: " + depPath);
        Path outputJar = libDir.resolve(depName + ".jar");

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");
        URI uri = URI.create("jar:" + outputJar.toUri().toASCIIString());
        try (FileSystem fs = FileSystems.newFileSystem(uri, env))
        {
            Path root = fs.getPath("/");
            IO.copyDir(depPath.resolve("target/classes"), root);
            IO.copyDir(depPath.resolve("src/main/resources"), root, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @AfterEach
    public void teardown()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @Test
    public void testNoFailures() throws InterruptedException, ExecutionException, TimeoutException
    {
        ContentResponse response = client.newRequest(server.getURI().resolve("/test/"))
            .followRedirects(false)
            .send();

        assertThat("response status", response.getStatus(), is(HttpStatus.OK_200));
        // Look for 0 entries that fail.
        assertThat("response", response.getContentAsString(), not(containsString(">FAIL<")));
    }
}
