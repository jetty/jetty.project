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

import java.io.IOException;
import java.nio.file.Path;

import com.github.dockerjava.api.DockerClient;
import org.eclipse.jetty.tests.hometester.JettyHomeTester;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.IsRunningStartupCheckStrategy;
import org.testcontainers.containers.startupcheck.StartupCheckStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

/**
 * Test of jetty-home/bin/jetty.sh as generic start mechanism.
 */
public class JettyShStartTest
{
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

    @Test
    public void testStartStop() throws IOException, InterruptedException
    {
        ImageFromDockerfile ubuntuImg = new ImageFromDockerfile("jetty-sh:ubuntu-22.04", false)
            .withDockerfileFromBuilder(builder ->
                builder
                    .from("ubuntu:22.04")
                    .run("apt update ; " +
                        "apt -y upgrade ; " +
                        "apt install -y openjdk-17-jdk-headless ; " +
                        "apt install -y curl vim netcat ")
                    .env("TEST_DIR", "/var/test")
                    .env("JETTY_HOME", "$TEST_DIR/jetty-home")
                    .env("JETTY_BASE", "$TEST_DIR/jetty-base")
                    .user("root")
                    .copy("/opt/jetty/", "${JETTY_HOME}/")
                    //.withStatement(new MultiArgsStatement("COPY", "--from=eclipse-temurin:17.0.4.1_1-jdk", "/opt/java/openjdk", "/opt/java/jdk17"))
                    .run(
                        "chmod ugo+x ${JETTY_HOME}/bin/jetty.sh ; " +
                        "mkdir -p ${JETTY_BASE} ; " +
                        "useradd --home-dir=${JETTY_BASE} --shell=/bin/bash jetty ; " +
                        "chown jetty:jetty ${JETTY_BASE} ; " +
                        "chmod a+w ${JETTY_BASE}")
                    .user("jetty")
                    .workDir("${JETTY_BASE}")
                    //.env("JAVA_HOME", "/opt/java/jdk17")
                    .run("java -jar ${JETTY_HOME}/start.jar --add-modules=http,deploy")
                    // .cmd("${JETTY_HOME}/bin/jetty.sh start")
                    //.cmd("bash")
                    .env("PATH", "$PATH:/var/test/jetty-home/bin/")
                    .entryPoint("/var/test/jetty-home/bin/jetty.sh", "start")
                    .build())
            .withFileFromFile("/opt/jetty/", jettyHomePath.toFile());

        try (GenericContainer<?> genericContainer = new GenericContainer<>(ubuntuImg))
        {
            genericContainer.withExposedPorts(8080)
                    //.withCommand("/var/test/jetty-home/bin/jetty.sh", "status")
                    //.withCommand("java", "-jar", "/var/test/jetty-home/start.jar", "--list-config")
                // .waitingFor(new LogMessageWaitStrategy().withRegEx(".*Starting Jetty:.* started.*"))
                // .waitingFor(new LogMessageWaitStrategy().withRegEx(":~$"))
                .withStartupCheckStrategy(new IsRunningStartupCheckStrategy())
                //.withStartupCheckStrategy(new OneShotStartupCheckStrategy())
                //.withStartupCheckStrategy(new TrueStartupCheckStrategy())
                // .withStartupCheckStrategy(new OneShotStartupCheckStrategy())
                .start();

            String listing = genericContainer.getLogs();
            System.err.println("### Logs");
            System.err.println(listing);
            // assertThat(listing, containsString("oejs.Server:main: jetty-10."));

            // System.err.println("== start.jar --list-config ==");
            //execCommand(genericContainer, "java", "-jar", "/var/test/jetty-home/start.jar", "--list-config");
            System.err.println("== jetty.sh status ==");
            String stdOut = execCommand(genericContainer, "jetty.sh", "status");
            assertThat(stdOut, containsString("Jetty running pid"));
        }
    }

    private static class TrueStartupCheckStrategy extends StartupCheckStrategy
    {
        @Override
        public StartupStatus checkStartupState(DockerClient dockerClient, String containerId)
        {
            return StartupStatus.SUCCESSFUL;
        }
    }

    private String execCommand(GenericContainer<?> genericContainer, String... command) throws IOException, InterruptedException
    {
        Container.ExecResult result = genericContainer.execInContainer(command);
        System.err.println("Exit code: " + result.getExitCode());
        System.err.println("StdOut: " + result.getStdout());
        System.err.println("StdErr: " + result.getStderr());
        return result.getStdout();
    }
}
