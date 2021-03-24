//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.tests.distribution;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HazelcastSessionDistributionTests extends AbstractDistributionTest
{
    private static final Logger HAZELCAST_LOG = LoggerFactory.getLogger("org.eclipse.jetty.tests.distribution.HazelcastLogs");

    private static final Logger LOGGER = LoggerFactory.getLogger(HazelcastSessionDistributionTests.class);

    @Test
    public void testHazelcastRemoteOnlyClient() throws Exception
    {
        try (GenericContainer hazelcast =
                            new GenericContainer("hazelcast/hazelcast:" + System.getProperty("hazelcast.version", "3.12.6"))
                                    .withExposedPorts(5701)
                            .waitingFor(Wait.forListeningPort())
                            .withLogConsumer(new Slf4jLogConsumer(HAZELCAST_LOG)))
        {
            hazelcast.start();
            String hazelcastHost = hazelcast.getContainerIpAddress();
            int hazelcastPort = hazelcast.getMappedPort(5701);

            LOGGER.info("hazelcast started on {}:{}", hazelcastHost, hazelcastPort);

            Map<String, String> tokenValues = new HashMap<>();
            tokenValues.put("hazelcast_ip", hazelcastHost);
            tokenValues.put("hazelcast_port", Integer.toString(hazelcastPort));
            Path hazelcastJettyPath = Paths.get("target/hazelcast-client.xml");
            transformFileWithHostAndPort(Paths.get("src/test/resources/hazelcast-client.xml"),
                                          hazelcastJettyPath,
                                          tokenValues);

            String jettyVersion = System.getProperty("jettyVersion");
            DistributionTester distribution = DistributionTester.Builder.newInstance()
                .jettyVersion(jettyVersion)
                .mavenLocalRepository(System.getProperty("mavenRepoPath"))
                .build();

            String[] args1 = {
                "--create-startd",
                "--approve-all-licenses",
                "--add-to-start=resources,server,http,webapp,deploy,jsp,jmx,servlet,servlets,session-store-hazelcast-remote"
            };
            try (DistributionTester.Run run1 = distribution.start(args1))
            {
                assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
                assertEquals(0, run1.getExitValue());

                File war = distribution.resolveArtifact("org.eclipse.jetty.tests:test-simple-session-webapp:war:" + jettyVersion);
                distribution.installWarFile(war, "test");

                int port = distribution.freePort();
                String[] argsStart = {
                    "jetty.http.port=" + port,
                    "jetty.session.hazelcast.configurationLocation=" + hazelcastJettyPath.toAbsolutePath(),
                    "jetty.session.hazelcast.onlyClient=true"
                };
                try (DistributionTester.Run run2 = distribution.start(argsStart))
                {
                    assertTrue(run2.awaitConsoleLogsFor("Started @", 10, TimeUnit.SECONDS));

                    startHttpClient();
                    ContentResponse response = client.GET("http://localhost:" + port + "/test/session?action=CREATE");
                    assertEquals(HttpStatus.OK_200, response.getStatus());
                    assertThat(response.getContentAsString(), containsString("SESSION CREATED"));

                    response = client.GET("http://localhost:" + port + "/test/session?action=READ");
                    assertEquals(HttpStatus.OK_200, response.getStatus());
                    assertThat(response.getContentAsString(), containsString("SESSION READ CHOCOLATE THE BEST:FRENCH"));
                }

                try (DistributionTester.Run run2 = distribution.start(argsStart))
                {
                    assertTrue(run2.awaitConsoleLogsFor("Started @", 10, TimeUnit.SECONDS));

                    ContentResponse response = client.GET("http://localhost:" + port + "/test/session?action=READ");
                    assertEquals(HttpStatus.OK_200, response.getStatus());
                    assertThat(response.getContentAsString(), containsString("SESSION READ CHOCOLATE THE BEST:FRENCH"));
                }
            }

        }
    }

    @Test
    public void testHazelcastRemote() throws Exception
    {

        Map<String, String> env = new HashMap<>();
        //
        env.put("JAVA_OPTS", "-Dhazelcast.local.publicAddress=127.0.0.1:5701 -Dhazelcast.config=/opt/hazelcast/config_ext/hazelcast.xml");
        try (GenericContainer hazelcast =
                 new GenericContainer("hazelcast/hazelcast:" + System.getProperty("hazelcast.version", "3.12.6"))
                     .withExposedPorts(5701, 55125, 55126) //, 54327)
                     .withEnv(env)
                     .waitingFor(Wait.forLogMessage(".*is STARTED.*", 1))
                     //.withNetworkMode("host")
                     //.waitingFor(Wait.forListeningPort())
                     .withClasspathResourceMapping("hazelcast-server.xml",
                                                   "/opt/hazelcast/config_ext/hazelcast.xml",
                                                   BindMode.READ_ONLY)
                     .withLogConsumer(new Slf4jLogConsumer(HAZELCAST_LOG)))
        {
            hazelcast.start();
            String hazelcastHost = hazelcast.getContainerIpAddress();
            int hazelcastPort = hazelcast.getMappedPort(5701);
//            int hazelcastMultiCastPort = hazelcast.getMappedPort(54327);

            LOGGER.info("hazelcast started on {}:{}", hazelcastHost, hazelcastPort);

            Map<String, String> tokenValues = new HashMap<>();
            tokenValues.put("hazelcast_ip", hazelcastHost);
            tokenValues.put("hazelcast_port", Integer.toString(hazelcastPort));
//            tokenValues.put("hazelcast_multicast_port", Integer.toString(hazelcastMultiCastPort));
            Path hazelcastJettyPath = Paths.get("target/hazelcast-jetty.xml");
            transformFileWithHostAndPort(Paths.get("src/test/resources/hazelcast-jetty.xml"),
                                         hazelcastJettyPath,
                                         tokenValues);

            String jettyVersion = System.getProperty("jettyVersion");
            DistributionTester distribution = DistributionTester.Builder.newInstance()
                .jettyVersion(jettyVersion)
                .mavenLocalRepository(System.getProperty("mavenRepoPath"))
                .build();

            String[] args1 = {
                "--create-startd",
                "--approve-all-licenses",
                "--add-to-start=resources,server,http,webapp,deploy,jsp,jmx,servlet,servlets,session-store-hazelcast-remote"
            };
            try (DistributionTester.Run run1 = distribution.start(args1))
            {
                assertTrue(run1.awaitFor(10, TimeUnit.SECONDS));
                assertEquals(0, run1.getExitValue());


                Path jettyBase = distribution.getJettyBase();
                //session-store-hazelcast-remote.ini
                // we should not need this
                File startdDirectory = new File(jettyBase.toFile(), "start.d");
                Files.copy(Paths.get("src/test/resources/session-store-hazelcast-remote.ini"),
                           new File(startdDirectory, "session-store-hazelcast-remote.ini").toPath(),
                           StandardCopyOption.REPLACE_EXISTING);

                File war = distribution.resolveArtifact("org.eclipse.jetty.tests:test-simple-session-webapp:war:" + jettyVersion);
                distribution.installWarFile(war, "test");

                int port = distribution.freePort();
                List<String> argsStart = Arrays.asList(
                    "jetty.http.port=" + port,
                    "jetty.session.hazelcast.onlyClient=false",
                    "jetty.session.hazelcast.configurationLocation=" + hazelcastJettyPath.toAbsolutePath()
                );

                try (DistributionTester.Run run2 = distribution.start(argsStart))
                {
                    assertTrue(run2.awaitConsoleLogsFor("Started @", 60, TimeUnit.SECONDS));

                    startHttpClient();
                    ContentResponse response = client.GET("http://localhost:" + port + "/test/session?action=CREATE");
                    assertEquals(HttpStatus.OK_200, response.getStatus());
                    assertThat(response.getContentAsString(), containsString("SESSION CREATED"));

                    response = client.GET("http://localhost:" + port + "/test/session?action=READ");
                    assertEquals(HttpStatus.OK_200, response.getStatus());
                    assertThat(response.getContentAsString(), containsString("SESSION READ CHOCOLATE THE BEST:FRENCH"));
                }

                LOGGER.info("restarting Jetty");

                try (DistributionTester.Run run2 = distribution.start(argsStart))
                {
                    assertTrue(run2.awaitConsoleLogsFor("Started @", 15, TimeUnit.SECONDS));

                    ContentResponse response = client.GET("http://localhost:" + port + "/test/session?action=READ");
                    assertEquals(HttpStatus.OK_200, response.getStatus());
                    assertThat(response.getContentAsString(), containsString("SESSION READ CHOCOLATE THE BEST:FRENCH"));
                }
            }

        }
    }

    /**
     * @param input input file to interpolate
     * @param output output file of interpolation
     * @param tokensValues key token to replace, value the value
     */
    private void transformFileWithHostAndPort(Path input, Path output, Map<String, String> tokensValues) throws Exception
    {
        StringBuilder fileContent = new StringBuilder();
        Files.deleteIfExists(output);
        Files.createFile(output);
        try (OutputStream outputStream = Files.newOutputStream(output))
        {
            Files.readAllLines(input).forEach(line ->
            {
                StringBuilder newLine = new StringBuilder(line);
                tokensValues.forEach((key, value) ->
                {
                    String interpolated = newLine.toString().replace(key, value);
                    newLine.setLength(0);
                    newLine.append(interpolated);
                });
                fileContent.append(newLine.toString());
                fileContent.append(System.lineSeparator());
            });


            outputStream.write(fileContent.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

}
