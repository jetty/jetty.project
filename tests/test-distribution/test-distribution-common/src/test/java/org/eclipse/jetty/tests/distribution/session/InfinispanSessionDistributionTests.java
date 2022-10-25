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

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.eclipse.jetty.session.infinispan.InfinispanSerializationContextInitializer;
import org.eclipse.jetty.util.IO;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.Configuration;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.commons.configuration.XMLStringConfiguration;
import org.infinispan.commons.marshall.ProtoStreamMarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
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

    @SuppressWarnings("rawtypes")
    private GenericContainer infinispan;

    private String host;
    private int port;

    @Override
    public void startExternalSessionStorage() throws Exception
    {
        String infinispanVersion = System.getProperty("infinispan.docker.image.version", "11.0.14.Final");
        infinispan =
                new GenericContainer(System.getProperty("infinispan.docker.image.name", "infinispan/server") +
                        ":" + infinispanVersion)
                        .withEnv("USER", "theuser")
                        .withEnv("PASS", "foobar")
                        .withEnv("MGMT_USER", "admin")
                        .withEnv("MGMT_PASS", "admin")
                        .withEnv("CONFIG_PATH", "/user-config/config.yaml")
                        .waitingFor(new LogMessageWaitStrategy()
                                .withRegEx(".*Infinispan Server.*started in.*\\s"))
                        .withExposedPorts(4712, 4713, 8088, 8089, 8443, 9990, 9993, 11211, 11222, 11223, 11224)
                        .withLogConsumer(new Slf4jLogConsumer(INFINISPAN_LOG))
                        .withClasspathResourceMapping("/config.yaml", "/user-config/config.yaml", BindMode.READ_ONLY);
        infinispan.start();
        host = infinispan.getContainerIpAddress();
        port = infinispan.getMappedPort(11222);

        Path resourcesDirectory = Path.of(jettyHomeTester.getJettyBase().toString(), "resources/");
        if (Files.exists(resourcesDirectory))
        {
            IO.delete(resourcesDirectory.toFile());
        }

        Files.createDirectories(resourcesDirectory);
        Properties properties = new Properties();
        properties.put("infinispan.client.hotrod.server_list", host + ":" + port);
        properties.put("infinispan.client.hotrod.use_auth", "true");
        properties.put("infinispan.client.hotrod.sasl_mechanism", "DIGEST-MD5");
        properties.put("infinispan.client.hotrod.auth_username", "theuser");
        properties.put("infinispan.client.hotrod.auth_password", "foobar");


        Path hotrod = Path.of(resourcesDirectory.toString(), "hotrod-client.properties");
        Files.deleteIfExists(hotrod);
        Files.createFile(hotrod);
        try (Writer writer = Files.newBufferedWriter(hotrod))
        {
            properties.store(writer, null);
        }

        Configuration configuration = new ConfigurationBuilder().withProperties(properties)
                .addServer().host(host).port(port)
                .marshaller(new ProtoStreamMarshaller())
                .addContextInitializer(new InfinispanSerializationContextInitializer())
                .security().authentication().saslMechanism("DIGEST-MD5")
                .username("theuser").password("foobar")
                .build();

        RemoteCacheManager remoteCacheManager = new RemoteCacheManager(configuration);
        ByteArrayOutputStream baos;
        try (InputStream is = this.getClass().getClassLoader().getResourceAsStream("session.proto"))
        {
            if (is == null)
                throw new IllegalStateException("inputstream is null");

            baos = new ByteArrayOutputStream();
            IO.copy(is, baos);
        }

        String content = baos.toString("UTF-8");
        remoteCacheManager.administration().getOrCreateCache("___protobuf_metadata", (String)null).put("session.proto", content);

        String xml = String.format("<infinispan>"  +
            "<cache-container>" + "<distributed-cache name=\"%s\" mode=\"SYNC\">" +
            "<encoding media-type=\"application/x-protostream\"/>" +
            "</distributed-cache>" +
            "</cache-container>" +
            "</infinispan>", "sessions");

        XMLStringConfiguration xmlConfig = new XMLStringConfiguration(xml);
        remoteCacheManager.administration().getOrCreateCache("sessions", xmlConfig);
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

    @Override
    public void configureExternalSessionStorage(Path jettyBase) throws Exception
    {
        Path hotRodProperties = jettyBase.resolve("resources").resolve("hotrod-client.properties");
        Files.deleteIfExists(hotRodProperties);
        try (BufferedWriter writer = Files.newBufferedWriter(hotRodProperties, StandardCharsets.UTF_8, StandardOpenOption.CREATE))
        {
            writer.write("infinispan.client.hotrod.use_auth=true");
            writer.newLine();
            writer.write("infinispan.client.hotrod.server_list=" + host + ":" + port);
            writer.newLine();
            writer.write("infinispan.client.hotrod.sasl_mechanism=DIGEST-MD5");
            writer.newLine();
            writer.write("infinispan.client.hotrod.auth_username=theuser");
            writer.newLine();
            writer.write("infinispan.client.hotrod.auth_password=foobar");
            writer.newLine();
        }
    }
}
