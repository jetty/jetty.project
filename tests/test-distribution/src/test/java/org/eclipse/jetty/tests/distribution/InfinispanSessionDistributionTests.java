//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.session.infinispan.InfinispanSerializationContextInitializer;
import org.eclipse.jetty.util.IO;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.Configuration;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.commons.configuration.XMLStringConfiguration;
import org.infinispan.commons.marshall.ProtoStreamMarshaller;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *
 */
public class InfinispanSessionDistributionTests extends AbstractDistributionTest
{   
    private static final Logger INFINISPAN_LOG = LoggerFactory.getLogger("org.eclipse.jetty.tests.distribution.session.infinispan");

    @SuppressWarnings("rawtypes")
    private GenericContainer infinispan;

    private String infinispanHost;
    private int infinispanPort;

    @Test
    public void stopRestartWebappTestSessionContentSaved() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        DistributionTester distribution = DistributionTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();
        
        startExternalSessionStorage(distribution.getJettyBase());

        String[] args1 = {
            "--create-startd",
            "--approve-all-licenses",
            "--add-to-start=resources,server,http,webapp,deploy,jmx,servlet,servlets,logging-slf4j,slf4j-simple-impl,session-store-infinispan-remote,infinispan-remote-query"};

        try (DistributionTester.Run run1 = distribution.start(args1))
        {
            assertTrue(run1.awaitFor(5, TimeUnit.SECONDS));
            assertEquals(0, run1.getExitValue());

            File war = distribution.resolveArtifact("org.eclipse.jetty.tests:test-simple-session-webapp:war:" + jettyVersion);
            distribution.installWarFile(war, "test");
            
            int jettyPort = distribution.freePort();
            
            String[] argsStart = {
                "jetty.http.port=" + jettyPort
            };

            configureExternalSessionStorage(distribution.getJettyBase());
            
            try (DistributionTester.Run run2 = distribution.start(argsStart))
            {
                run2.awaitConsoleLogsFor("Started @", 10, TimeUnit.SECONDS);

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + jettyPort + "/test/session?action=CREATE");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("SESSION CREATED"));

                response = client.GET("http://localhost:" + jettyPort + "/test/session?action=READ");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("SESSION READ CHOCOLATE THE BEST:FRENCH"));
            }

            try (DistributionTester.Run run2 = distribution.start(argsStart))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started @", 10, TimeUnit.SECONDS));

                ContentResponse response = client.GET("http://localhost:" + jettyPort + "/test/session?action=READ");
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertThat(response.getContentAsString(), containsString("SESSION READ CHOCOLATE THE BEST:FRENCH"));
            }
        }
        finally
        {
            stopExternalSessionStorage();
        }
    }

    public void startExternalSessionStorage(Path jettyBase) throws Exception
    {
        String infinispanVersion = System.getProperty("infinispan.docker.image.version", "11.0.9.Final");
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
        infinispanHost = infinispan.getContainerIpAddress();
        infinispanPort = infinispan.getMappedPort(11222);

        Path resourcesDirectory = jettyBase.resolve("resources");
        if (Files.exists(resourcesDirectory))
        {
            IO.delete(resourcesDirectory.toFile());
        }

        Files.createDirectories(resourcesDirectory);
        Properties properties = new Properties();
        properties.put("infinispan.client.hotrod.server_list", infinispanHost + ":" + infinispanPort);
        properties.put("infinispan.client.hotrod.use_auth", "true");
        properties.put("infinispan.client.hotrod.sasl_mechanism", "DIGEST-MD5");
        properties.put("infinispan.client.hotrod.auth_username", "theuser");
        properties.put("infinispan.client.hotrod.auth_password", "foobar");

        Path hotrod = resourcesDirectory.resolve("hotrod-client.properties");
        Files.deleteIfExists(hotrod);
        Files.createFile(hotrod);
        try (Writer writer = Files.newBufferedWriter(hotrod))
        {
            properties.store(writer, null);
        }

        Configuration configuration = new ConfigurationBuilder().withProperties(properties)
                .addServer().host(infinispanHost).port(infinispanPort)
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
        RemoteCache<String, SessionData> cache = remoteCacheManager.administration().getOrCreateCache("sessions", xmlConfig);
    }

    public void stopExternalSessionStorage() throws Exception
    {
        infinispan.stop();
    }

    public void configureExternalSessionStorage(Path jettyBase) throws Exception
    {
        Path hotRodProperties = jettyBase.resolve("resources").resolve("hotrod-client.properties");
        Files.deleteIfExists(hotRodProperties);
        try (BufferedWriter writer = Files.newBufferedWriter(hotRodProperties, StandardCharsets.UTF_8, StandardOpenOption.CREATE))
        {
            writer.write("infinispan.client.hotrod.use_auth=true");
            writer.newLine();
            writer.write("infinispan.client.hotrod.server_list=" + infinispanHost + ":" + infinispanPort);
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
