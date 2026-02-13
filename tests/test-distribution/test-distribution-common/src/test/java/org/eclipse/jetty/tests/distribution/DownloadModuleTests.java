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

package org.eclipse.jetty.tests.distribution;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.tests.testers.Tester;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for downloading modules from a URL via {@code --add-modules=<url>}.
 *
 * @see <a href="https://github.com/jetty/jetty.project/issues/175">Issue #175</a>
 */
public class DownloadModuleTests extends AbstractJettyHomeTest
{
    /**
     * Creates a config JAR containing a simple module definition.
     * The JAR structure mirrors what would be extracted into {@code ${jetty.base}}:
     * <pre>
     *   modules/test-download.mod
     * </pre>
     */
    private Path createConfigJar(Path directory, String moduleName) throws Exception
    {
        Files.createDirectories(directory);
        Path jarFile = directory.resolve(moduleName + "-config.jar");

        String modContent = """
            [description]
            Test module installed from a remote URL.

            [depend]
            server

            [ini-template]
            ## Test property
            # test.download.property=value
            """;

        try (OutputStream fos = Files.newOutputStream(jarFile);
             JarOutputStream jos = new JarOutputStream(fos))
        {
            JarEntry modEntry = new JarEntry("modules/" + moduleName + ".mod");
            jos.putNextEntry(modEntry);
            jos.write(modContent.getBytes());
            jos.closeEntry();
        }

        return jarFile;
    }

