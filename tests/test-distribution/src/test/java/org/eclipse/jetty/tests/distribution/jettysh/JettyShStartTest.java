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

package org.eclipse.jetty.tests.distribution.jettysh;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.awaitility.Awaitility;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.distribution.AbstractJettyHomeTest;
import org.eclipse.jetty.tests.hometester.JettyHomeTester;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.IsRunningStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.ShellStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test of jetty-home/bin/jetty.sh as generic start mechanism.
 */
public class JettyShStartTest extends AbstractJettyHomeTest
{
    private static final String JETTY_REGISTRY = "registry.jetty.org/";

    private static final String JETTY_MODE = "jetty-mode";
    private static final String MODE_USER_CHANGE = "user-change";
    private static final String MODE_ROOT_ONLY = "root-only";
    private static final String JETTY_BASE_MODE = "jetty-base-mode";
    private static final boolean DEBUG_JETTY_SH = false;

    private static Path jettyHomePath;

    @BeforeAll
    public static void initJettyHome() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester homeTester = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();
        jettyHomePath = homeTester.getJettyHome();
    }

    public static Stream<Arguments> jettyImages()
    {
        List<Arguments> images = new ArrayList<>();

        for (ImageOS osImage : List.of(new ImageOSUbuntuJammyLTS()))
        {
            images.add(Arguments.of(new ImageUserRootOnly(osImage)));
            images.add(Arguments.of(new ImageUserChange(osImage)));
        }

        return images.stream();
    }

    @ParameterizedTest
    @MethodSource("jettyImages")
    public void testStartStopBasicJettyBase(ImageFromDockerfile jettyImage) throws Exception
    {
        String name = jettyImage.get();
        System.out.println("testStartStopBasicJettyBase: name=" + name);

        ImageFromDockerfile image = new ImageFromDSL(jettyImage, "basic-base", builder ->
            builder
                .from(jettyImage.getDockerImageName())
                .label(JETTY_BASE_MODE, "basic")
                // Create a basic configuration of jetty-base
                .run("java -jar ${JETTY_HOME}/start.jar --add-modules=http,deploy")
                .build());

        try (GenericContainer<?> genericContainer = new GenericContainer<>(image))
        {
            genericContainer.setWaitStrategy(new ShellStrategy().withCommand("id"));

            genericContainer.withExposedPorts(80, 8080) // jetty
                .withCommand("/bin/sh", "-c", "while true; do pwd | nc -l -p 80; done")
                .withStartupAttempts(2)
                .withStartupCheckStrategy(new IsRunningStartupCheckStrategy())
                .start();

            System.out.println("Started: " + image.getDockerImageName());

            System.err.println("== jetty.sh start ==");
            Container.ExecResult result = genericContainer.execInContainer("/var/test/jetty-home/bin/jetty.sh", "start");
            assertThat(result.getExitCode(), is(0));
            /*
             * Example successful output
             * ----
             * STDOUT:
             * Starting Jetty: . started
             * OK Wed Oct 18 19:29:35 UTC 2023
             * ----
             */
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(result::getStdout,
                allOf(
                    containsString("Starting Jetty:"),
                    containsString("\nOK ")
                ));

            startHttpClient();

            URI containerUriRoot = URI.create("http://" + genericContainer.getHost() + ":" + genericContainer.getMappedPort(8080) + "/");
            System.err.printf("Container URI Root: %s%n", containerUriRoot);

            ContentResponse response = client.GET(containerUriRoot);
            assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus(), new ResponseDetails(response));
            assertThat(response.getContentAsString(), containsString("Powered by Eclipse Jetty:// Server"));

            System.err.println("== jetty.sh status ==");
            result = genericContainer.execInContainer("/var/test/jetty-home/bin/jetty.sh", "status");
            assertThat(result.getExitCode(), is(0));
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(result::getStdout,
                containsString("Jetty running pid"));
        }
    }

    @ParameterizedTest
    @MethodSource("jettyImages")
    public void testStartStopComplexJettyBase(ImageFromDockerfile jettyImage) throws Exception
    {
        String name = jettyImage.get();
        System.out.println("testStartStopComplexJettyBase: name=" + name);

        Path basesWithSpaces = MavenPaths.findTestResourceDir("bases/spaces-with-conf");
        System.out.println("basesWithSpaces (src): " + basesWithSpaces);

        ImageFromDockerfile image = new ImageFromDSL(jettyImage, "complex-base", builder ->
        {
            builder
                .from(jettyImage.getDockerImageName())
                .label(JETTY_BASE_MODE, "complex")
                // Create a basic configuration of jetty-base
                .run("java -jar ${JETTY_HOME}/start.jar --add-modules=http,deploy ; " +
                    "mkdir /tmp/logs")
                .copy("/tests/bases-with-spaces/", "${JETTY_BASE}/");
            if (jettyImage instanceof ImageUserChange)
            {
                builder.user("root")
                    .run("chown -R jetty:jetty $JETTY_BASE")
                    .user("jetty");
            }
            builder.build();
        }
        )
            .withFileFromFile("/tests/bases-with-spaces/", basesWithSpaces.toFile());

        try (GenericContainer<?> genericContainer = new GenericContainer<>(image))
        {
            genericContainer.setWaitStrategy(new ShellStrategy().withCommand("id"));

            genericContainer.withExposedPorts(8080) // jetty
                .withCommand("/bin/sh", "-c", "while true; do pwd | nc -l -p 80; done")
                .withStartupAttempts(2)
                .withStartupCheckStrategy(new IsRunningStartupCheckStrategy())
                .start();

            System.out.println("Started: " + image.getDockerImageName());

            System.err.println("== jetty.sh start ==");
            Container.ExecResult result = genericContainer.execInContainer("/var/test/jetty-home/bin/jetty.sh", "start");
//            assertThat(result.getExitCode(), is(0));
            /*
             * Example successful output
             * ----
             * STDOUT:
             * Starting Jetty: . started
             * OK Wed Oct 18 19:29:35 UTC 2023
             * ----
             */
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(result::getStdout,
                allOf(
                    containsString("Starting Jetty:"),
                    containsString("\nOK ")
                ));

            startHttpClient();

            URI containerUriRoot = URI.create("http://" + genericContainer.getHost() + ":" + genericContainer.getMappedPort(8080) + "/");
            System.err.printf("Container URI Root: %s%n", containerUriRoot);

            ContentResponse response = client.GET(containerUriRoot);
            assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus(), new ResponseDetails(response));
            assertThat(response.getContentAsString(), containsString("Powered by Eclipse Jetty:// Server"));

            System.err.println("== jetty.sh status ==");
            result = genericContainer.execInContainer("/var/test/jetty-home/bin/jetty.sh", "status");
            assertThat(result.getExitCode(), is(0));
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(result::getStdout,
                containsString("Jetty running pid"));
        }
    }
}
