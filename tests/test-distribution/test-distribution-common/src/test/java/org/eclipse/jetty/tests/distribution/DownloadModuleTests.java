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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import com.sun.net.httpserver.HttpServer;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.tests.testers.Tester;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for downloading modules from a URL via {@code --add-modules=<url>}.
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

        // Start a local HTTP server to serve the config JAR.
        int httpPort = Tester.freePort();
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", httpPort), 0);
        byte[] jarBytes = Files.readAllBytes(configJar);
        httpServer.createContext("/" + configJar.getFileName(), exchange ->
        {
            exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
            exchange.sendResponseHeaders(200, jarBytes.length);
            try (OutputStream os = exchange.getResponseBody())
            {
                os.write(jarBytes);
            }
        });
        httpServer.start();

        try
        {
            String downloadUrl = "http://localhost:" + httpPort + "/" + configJar.getFileName();

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
            httpServer.stop(0);
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

        // Start a local HTTP server to serve the config JAR.
        int httpPort = Tester.freePort();
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", httpPort), 0);
        byte[] jarBytes = Files.readAllBytes(jarFile);
        httpServer.createContext("/multi-config.jar", exchange ->
        {
            exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
            exchange.sendResponseHeaders(200, jarBytes.length);
            try (OutputStream os = exchange.getResponseBody())
            {
                os.write(jarBytes);
            }
        });
        httpServer.start();

        try
        {
            String downloadUrl = "http://localhost:" + httpPort + "/multi-config.jar";

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
            httpServer.stop(0);
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

        // Start a local HTTP server to serve the config JAR.
        int fileServerPort = Tester.freePort();
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", fileServerPort), 0);
        byte[] jarBytes = Files.readAllBytes(jarFile);
        httpServer.createContext("/" + jarFile.getFileName(), exchange ->
        {
            exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
            exchange.sendResponseHeaders(200, jarBytes.length);
            try (OutputStream os = exchange.getResponseBody())
            {
                os.write(jarBytes);
            }
        });
        httpServer.start();

        try
        {
            String downloadUrl = "http://localhost:" + fileServerPort + "/" + jarFile.getFileName();

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
            httpServer.stop(0);
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

        // Start a local HTTP server that requires Basic Auth.
        int httpPort = Tester.freePort();
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", httpPort), 0);
        byte[] jarBytes = Files.readAllBytes(configJar);
        httpServer.createContext("/" + configJar.getFileName(), exchange ->
        {
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            if (!expectedAuth.equals(authHeader))
            {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"test\"");
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
            exchange.sendResponseHeaders(200, jarBytes.length);
            try (OutputStream os = exchange.getResponseBody())
            {
                os.write(jarBytes);
            }
        });
        httpServer.start();

        try
        {
            String downloadUrl = "http://localhost:" + httpPort + "/" + configJar.getFileName();

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
            httpServer.stop(0);
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

        // Start a local HTTP server that requires a Bearer token.
        int httpPort = Tester.freePort();
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", httpPort), 0);
        byte[] jarBytes = Files.readAllBytes(configJar);
        httpServer.createContext("/" + configJar.getFileName(), exchange ->
        {
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            if (!("Bearer " + bearerToken).equals(authHeader))
            {
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
            exchange.sendResponseHeaders(200, jarBytes.length);
            try (OutputStream os = exchange.getResponseBody())
            {
                os.write(jarBytes);
            }
        });
        httpServer.start();

        try
        {
            String downloadUrl = "http://localhost:" + httpPort + "/" + configJar.getFileName();

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
            httpServer.stop(0);
        }
    }
}