    /**
     * Starts an embedded Jetty server that serves the given content at the specified path.
     *
     * @param contextPath the URL path to serve the content at
     * @param content the byte content to serve
     * @return the started Server (caller must stop it)
     */
    private Server startFileServer(String contextPath, byte[] content) throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                if (!Request.getPathInContext(request).equals(contextPath))
                {
                    Response.writeError(request, response, callback, HttpStatus.NOT_FOUND_404);
                    return true;
                }
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/java-archive");
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, content.length);
                response.write(true, ByteBuffer.wrap(content), callback);
                return true;
            }
        });

        server.start();
        return server;
    }

    /**
     * Starts an embedded Jetty server that requires the specified Authorization header.
     * Returns 401 (for Basic) or 403 (for other schemes) if the header is missing or wrong.
     *
     * @param contextPath the URL path to serve the content at
     * @param content the byte content to serve
     * @param expectedAuthHeader the expected Authorization header value
     * @param failStatus the HTTP status to return on auth failure (e.g. 401 or 403)
     * @return the started Server (caller must stop it)
     */
    private Server startAuthFileServer(String contextPath, byte[] content,
                                       String expectedAuthHeader, int failStatus) throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                if (!Request.getPathInContext(request).equals(contextPath))
                {
                    Response.writeError(request, response, callback, HttpStatus.NOT_FOUND_404);
                    return true;
                }

                String authHeader = request.getHeaders().get(HttpHeader.AUTHORIZATION);
                if (!expectedAuthHeader.equals(authHeader))
                {
                    if (failStatus == HttpStatus.UNAUTHORIZED_401)
                        response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, "Basic realm=\"test\"");
                    Response.writeError(request, response, callback, failStatus);
                    return true;
                }

                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/java-archive");
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, content.length);
                response.write(true, ByteBuffer.wrap(content), callback);
                return true;
            }
        });

        server.start();
        return server;
    }

    private static int getPort(Server server)
    {
        return server.getBean(ServerConnector.class).getLocalPort();
//        return ((ServerConnector)server.getConnectors()[0]).getLocalPort();
    }

    @Test
    public void testAddModuleFromUrl() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        String moduleName = "test-download";
        Path configJar = createConfigJar(jettyBase.resolve("work"), moduleName);
        byte[] jarBytes = Files.readAllBytes(configJar);

        Server fileServer = startFileServer("/" + configJar.getFileName(), jarBytes);
        try
        {
            String downloadUrl = "http://localhost:" + getPort(fileServer) + "/" + configJar.getFileName();

            // Use --add-modules with a full URL to download and install the module.
            try (JettyHomeTester.Run run = distribution.start(
                "--allow-insecure-http-downloads",
                "--add-modules=" + downloadUrl))
            {
                assertTrue(run.awaitForStart(START_TIMEOUT, TimeUnit.SECONDS), run.logs());
                assertEquals(0, run.getExitValue(), run.logs());
            }

            // Verify the module file was extracted into ${jetty.base}/modules/.
            Path installedMod = jettyBase.resolve("modules/" + moduleName + ".mod");
            assertTrue(Files.exists(installedMod),
                "Module file should have been extracted to " + installedMod);

            // Verify the module was enabled (start.d/test-download.ini or start.ini should reference it).
            Path startD = jettyBase.resolve("start.d/" + moduleName + ".ini");
            Path startIni = jettyBase.resolve("start.ini");
            assertTrue(Files.exists(startD) || Files.exists(startIni),
                "Module should be enabled in start.d or start.ini");

            if (Files.exists(startD))
            {
                String iniContent = Files.readString(startD);
                assertTrue(iniContent.contains("--modules=" + moduleName),
                    "start.d INI should contain --modules=" + moduleName);
            }
        }
        finally
        {
            fileServer.stop();
        }
    }

    @Test
    public void testAddModuleFromUrlWithMultipleModules() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        // Create a config JAR containing two module definitions.
        Path workDir = Files.createDirectories(jettyBase.resolve("work"));
        Path jarFile = workDir.resolve("multi-config.jar");

        String modAlpha = """
            [description]
            Alpha module from remote config JAR.

            [depend]
            server
            """;

        String modBeta = """
            [description]
            Beta module from remote config JAR.

            [depend]
            server
            """;

        try (OutputStream fos = Files.newOutputStream(jarFile);
             JarOutputStream jos = new JarOutputStream(fos))
        {
            jos.putNextEntry(new JarEntry("modules/test-alpha.mod"));
            jos.write(modAlpha.getBytes());
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("modules/test-beta.mod"));
            jos.write(modBeta.getBytes());
            jos.closeEntry();
        }

        byte[] jarBytes = Files.readAllBytes(jarFile);
        Server fileServer = startFileServer("/multi-config.jar", jarBytes);
        try
        {
            String downloadUrl = "http://localhost:" + getPort(fileServer) + "/multi-config.jar";

            // Download and install the config JAR containing multiple modules.
            try (JettyHomeTester.Run run = distribution.start(
                "--allow-insecure-http-downloads",
                "--add-modules=" + downloadUrl))
            {
                assertTrue(run.awaitForStart(START_TIMEOUT, TimeUnit.SECONDS), run.logs());
                assertEquals(0, run.getExitValue(), run.logs());
            }

            // Verify both module files were extracted.
            assertTrue(Files.exists(jettyBase.resolve("modules/test-alpha.mod")),
                "test-alpha.mod should have been extracted");
            assertTrue(Files.exists(jettyBase.resolve("modules/test-beta.mod")),
                "test-beta.mod should have been extracted");
        }
        finally
        {
            fileServer.stop();
        }
    }

    @Test
    public void testAddModuleFromUrlAndStartServer() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        String moduleName = "test-download-http";
        Path workDir = Files.createDirectories(jettyBase.resolve("work"));
        Path jarFile = workDir.resolve(moduleName + "-config.jar");

        // Create a module that depends on http (so the server actually starts and listens).
        String modContent = """
            [description]
            Test module that enables HTTP via remote download.

            [depend]
            http
            """;

        try (OutputStream fos = Files.newOutputStream(jarFile);
             JarOutputStream jos = new JarOutputStream(fos))
        {
            jos.putNextEntry(new JarEntry("modules/" + moduleName + ".mod"));
            jos.write(modContent.getBytes());
            jos.closeEntry();
        }

        byte[] jarBytes = Files.readAllBytes(jarFile);
        Server fileServer = startFileServer("/" + jarFile.getFileName(), jarBytes);
        try
        {
            String downloadUrl = "http://localhost:" + getPort(fileServer) + "/" + jarFile.getFileName();

            // Step 1: Download and install the module.
            try (JettyHomeTester.Run run1 = distribution.start(
                "--allow-insecure-http-downloads",
                "--add-modules=" + downloadUrl))
            {
                assertTrue(run1.awaitForStart(START_TIMEOUT, TimeUnit.SECONDS), run1.logs());
                assertEquals(0, run1.getExitValue(), run1.logs());
            }

            // Step 2: Start the server with the downloaded module.
            int jettyPort = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + jettyPort))
            {
                assertTrue(run2.awaitForJettyStart(), run2.logs());

                startHttpClient();
                org.eclipse.jetty.client.ContentResponse response = client.GET("http://localhost:" + jettyPort);
                assertThat(response.getStatus(), is(404));
            }
        }
        finally
        {
            fileServer.stop();
        }
    }

    @Test
    public void testAddModuleFromUrlWithBasicAuth() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        String moduleName = "test-basic-auth";
        Path configJar = createConfigJar(jettyBase.resolve("work"), moduleName);

        String username = "testuser";
        String password = "testpass";
        String expectedAuth = "Basic " + Base64.getEncoder()
            .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));

        byte[] jarBytes = Files.readAllBytes(configJar);
        Server fileServer = startAuthFileServer(
            "/" + configJar.getFileName(), jarBytes, expectedAuth, HttpStatus.UNAUTHORIZED_401);
        try
        {
            String downloadUrl = "http://localhost:" + getPort(fileServer) + "/" + configJar.getFileName();

            try (JettyHomeTester.Run run = distribution.start(
                "--allow-insecure-http-downloads",
                "--download-username=" + username,
                "--download-password=" + password,
                "--add-modules=" + downloadUrl))
            {
                assertTrue(run.awaitForStart(START_TIMEOUT, TimeUnit.SECONDS), run.logs());
                assertEquals(0, run.getExitValue(), run.logs());
            }

            Path installedMod = jettyBase.resolve("modules/" + moduleName + ".mod");
            assertTrue(Files.exists(installedMod),
                "Module file should have been extracted to " + installedMod);
        }
        finally
        {
            fileServer.stop();
        }
    }

    @Test
    public void testAddModuleFromUrlWithBearerToken() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        String moduleName = "test-bearer-auth";
        Path configJar = createConfigJar(jettyBase.resolve("work"), moduleName);

        String bearerToken = "my-secret-token-12345";

        byte[] jarBytes = Files.readAllBytes(configJar);
        Server fileServer = startAuthFileServer(
            "/" + configJar.getFileName(), jarBytes, "Bearer " + bearerToken, HttpStatus.FORBIDDEN_403);
        try
        {
            String downloadUrl = "http://localhost:" + getPort(fileServer) + "/" + configJar.getFileName();

            try (JettyHomeTester.Run run = distribution.start(
                "--allow-insecure-http-downloads",
                "--download-auth-header=Bearer " + bearerToken,
                "--add-modules=" + downloadUrl))
            {
                assertTrue(run.awaitForStart(START_TIMEOUT, TimeUnit.SECONDS), run.logs());
                assertEquals(0, run.getExitValue(), run.logs());
            }

            Path installedMod = jettyBase.resolve("modules/" + moduleName + ".mod");
            assertTrue(Files.exists(installedMod),
                "Module file should have been extracted to " + installedMod);
        }
        finally
        {
            fileServer.stop();
        }
    }
}
