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

import com.github.dockerjava.api.DockerClient;
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
import org.testcontainers.containers.startupcheck.StartupCheckStrategy;
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

    private static ImageFromDockerfile getUbuntuOSImage()
    {
        return new ImageFromDSL(JETTY_REGISTRY + "jetty-sh:ubuntu-22.04")
            .withDockerfileFromBuilder(builder ->
                builder
                    .from("ubuntu:22.04")
                    .run("apt update ; " +
                        "apt -y upgrade ; " +
                        "apt install -y openjdk-17-jdk-headless ; " +
                        "apt install -y curl vim net-tools ")
                    .env("TEST_DIR", "/var/test")
                    .env("JETTY_HOME", "$TEST_DIR/jetty-home")
                    .env("JETTY_BASE", "$TEST_DIR/jetty-base")
                    .env("PATH", "$PATH:${JETTY_HOME}/bin/")
                    .user("root")
                    // Configure /etc/default/jetty
                    .run("echo \"JETTY_HOME=${JETTY_HOME}\" > /etc/default/jetty ; " +
                        "echo \"JETTY_BASE=${JETTY_BASE}\" >> /etc/default/jetty" +
                        (DEBUG_JETTY_SH ? " ; echo \"DEBUG=0\" >> /etc/default/jetty" : ""))
                    // setup Jetty Home
                    .copy("/opt/jetty/", "${JETTY_HOME}/")
                    .env("PATH", "$PATH:${JETTY_HOME}/bin/")
                    .run("chmod ugo+x ${JETTY_HOME}/bin/jetty.sh")
                    .build())
            .withFileFromFile("/opt/jetty/", jettyHomePath.toFile());
    }

    /**
     * A docker image with no JETTY_USER set, everything executes as `root`.
     */
    private static ImageFromDockerfile buildWithRootNoUserChange(ImageFromDockerfile osImage)
    {
        String name = osImage.get();
        System.out.println("buildWithRootNoUserChange: name=" + name);

        return new ImageFromDSL(osImage.getDockerImageName() + "-no-user-change")
            .withDockerfileFromBuilder(builder ->
                builder
                    .from(osImage.getDockerImageName())
                    .label(JETTY_MODE, MODE_ROOT_ONLY)
                    .run("mkdir -p ${JETTY_BASE} ; " +
                        "chmod u+x ${JETTY_HOME}/bin/jetty.sh ; " +
                        "chmod a+w ${JETTY_BASE}")
                    // Configure Jetty Base
                    .workDir("${JETTY_BASE}")
                    .build());
    }

    /**
     * A docker image with JETTY_USER set to id `jetty`.
     * JETTY_HOME is owned by `root`.
     * JETTY_BASE is owned by `jetty`
     */
    private static ImageFromDockerfile buildWithUserChangeToJettyId(ImageFromDockerfile osImage)
    {
        String name = osImage.get();
        System.out.println("buildWithUserChangeToJettyUser: name=" + name);

        return new ImageFromDSL(osImage.getDockerImageName() + "-user-change")
            .withDockerfileFromBuilder(builder ->
                builder
                    .from(osImage.getDockerImageName())
                    .label(JETTY_MODE, MODE_USER_CHANGE)
                    // setup "jetty" user and Jetty Base directory
                    .run("chmod ugo+x ${JETTY_HOME}/bin/jetty.sh ; " +
                        "mkdir -p ${JETTY_BASE} ; " +
                        "useradd --home-dir=${JETTY_BASE} --shell=/bin/bash jetty ; " +
                        "chown jetty:jetty ${JETTY_BASE} ; " +
                        "chmod a+w ${JETTY_BASE} ; " +
                        "echo \"JETTY_USER=jetty\" > /etc/default/jetty") // user change
                    .user("jetty")
                    // Configure Jetty Base
                    .workDir("${JETTY_BASE}")
                    .build());
    }

    public static Stream<Arguments> jettyImages()
    {
        List<Arguments> images = new ArrayList<>();

        List<ImageFromDockerfile> osImages = List.of(
            getUbuntuOSImage()
        );

        for (ImageFromDockerfile osImage : osImages)
        {
            images.add(Arguments.of(buildWithRootNoUserChange(osImage)));
            images.add(Arguments.of(buildWithUserChangeToJettyId(osImage)));
        }

        return images.stream();
    }

    @ParameterizedTest
    @MethodSource("jettyImages")
    public void testStartStopBasicJettyBase(ImageFromDockerfile jettyImage) throws Exception
    {
        String name = jettyImage.get();
        System.out.println("testStartStopBasicJettyBase: name=" + name);

        ImageFromDockerfile image = new ImageFromDSL(jettyImage.getDockerImageName() + "-basic-base")
            .withDockerfileFromBuilder(builder ->
                builder
                    .from(jettyImage.getDockerImageName())
                    .label(JETTY_BASE_MODE, "basic")
                    // Create a basic configuration of jetty-base
                    .run("java -jar ${JETTY_HOME}/start.jar --add-modules=http,deploy")
                    .build());

        try (GenericContainer<?> genericContainer = new GenericContainer<>(image))
        {
            genericContainer.setWaitStrategy(new ShellStrategy().withCommand("id"));

            genericContainer.withExposedPorts(8080) // jetty
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

        ImageFromDockerfile image = new ImageFromDSL(jettyImage.getDockerImageName() + "-complex-base")
            .withDockerfileFromBuilder(builder ->
                builder
                    .from(jettyImage.getDockerImageName())
                    .label(JETTY_BASE_MODE, "basic")
                    // Create a basic configuration of jetty-base
                    .run("java -jar ${JETTY_HOME}/start.jar --add-modules=http,deploy")
                    .copy("/tests/bases-with-spaces/", "${JETTY_BASE}/")
                    .build())
            .withFileFromFile("/tests/bases-with-spaces/", basesWithSpaces.toFile());

        try (GenericContainer<?> genericContainer = new GenericContainer<>(image))
        {
            genericContainer.setWaitStrategy(new ShellStrategy().withCommand("id"));

            genericContainer.withExposedPorts(8080) // jetty
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

    static class NoopStartupCheckStrategy extends StartupCheckStrategy
    {
        @Override
        public StartupStatus checkStartupState(DockerClient dockerClient, String containerId)
        {
            return StartupStatus.SUCCESSFUL;
        }
    }

}
