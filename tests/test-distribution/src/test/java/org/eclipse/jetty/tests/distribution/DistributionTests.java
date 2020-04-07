//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.tests.distribution;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.unixsocket.client.HttpClientTransportOverUnixSockets;
import org.eclipse.jetty.unixsocket.server.UnixSocketConnector;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class DistributionTests extends AbstractDistributionTest
{
    @Test
    public void testStartStop() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        try (DistributionTester.Run run1 = distribution.start("--add-to-start=http"))
        {
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            int port = distribution.freePort();
            try (DistributionTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port);
                assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());

                run2.stop();
                assertTrue(run2.awaitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void testQuickStartGenerationAndRun() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--create-startd",
            "--approve-all-licenses",
            "--add-to-start=resources,server,http,webapp,deploy,jsp,servlet,servlets,quickstart" 
        };
        
        try (DistributionTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty.tests:test-simple-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "test");

     
            try (DistributionTester.Run run2 = distribution.start("jetty.quickstart.mode=GENERATE"))
            {
                assertTrue(run2.awaitConsoleLogsFor("QuickStartGeneratorConfiguration:main: Generated", 10, TimeUnit.SECONDS));
                Path unpackedWebapp = distribution.getJettyBase().resolve("webapps").resolve("test");
                assertTrue(Files.exists(unpackedWebapp));
                Path webInf = unpackedWebapp.resolve("WEB-INF");
                assertTrue(Files.exists(webInf));
                Path quickstartWebXml = webInf.resolve("quickstart-web.xml");
                assertTrue(Files.exists(quickstartWebXml));
                assertNotEquals(0, Files.size(quickstartWebXml));
                
                int port = distribution.freePort();
                
                try (DistributionTester.Run run3 = distribution.start("jetty.http.port=" + port, "jetty.quickstart.mode=QUICKSTART"))
                {
                    assertTrue(run3.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                    startHttpClient();
                    ContentResponse response = client.GET("http://localhost:" + port + "/test/index.jsp");
                    assertEquals(HttpStatus.OK_200, response.getStatus());
                    assertThat(response.getContentAsString(), containsString("Hello"));
                    assertThat(response.getContentAsString(), not(containsString("<%")));
                }
            }
        }
    }
    
    @Test
    public void testSimpleWebAppWithJSP() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--create-startd",
            "--approve-all-licenses",
            "--add-to-start=resources,server,http,webapp,deploy,jsp,jmx,servlet,servlets"
        };
        try (DistributionTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty.tests:test-simple-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "test");

            int port = distribution.freePort();
            try (DistributionTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/test/index.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("Hello"));
                assertThat(response.getContentAsString(), not(containsString("<%")));
            }
        }
    }

    @Test
    @DisabledOnJre(JRE.JAVA_8)
    public void testSimpleWebAppWithJSPOnModulePath() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--create-startd",
            "--approve-all-licenses",
            "--add-to-start=resources,server,http,webapp,deploy,jsp,jmx,servlet,servlets"
        };
        try (DistributionTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty.tests:test-simple-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "test");

            int port = distribution.freePort();
            String[] args2 = {
                "--jpms",
                "jetty.http.port=" + port
            };
            try (DistributionTester.Run run2 = distribution.start(args2))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/test/index.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("Hello"));
                assertThat(response.getContentAsString(), not(containsString("<%")));
            }
        }
    }

    @Test
    @DisabledOnJre(JRE.JAVA_8)
    public void testSimpleWebAppWithJSPOverH2C() throws Exception
    {
        testSimpleWebAppWithJSPOverHTTP2(false);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_8)
    public void testSimpleWebAppWithJSPOverH2() throws Exception
    {
        testSimpleWebAppWithJSPOverHTTP2(true);
    }

    private void testSimpleWebAppWithJSPOverHTTP2(boolean ssl) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--create-startd",
            "--add-to-start=jsp,deploy," + (ssl ? "http2,test-keystore" : "http2c")
        };
        try (DistributionTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty.tests:test-simple-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "test");

            int port = distribution.freePort();
            String portProp = ssl ? "jetty.ssl.port" : "jetty.http.port";
            try (DistributionTester.Run run2 = distribution.start(portProp + "=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                ClientConnector connector = new ClientConnector();
                connector.setSslContextFactory(new SslContextFactory.Client(true));
                HTTP2Client h2Client = new HTTP2Client(connector);
                startHttpClient(() -> new HttpClient(new HttpClientTransportOverHTTP2(h2Client)));
                ContentResponse response = client.GET((ssl ? "https" : "http") + "://localhost:" + port + "/test/index.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("Hello"));
                assertThat(response.getContentAsString(), not(containsString("<%")));
            }
        }
    }

    @Test
    public void testUnixSocket() throws Exception
    {
        Path tmpSockFile;
        String unixSocketTmp = System.getProperty("unix.socket.tmp");
        if (StringUtil.isNotBlank(unixSocketTmp))
            tmpSockFile = Files.createTempFile(Paths.get(unixSocketTmp), "unix", ".sock");
        else
            tmpSockFile = Files.createTempFile("unix", ".sock");
        if (tmpSockFile.toAbsolutePath().toString().length() > UnixSocketConnector.MAX_UNIX_SOCKET_PATH_LENGTH)
        {
            Path tmp = Paths.get("/tmp");
            assumeTrue(Files.exists(tmp) && Files.isDirectory(tmp));
            tmpSockFile = Files.createTempFile(tmp, "unix", ".sock");
        }
        Path sockFile = tmpSockFile;
        assertTrue(Files.deleteIfExists(sockFile), "temp sock file cannot be deleted");

        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--create-startd",
            "--add-to-start=unixsocket-http,deploy,jsp",
            "--approve-all-licenses"
        };
        try (DistributionTester.Run run1 = distribution.start(args1))
        {
            // Give it time to download the dependencies
            assertTrue(run1.awaitFor(30, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty.tests:test-simple-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "test");

            try (DistributionTester.Run run2 = distribution.start("jetty.unixsocket.path=" + sockFile.toString()))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                startHttpClient(() -> new HttpClient(new HttpClientTransportOverUnixSockets(sockFile.toString())));
                ContentResponse response = client.GET("http://localhost/test/index.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("Hello"));
                assertThat(response.getContentAsString(), not(containsString("<%")));
            }
        }
        finally
        {
            Files.deleteIfExists(sockFile);
        }
    }

    @Test
    public void testLog4j2ModuleWithSimpleWebAppWithJSP() throws Exception
    {
        Path jettyBase = Files.createTempDirectory("jetty_base");
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--create-startd",
            "--approve-all-licenses",
            "--add-to-start=resources,server,http,webapp,deploy,jsp,servlet,servlets,logging-log4j2"
        };
        try (DistributionTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());
            assertTrue(Files.exists(jettyBase.resolve("resources/log4j2.xml")));

            File war = distribution.resolveArtifact("org.eclipse.jetty.tests:test-simple-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "test");

            int port = distribution.freePort();
            try (DistributionTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/test/index.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("Hello"));
                assertThat(response.getContentAsString(), not(containsString("<%")));
                assertTrue(Files.exists(jettyBase.resolve("resources/log4j2.xml")));
            }
        }
        finally
        {
            IO.delete(jettyBase.toFile());
        }
    }

    @Test
    public void testWebAppWithProxyAndJPMS() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        String[] args1 = {
            "--create-startd",
            "--add-to-start=http,webapp,deploy,resources"
        };
        try (DistributionTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Path logFile = distribution.getJettyBase().resolve("resources").resolve("jetty-logging.properties");
            try (BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE))
            {
                writer.write("org.eclipse.jetty.LEVEL=INFO");
            }

            File war = distribution.resolveArtifact("org.eclipse.jetty.tests:test-proxy-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "proxy");

            int port = distribution.freePort();
            try (DistributionTester.Run run2 = distribution.start("--jpms", "jetty.http.port=" + port, "jetty.server.dumpAfterStart=true"))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));

                startHttpClient(() -> new HttpClient(new HttpClientTransportOverHTTP(1)));
                ContentResponse response = client.GET("http://localhost:" + port + "/proxy/current/");
                assertEquals(HttpStatus.OK_200, response.getStatus());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "--jpms",
    })
    public void testSimpleWebAppWithWebsocket(String arg) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();
        String[] args1 = {
            "--create-startd",
            "--approve-all-licenses",
            "--add-to-start=resources,server,http,webapp,deploy,jsp,jmx,servlet,servlets,websocket"
        };
        try (DistributionTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty.tests:test-bad-websocket-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "test1");
            distribution.installWarFile(war, "test2");

            int port = distribution.freePort();
            String[] args2 = {
                arg,
                "jetty.http.port=" + port//,
                //"jetty.server.dumpAfterStart=true"
            };
            try (DistributionTester.Run run2 = distribution.start(args2))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started Server@", 10, TimeUnit.SECONDS));
                assertFalse(run2.getLogs().stream().anyMatch(s -> s.contains("LinkageError")));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port + "/test1/index.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("Hello"));
                assertThat(response.getContentAsString(), not(containsString("<%")));

                client.GET("http://localhost:" + port + "/test2/index.jsp");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("Hello"));
                assertThat(response.getContentAsString(), not(containsString("<%")));
            }
        }
    }

    @Test
    public void testStartStopLog4j2Modules() throws Exception
    {
        Path jettyBase = Files.createTempDirectory("jetty_base");

        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance() //
            .jettyVersion(jettyVersion) //
            .jettyBase(jettyBase) //
            .mavenLocalRepository(System.getProperty("mavenRepoPath")) //
            .build();

        String[] args = {
            "--create-startd",
            "--approve-all-licenses",
            "--add-to-start=http,logging-log4j2"
        };

        try (DistributionTester.Run run1 = distribution.start(args))
        {
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            Files.copy(Paths.get("src/test/resources/log4j2.xml"), //
                       Paths.get(jettyBase.toString(),"resources").resolve("log4j2.xml"), //
                       StandardCopyOption.REPLACE_EXISTING);

            int port = distribution.freePort();
            try (DistributionTester.Run run2 = distribution.start("jetty.http.port=" + port))
            {
                assertTrue(run2.awaitLogsFileFor(
                    jettyBase.resolve("logs").resolve("jetty.log"), //
                    "Started Server@", 10, TimeUnit.SECONDS));

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + port);
                assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());

                run2.stop();
                assertTrue(run2.awaitFor(5, TimeUnit.SECONDS));
            }
        }
    }

}
