//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.tests.distribution.session;

import java.io.File;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.hometester.JettyHomeTester;
import org.junit.jupiter.api.Disabled;
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

public class HazelcastSessionDistributionTests extends AbstractSessionDistributionTests
{
    private static final Logger HAZELCAST_LOG = LoggerFactory.getLogger("org.eclipse.jetty.tests.distribution.session.HazelcastLogs");

    private static final Logger LOGGER = LoggerFactory.getLogger(HazelcastSessionDistributionTests.class);

    private GenericContainer<?> hazelcast = new GenericContainer<>("hazelcast/hazelcast:" + System.getProperty("hazelcast.version", "4.2.2"))
            .withExposedPorts(5701)
            .waitingFor(Wait.forLogMessage(".*is STARTED.*", 1))
            .withLogConsumer(new Slf4jLogConsumer(HAZELCAST_LOG));

    private Path hazelcastJettyPath;

    @Override
    public void startExternalSessionStorage() throws Exception
    {
        if (!hazelcast.isRunning())
        {
            hazelcast.start();
        }
        String hazelcastHost = hazelcast.getContainerIpAddress();
        int hazelcastPort = hazelcast.getMappedPort(5701);

        LOGGER.info("hazelcast started on {}:{}", hazelcastHost, hazelcastPort);

        Map<String, String> tokenValues = new HashMap<>();
        tokenValues.put("hazelcast_ip", hazelcastHost);
        tokenValues.put("hazelcast_port", Integer.toString(hazelcastPort));
        this.hazelcastJettyPath = Paths.get("target/hazelcast-client.xml");
        transformFileWithHostAndPort(Paths.get("src/test/resources/hazelcast-client.xml"),
            hazelcastJettyPath,
            tokenValues);
    }

    @Override
    public void stopExternalSessionStorage()
    {
        hazelcast.stop();
    }

    @Override
    public void configureExternalSessionStorage(Path jettyBase) throws Exception
    {
        // no op
    }
    
    @Override
    public List<String> getFirstStartExtraArgs()
    {
        return Collections.emptyList();
    }

    @Override
    public String getFirstStartExtraModules()
    {
        return "session-store-hazelcast-remote";
    }

    @Override
    public List<String> getSecondStartExtraArgs()
    {
        return Arrays.asList(
            "jetty.session.hazelcast.configurationLocation=" + hazelcastJettyPath.toAbsolutePath(),
            "jetty.session.hazelcast.onlyClient=true"
        );
    }

    /**
     * This test simulate Hazelcast instance within Jetty a cluster member with an external Hazelcast instance
     */
    @Test
    @Disabled("not working see https://github.com/hazelcast/hazelcast/issues/18508")
    public void testHazelcastRemoteAndPartOfCluster() throws Exception
    {

        Map<String, String> env = new HashMap<>();
        // -Dhazelcast.local.publicAddress=127.0.0.1:5701
        env.put("JAVA_OPTS", "-Dhazelcast.config=/opt/hazelcast/config_ext/hazelcast.xml");
        try (GenericContainer<?> hazelcast =
                 new GenericContainer<>("hazelcast/hazelcast:" + System.getProperty("hazelcast.version", "4.1"))
                     .withExposedPorts(5701, 5705)
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
            String hazelcastHost = InetAddress.getByName(hazelcast.getContainerIpAddress()).getHostAddress(); // hazelcast.getContainerIpAddress();
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
            JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
                .jettyVersion(jettyVersion)
                .mavenLocalRepository(System.getProperty("mavenRepoPath"))
                .build();

            String[] args1 = {
                "--create-startd",
                "--approve-all-licenses",
                "--add-to-start=resources,server,http,webapp,deploy,jmx,servlet,servlets,session-store-hazelcast-remote"
            };
            try (JettyHomeTester.Run run1 = distribution.start(args1))
            {
                assertTrue(run1.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
                assertEquals(0, run1.getExitValue());

                File war = distribution.resolveArtifact("org.eclipse.jetty.tests:test-simple-session-webapp:war:" + jettyVersion);
                distribution.installWarFile(war, "test");

                int port = distribution.freePort();
                List<String> argsStart = Arrays.asList(
                    "jetty.http.port=" + port,
                    "jetty.session.hazelcast.onlyClient=false",
                    "jetty.session.hazelcast.configurationLocation=" + hazelcastJettyPath.toAbsolutePath()
                );

                try (JettyHomeTester.Run run2 = distribution.start(argsStart))
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

                try (JettyHomeTester.Run run2 = distribution.start(argsStart))
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
                fileContent.append(newLine);
                fileContent.append(System.lineSeparator());
            });

            outputStream.write(fileContent.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
}
