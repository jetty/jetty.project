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

package org.eclipse.jetty.websocket.tests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

import com.github.dockerjava.api.DockerClient;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.tests.core.CoreAutobahnClient;
import org.eclipse.jetty.websocket.tests.core.CoreAutobahnServer;
import org.eclipse.jetty.websocket.tests.javax.JavaxAutobahnClient;
import org.eclipse.jetty.websocket.tests.javax.JavaxAutobahnServer;
import org.eclipse.jetty.websocket.tests.jetty.JettyAutobahnClient;
import org.eclipse.jetty.websocket.tests.jetty.JettyAutobahnServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import static org.eclipse.jetty.websocket.tests.AutobahnUtils.copyFromContainer;
import static org.eclipse.jetty.websocket.tests.AutobahnUtils.parseResults;
import static org.eclipse.jetty.websocket.tests.AutobahnUtils.throwIfFailed;
import static org.eclipse.jetty.websocket.tests.AutobahnUtils.writeJUnitXmlReport;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
public class AutobahnTests
{
    private static final Logger LOG = LoggerFactory.getLogger(AutobahnTests.class);
    private static final Path USER_DIR = Paths.get(System.getProperty("user.dir"));

    private Path reportDir;
    private Path fuzzingServer;
    private Path fuzzingClient;

    private AutobahnServer server;
    private AutobahnClient client;

    @BeforeAll
    public static void clean()
    {
        Path reportDir = USER_DIR.resolve("target/reports");
        IO.delete(reportDir.toFile());
    }

    public void setup(String version) throws Exception
    {
        fuzzingServer = USER_DIR.resolve("fuzzingserver.json");
        assertTrue(Files.exists(fuzzingServer), fuzzingServer + " not exists");

        fuzzingClient = USER_DIR.resolve("fuzzingclient.json");
        assertTrue(Files.exists(fuzzingClient), fuzzingClient + " not exists");

        reportDir = USER_DIR.resolve("target/reports/" + version);
        if (!Files.exists(reportDir))
            Files.createDirectories(reportDir);

        switch (version)
        {
            case "jetty":
                server = new JettyAutobahnServer();
                client = new JettyAutobahnClient();
                break;
            case "javax":
                server = new JavaxAutobahnServer();
                client = new JavaxAutobahnClient();
                break;
            case "core":
                server = new CoreAutobahnServer();
                client = new CoreAutobahnClient();
                break;
            default:
                throw new IllegalStateException(version);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"core", "jetty", "javax"})
    public void testClient(String version) throws Exception
    {
        setup(version);

        try (GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("jettyproject/autobahn-testsuite:latest"))
            .withCommand("/bin/bash", "-c", "wstest -m fuzzingserver -s /config/fuzzingserver.json")
            .withExposedPorts(9001)
            .withCopyFileToContainer(MountableFile.forHostPath(fuzzingServer), "/config/fuzzingserver.json")
            .withLogConsumer(new Slf4jLogConsumer(LOG))
            .withStartupTimeout(Duration.ofHours(2)))
        {
            container.start();
            Integer mappedPort = container.getMappedPort(9001);
            client.runAutobahnClient(container.getContainerIpAddress(), mappedPort, null);

            DockerClient dockerClient = container.getDockerClient();
            String containerId = container.getContainerId();
            copyFromContainer(dockerClient, containerId, reportDir, Paths.get("/target/reports/clients"));
        }

        LOG.info("Test Result Overview {}", reportDir.resolve("clients/index.html").toUri());
        List<AutobahnUtils.AutobahnCaseResult> results = parseResults(reportDir.resolve("clients/index.json"));
        String className = getClass().getName();
        writeJUnitXmlReport(results, version + "-autobahn-client", className + ".client");
        throwIfFailed(results);
    }

    @ParameterizedTest
    @ValueSource(strings = {"core", "jetty", "javax"})
    public void testServer(String version) throws Exception
    {
        setup(version);

        // We need to expose the host port of the server to the Autobahn Client in docker container.
        final int port = 9001;
        org.testcontainers.Testcontainers.exposeHostPorts(port);
        server.startAutobahnServer(port);

        AutobahnUtils.FileSignalWaitStrategy strategy = new AutobahnUtils.FileSignalWaitStrategy(reportDir, Paths.get("/target/reports/servers"));
        try (GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("jettyproject/autobahn-testsuite:latest"))
            .withCommand("/bin/bash", "-c", "wstest -m fuzzingclient -s /config/fuzzingclient.json" + AutobahnUtils.FileSignalWaitStrategy.END_COMMAND)
            .withLogConsumer(new Slf4jLogConsumer(LOG))
            .withCopyFileToContainer(MountableFile.forHostPath(fuzzingClient), "/config/fuzzingclient.json")
            .withStartupCheckStrategy(strategy)
            .withStartupTimeout(Duration.ofHours(2)))
        {
            container.start();
        }
        finally
        {
            server.stopAutobahnServer();
        }

        LOG.info("Test Result Overview {}", reportDir.resolve("servers/index.html").toUri());
        List<AutobahnUtils.AutobahnCaseResult> results = parseResults(reportDir.resolve("servers/index.json"));
        String className = getClass().getName();
        writeJUnitXmlReport(results, version + "-autobahn-server", className + ".server");
        throwIfFailed(results);
    }
}
