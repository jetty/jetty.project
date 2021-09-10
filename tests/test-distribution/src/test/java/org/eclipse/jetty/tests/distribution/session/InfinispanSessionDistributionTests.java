//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.eclipse.jetty.util.IO;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.Configuration;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;

/**
 *
 */
public class InfinispanSessionDistributionTests extends AbstractSessionDistributionTests
{

    private static final Logger LOGGER = LoggerFactory.getLogger(InfinispanSessionDistributionTests.class);
    private static final Logger INFINISPAN_LOG = LoggerFactory.getLogger("org.eclipse.jetty.tests.distribution.session.infinispan");

    private GenericContainer infinispan;

    private String host;

    @Override
    public void startExternalSessionStorage() throws Exception
    {
        String infinispanVersion = System.getProperty("infinispan.docker.image.version", "9.4.8.Final");
        infinispan =
                new GenericContainer(System.getProperty("infinispan.docker.image.name", "jboss/infinispan-server") +
                        ":" + infinispanVersion)
                        //.withEnv("APP_USER", "theuser")
                        //.withEnv("APP_PASS", "foobar")
                        .withEnv("MGMT_USER", "admin")
                        .withEnv("MGMT_PASS", "admin")
                        .withCommand("standalone")
                        .waitingFor(new LogMessageWaitStrategy()
                                .withRegEx(".*Infinispan Server.*started in.*\\s"))
                        .withExposedPorts(4712, 4713, 8088, 8089, 8443, 9990, 9993, 11211, 11222, 11223, 11224)
                        .withLogConsumer(new Slf4jLogConsumer(INFINISPAN_LOG));
        infinispan.start();
        String host = infinispan.getContainerIpAddress();
        int port = infinispan.getMappedPort(11222);

        Path resourcesDirectory = Path.of(jettyHomeTester.getJettyBase().toString(), "resources/");
        if (Files.exists(resourcesDirectory))
        {
            IO.delete(resourcesDirectory.toFile());
        }
        Files.createDirectories(resourcesDirectory);
        Properties properties = new Properties();
        properties.put("infinispan.client.hotrod.server_list", host + ":" + port);
        //properties.put("jetty.session.infinispan.clientIntelligence", "BASIC");

        Path hotrod = Path.of(resourcesDirectory.toString(), "hotrod-client.properties");
        Files.deleteIfExists(hotrod);
        Files.createFile(hotrod);
        try (Writer writer = Files.newBufferedWriter(hotrod))
        {
            properties.store(writer, null);
        }

        Configuration configuration = new ConfigurationBuilder().withProperties(properties)
                .addServer().host(host).port(port).build();

        RemoteCacheManager remoteCacheManager = new RemoteCacheManager(configuration);
        remoteCacheManager.administration().getOrCreateCache("sessions", (String)null);

    }

    @Override
    public void stopExternalSessionStorage() throws Exception
    {
        infinispan.stop();
    }

    @Override
    public List<String> getFirstStartExtraArgs()
    {
        return Arrays.asList();
    }

    @Override
    public String getFirstStartExtraModules()
    {
        return "session-store-infinispan-remote,infinispan-remote-query";
    }

    @Override
    public List<String> getSecondStartExtraArgs()
    {
        return Arrays.asList();
    }

}
