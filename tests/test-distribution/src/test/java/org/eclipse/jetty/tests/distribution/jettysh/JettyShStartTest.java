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
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.IsRunningStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.ShellStrategy;

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
    public static Stream<Arguments> jettyImages()
    {
        List<ImageFromDSL> images = new ArrayList<>();

        // Loop through all OS images
        for (ImageOS osImage : List.of(new ImageOSUbuntuJammyLTS()))
        {
            // Establish user Images based on OS Image
            List<ImageFromDSL> userImages = new ArrayList<>();
            userImages.add(new ImageUserRootOnly(osImage));
            userImages.add(new ImageUserChange(osImage));

            // Loop through user Images to establish various JETTY_BASE configurations
            for (ImageFromDSL userImage: userImages)
            {
                // Basic JETTY_BASE
                images.add(new ImageFromDSL(userImage, "base-basic", builder ->
                    builder
                        .from(userImage.getDockerImageName())
                        // Create a basic configuration of jetty-base
                        .run("java -jar ${JETTY_HOME}/start.jar --add-modules=http,deploy")
                        .build()));

                // Complex JETTY_BASE with spaces
                Path basesWithSpaces = MavenPaths.findTestResourceDir("bases/spaces-with-conf");
                ImageFromDSL baseComplexWithSpacesImage = new ImageFromDSL(userImage, "base-complex-with-spaces", builder ->
                {
                    builder
                        .from(userImage.getDockerImageName())
                        // Create a basic configuration of jetty-base
                        .run("java -jar ${JETTY_HOME}/start.jar --add-modules=http,deploy")
                        .copy("/tests/bases-with-spaces/", "${JETTY_BASE}/");
                    if (userImage instanceof ImageUserChange)
                    {
                        // Make sure we change the ownership of JETTY_BASE if we are testing a user change mode
                        builder.user("root")
                            .run("chown -R jetty:jetty $JETTY_BASE")
                            .user("jetty");
                    }
                    builder.build();
                });
                baseComplexWithSpacesImage.withFileFromFile("/tests/bases-with-spaces/", basesWithSpaces.toFile());
                images.add(baseComplexWithSpacesImage);
            }
        }

        return images.stream().map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("jettyImages")
    public void testStartStopJettyBase(ImageFromDSL jettyImage) throws Exception
    {
        String name = jettyImage.get();
        System.out.println("testStartStopJettyBase: name=" + name);

        try (GenericContainer<?> genericContainer = new GenericContainer<>(jettyImage))
        {
            genericContainer.setWaitStrategy(new ShellStrategy().withCommand("id"));

            genericContainer.withExposedPorts(80, 8080) // jetty
                .withCommand("/bin/sh", "-c", "while true; do pwd | nc -l -p 80; done")
                .withStartupAttempts(2)
                .withStartupCheckStrategy(new IsRunningStartupCheckStrategy())
                .start();

            System.out.println("Started: " + jettyImage.getDockerImageName());

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
}
