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

package org.eclipse.jetty.websocket.core.autobahn;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
public class AutobahnTests
{
    private static final Logger LOG = LoggerFactory.getLogger(AutobahnTests.class);
    private static final Path USER_DIR = Paths.get(System.getProperty("user.dir"));

    @Test
    public void testClient() throws Exception
    {
        Path fuzzingServer = USER_DIR.resolve("fuzzingserver.json");
        assertTrue(Files.exists(fuzzingServer));

        Path reportDir = USER_DIR.resolve("target/reports");
        if (!Files.exists(reportDir))
            Files.createDirectory(reportDir);

        // Start a jetty docker image with this imageTag, binding the directory of a simple webapp.
        GenericContainer<?> container = new GenericContainer<>("crossbario/autobahn-testsuite:latest")
            .withLogConsumer(new Slf4jLogConsumer(LOG))
            .withExposedPorts(9001);
        container.addFileSystemBind(fuzzingServer.toString(), "/config/fuzzingserver.json", BindMode.READ_ONLY);
        container.addFileSystemBind(reportDir.toString(), "/target/reports", BindMode.READ_WRITE);

        try
        {
            container.start();
            Integer mappedPort = container.getMappedPort(9001);
            CoreAutobahnClient.main(new String[]{"localhost", mappedPort.toString()});
        }
        finally
        {
            container.stop();
        }

        LOG.info("Test Result Overview {}", reportDir.resolve("clients/index.html").toUri());
    }

    @Test
    public void testServer() throws Exception
    {
        Path fuzzingClient = USER_DIR.resolve("fuzzingclient.json");
        assertTrue(Files.exists(fuzzingClient));

        Path reportDir = USER_DIR.resolve("target/reports");
        if (!Files.exists(reportDir))
            Files.createDirectory(reportDir);

        // We need to expose the host port of the server to the Autobahn Client in docker container.
        final int port = 9001;
        org.testcontainers.Testcontainers.exposeHostPorts(port);
        Server server = CoreAutobahnServer.startAutobahnServer(port);

        GenericContainer<?> container = new GenericContainer<>("crossbario/autobahn-testsuite:latest")
            .withCommand("wstest -m fuzzingclient -s /config/fuzzingclient.json")
            .withLogConsumer(new Slf4jLogConsumer(LOG))
            .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofHours(1)));
        container.addFileSystemBind(fuzzingClient.toString(), "/config/fuzzingclient.json", BindMode.READ_ONLY);
        container.addFileSystemBind(reportDir.toString(), "/target/reports", BindMode.READ_WRITE);

        try
        {
            container.start();
        }
        finally
        {
            container.stop();
            server.stop();
        }

        LOG.info("Test Result Overview {}", reportDir.resolve("servers/index.html").toUri());
    }
}
