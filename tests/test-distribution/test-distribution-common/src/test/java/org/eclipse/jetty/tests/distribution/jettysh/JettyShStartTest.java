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
import java.nio.channels.AsynchronousCloseException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import org.awaitility.Awaitility;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.distribution.AbstractJettyHomeTest;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.startupcheck.IsRunningStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.ShellStrategy;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test of jetty-home/bin/jetty.sh as generic start mechanism.
 */
public class JettyShStartTest extends AbstractJettyHomeTest
{
    private static final Logger LOG = LoggerFactory.getLogger(JettyShStartTest.class);

    public static Stream<Arguments> jettyImages()
    {
        List<ImageFromDSL> images = new ArrayList<>();

        // Loop through all OS images
        for (ImageOS osImage : List.of(new ImageOSUbuntuJammyJDK17(), new ImageOSAmazonCorretto17()))
        {
            // Establish user Images based on OS Image
            List<ImageFromDSL> userImages = new ArrayList<>();
            userImages.add(new ImageUserRoot(osImage));
            userImages.add(new ImageUserChange(osImage));

            // Loop through user Images to establish various JETTY_BASE configurations
            for (ImageFromDSL userImage : userImages)
            {
                // Basic JETTY_BASE
                images.add(new ImageFromDSL(userImage, "base-basic", builder ->
                    builder
                        .from(userImage.getDockerImageName())
                        // Create a basic configuration of jetty-base
                        .run("java -jar ${JETTY_HOME}/start.jar --add-modules=http,ee10-deploy")
                        .build()));

                // Complex JETTY_BASE with spaces
                Path basesWithSpaces = MavenPaths.findTestResourceDir("bases/spaces-with-conf");
                ImageFromDSL baseComplexWithSpacesImage = new ImageFromDSL(userImage, "base-complex-with-spaces", builder ->
                {
                    builder
                        .from(userImage.getDockerImageName())
                        // Create a basic configuration of jetty-base
                        .run("java -jar ${JETTY_HOME}/start.jar --add-modules=http,ee10-deploy")
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
        ensureParentImagesExist(jettyImage);

        try (GenericContainer<?> genericContainer = new GenericContainer<>(jettyImage))
        {
            genericContainer.setWaitStrategy(new ShellStrategy().withCommand("id"));

            genericContainer.withExposedPorts(80, 8080) // jetty
                .withCommand("/bin/sh", "-c", "while true; do pwd | nc -l -p 80; done")
                .withStartupAttempts(2)
                .withLogConsumer(new Slf4jLogConsumer(LOG))
                .withStartupCheckStrategy(new IsRunningStartupCheckStrategy())
                .start();

            LOG.info("Started: " + jettyImage.getDockerImageName());

            // Seed a known secret in start.d so it can be asserted present in the
            // argfile and absent from every process command line.
            injectSecretMarker(genericContainer);

            // System.err.println("== jetty.sh start ==");
            Container.ExecResult result = genericContainer.execInContainer("/var/test/jetty-home/bin/jetty.sh", "start");
            assertThat(asReason(result, "jetty.sh start"), result.getExitCode(), is(0));
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
            if (LOG.isDebugEnabled())
                LOG.debug("Container URI Root: {}", containerUriRoot);

            // System.err.println("== Attempt GET request to service ==");
            ContentResponse response = client.GET(containerUriRoot);
            assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus(), new ResponseDetails(response));
            assertThat(response.getContentAsString(), containsString("Powered by Eclipse Jetty:// Server"));

            // System.err.println("== jetty.sh status (should be running) ==");
            result = genericContainer.execInContainer("/var/test/jetty-home/bin/jetty.sh", "status");
            assertThat(asReason(result, "jetty.sh status"), result.getExitCode(), is(0));
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(result::getStdout,
                containsString("Jetty running pid"));

            // Issue #14005: while Jetty runs, the fully-expanded configuration (which can
            // include secrets such as start.d/ssl.ini passwords) lives in a 0600 java
            // @argument-file and must NOT appear on the process command line (ps / /proc).
            assertArgFileHidesConfiguration(genericContainer);

            // System.err.println("== jetty.sh stop ==");
            result = genericContainer.execInContainer("/var/test/jetty-home/bin/jetty.sh", "stop");
            assertThat(asReason(result, "jetty.sh stop"), result.getExitCode(), is(0));
            /* Looking for output from jetty.sh indicating a stopped jetty.
             * STDOUT Example 1
             * ----
             * Stopping Jetty: OK\n
             * ----
             * STOUT Example 2
             * ----
             * Stopping Jetty: .Killed 12345\n
             * OK\n
             * ----
             */
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(result::getStdout,
                matchesRegex("Stopping Jetty: .*[\n]?OK[\n]"));

            // System.err.println("== jetty.sh status (should be stopped) ==");
            result = genericContainer.execInContainer("/var/test/jetty-home/bin/jetty.sh", "status");
            assertThat(asReason(result, "jetty.sh status"), result.getExitCode(), is(1));
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(result::getStdout,
                containsString("Jetty NOT running"));

            // Issue #14005: the argfile (which may contain secrets) is removed on stop.
            Container.ExecResult argFileAfterStop = genericContainer.execInContainer("test", "-f", JETTY_ARGFILE);
            assertThat("argfile should be removed on stop: " + JETTY_ARGFILE, argFileAfterStop.getExitCode(), is(1));

            // System.err.println("== Attempt GET request to non-existent service ==");
            client.setConnectTimeout(1000);
            Exception failedGetException = assertThrows(Exception.class, () -> client.GET(containerUriRoot));
            // GET failure can result in either exception below (which one is based on timing / race)
            assertThat(failedGetException, anyOf(
                instanceOf(ExecutionException.class),
                instanceOf(AsynchronousCloseException.class))
            );
        }
    }

    @ParameterizedTest
    @MethodSource("jettyImages")
    public void testStartRestartStopJettyBase(ImageFromDSL jettyImage) throws Exception
    {
        ensureParentImagesExist(jettyImage);

        try (GenericContainer<?> genericContainer = new GenericContainer<>(jettyImage))
        {
            genericContainer.setWaitStrategy(new ShellStrategy().withCommand("id"));

            genericContainer.withExposedPorts(80, 8080) // jetty
                .withCommand("/bin/sh", "-c", "while true; do pwd | nc -l -p 80; done")
                .withStartupAttempts(2)
                .withStartupCheckStrategy(new IsRunningStartupCheckStrategy())
                .start();

            LOG.info("Started: " + jettyImage.getDockerImageName());

            // System.err.println("== jetty.sh start ==");
            Container.ExecResult result = genericContainer.execInContainer("/var/test/jetty-home/bin/jetty.sh", "start");
            assertThat(asReason(result, "jetty.sh start"), result.getExitCode(), is(0));
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
            if (LOG.isDebugEnabled())
                LOG.debug("Container URI Root: {}", containerUriRoot);

            // System.err.println("== Attempt GET request to service ==");
            ContentResponse response = client.GET(containerUriRoot);
            assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus(), new ResponseDetails(response));
            assertThat(response.getContentAsString(), containsString("Powered by Eclipse Jetty:// Server"));

            // System.err.println("== jetty.sh status (should be running) ==");
            result = genericContainer.execInContainer("/var/test/jetty-home/bin/jetty.sh", "status");
            assertThat(asReason(result, "jetty.sh status"), result.getExitCode(), is(0));
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(result::getStdout,
                containsString("Jetty running pid"));

            // System.err.println("== jetty.sh restart ==");
            result = genericContainer.execInContainer("/var/test/jetty-home/bin/jetty.sh", "restart");
            assertThat(asReason(result, "jetty.sh restart"), result.getExitCode(), is(0));
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(result::getStdout,
                allOf(
                    containsString("Starting Jetty:"),
                    containsString("\nOK ")
                ));

            // System.err.println("== Attempt GET request to service ==");
            response = client.GET(containerUriRoot);
            assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus(), new ResponseDetails(response));
            assertThat(response.getContentAsString(), containsString("Powered by Eclipse Jetty:// Server"));

            // System.err.println("== jetty.sh status (should be running) ==");
            result = genericContainer.execInContainer("/var/test/jetty-home/bin/jetty.sh", "status");
            assertThat(asReason(result, "jetty.sh status"), result.getExitCode(), is(0));
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(result::getStdout,
                containsString("Jetty running pid"));

            // System.err.println("== jetty.sh stop ==");
            result = genericContainer.execInContainer("/var/test/jetty-home/bin/jetty.sh", "stop");
            assertThat(asReason(result, "jetty.sh stop"), result.getExitCode(), is(0));
            /* Looking for output from jetty.sh indicating a stopped jetty.
             * STDOUT Example 1
             * ----
             * Stopping Jetty: OK\n
             * ----
             * STOUT Example 2
             * ----
             * Stopping Jetty: .Killed 12345\n
             * OK\n
             * ----
             */
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(result::getStdout,
                matchesRegex("Stopping Jetty: .*[\n]?OK[\n]"));

            // System.err.println("== jetty.sh status (should be stopped) ==");
            result = genericContainer.execInContainer("/var/test/jetty-home/bin/jetty.sh", "status");
            assertThat(asReason(result, "jetty.sh status"), result.getExitCode(), is(1));
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(result::getStdout,
                containsString("Jetty NOT running"));

            // System.err.println("== Attempt GET request to non-existent service ==");
            client.setConnectTimeout(1000);
            Exception failedGetException = assertThrows(Exception.class, () -> client.GET(containerUriRoot));
            // GET failure can result in either exception below (which one is based on timing / race)
            assertThat(failedGetException, anyOf(
                instanceOf(ExecutionException.class),
                instanceOf(AsynchronousCloseException.class))
            );
        }
    }

    /**
     * When {@code jetty.sh} runs as {@code root} with {@code JETTY_USER} set, it
     * generates the {@code @argument-file} as root and must {@code chown} it to {@code JETTY_USER}
     * (keeping mode 0600) so the dropped-to user can read it at launch. The other images never hit
     * this path: {@link ImageUserRoot} has no {@code JETTY_USER}, and {@link ImageUserChange} runs
     * the whole script as {@code jetty}.
     * <p>
     * Ubuntu only: it has {@code start-stop-daemon} (so the launch uses {@code --chuid jetty}). The
     * Amazon Corretto image is too minimal for the privilege-drop fallback (no {@code start-stop-daemon}
     * and no {@code su}).
     */
    @Test
    public void testArgFileOwnedByJettyUserOnPrivilegeDrop() throws Exception
    {
        ImageOS osImage = new ImageOSUbuntuJammyJDK17();
        ImageFromDSL userImage = new ImageUserRootJettyUser(osImage);
        ImageFromDSL jettyImage = new ImageFromDSL(userImage, "base-basic-root-jetty-user", builder ->
            builder
                .from(userImage.getDockerImageName())
                .run("java -jar ${JETTY_HOME}/start.jar --add-modules=http,ee10-deploy")
                .build());
        ensureParentImagesExist(jettyImage);

        try (GenericContainer<?> genericContainer = new GenericContainer<>(jettyImage))
        {
            genericContainer.setWaitStrategy(new ShellStrategy().withCommand("id"));

            genericContainer.withExposedPorts(80, 8080) // jetty
                .withCommand("/bin/sh", "-c", "while true; do pwd | nc -l -p 80; done")
                .withStartupAttempts(2)
                .withLogConsumer(new Slf4jLogConsumer(LOG))
                .withStartupCheckStrategy(new IsRunningStartupCheckStrategy())
                .start();

            LOG.info("Started: " + jettyImage.getDockerImageName());

            // System.err.println("== jetty.sh start ==");
            Container.ExecResult result = genericContainer.execInContainer("/var/test/jetty-home/bin/jetty.sh", "start");
            assertThat(asReason(result, "jetty.sh start"), result.getExitCode(), is(0));
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(result::getStdout,
                allOf(
                    containsString("Starting Jetty:"),
                    containsString("\nOK ")
                ));

            // System.err.println("== jetty.sh status (should be running) ==");
            result = genericContainer.execInContainer("/var/test/jetty-home/bin/jetty.sh", "status");
            assertThat(asReason(result, "jetty.sh status"), result.getExitCode(), is(0));
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(result::getStdout,
                containsString("Jetty running pid"));

            // The argfile was created by root and handed to JETTY_USER (jetty), still mode 0600.
            Container.ExecResult ownerAndMode = genericContainer.execInContainer("stat", "-c", "%U %a", JETTY_ARGFILE);
            assertThat(asReason(ownerAndMode, "stat " + JETTY_ARGFILE), ownerAndMode.getExitCode(), is(0));
            assertThat("argfile owner and mode of " + JETTY_ARGFILE, ownerAndMode.getStdout().trim(), is("jetty 600"));

            // System.err.println("== jetty.sh stop ==");
            result = genericContainer.execInContainer("/var/test/jetty-home/bin/jetty.sh", "stop");
            assertThat(asReason(result, "jetty.sh stop"), result.getExitCode(), is(0));
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(result::getStdout,
                matchesRegex("Stopping Jetty: .*[\n]?OK[\n]"));
        }
    }

    /**
     * Issue #14005 (encoding round-trip): a non-ASCII configuration value survives jetty.sh's
     * generate -> {@code @argfile} -> launch cycle when the JVM runs under a UTF-8 locale.
     * <p>
     * jetty.sh generates and launches within one environment, so this covers the same-host case
     * only (a cross-encoding move to another machine is a documented portability caveat). Ubuntu
     * only, and {@code C.UTF-8} is built into glibc so no locale needs generating.
     */
    @Test
    public void testArgFilePreservesNonAsciiConfig() throws Exception
    {
        // café-€-naïve
        String marker = "café-€-naïve";

        ImageOS osImage = new ImageOSUbuntuJammyJDK17();
        ImageFromDSL userImage = new ImageUserRoot(osImage);
        ImageFromDSL jettyImage = new ImageFromDSL(userImage, "base-basic-nonascii", builder ->
            builder
                .from(userImage.getDockerImageName())
                .run("java -jar ${JETTY_HOME}/start.jar --add-modules=http,ee10-deploy")
                .build());
        ensureParentImagesExist(jettyImage);

        try (GenericContainer<?> genericContainer = new GenericContainer<>(jettyImage))
        {
            genericContainer.setWaitStrategy(new ShellStrategy().withCommand("id"));

            genericContainer.withExposedPorts(80, 8080) // jetty
                .withCommand("/bin/sh", "-c", "while true; do pwd | nc -l -p 80; done")
                .withStartupAttempts(2)
                .withLogConsumer(new Slf4jLogConsumer(LOG))
                .withStartupCheckStrategy(new IsRunningStartupCheckStrategy())
                .start();

            // Put a non-ASCII property value into start.d.
            Container.ExecResult inject = genericContainer.execInContainer("/bin/sh", "-c",
                "mkdir -p /var/test/jetty-base/start.d && " +
                    "printf 'test.marker=%s\\n' '" + marker + "' > /var/test/jetty-base/start.d/zzz-nonascii.ini");
            assertThat(asReason(inject, "inject non-ASCII marker"), inject.getExitCode(), is(0));

            // Start under a UTF-8 locale so the generate+launch native encoding is UTF-8.
            Container.ExecResult result = genericContainer.execInContainer("env", "LANG=C.UTF-8", "LC_ALL=C.UTF-8",
                "/var/test/jetty-home/bin/jetty.sh", "start");
            assertThat(asReason(result, "jetty.sh start"), result.getExitCode(), is(0));
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(result::getStdout,
                allOf(
                    containsString("Starting Jetty:"),
                    containsString("\nOK ")
                ));

            // The non-ASCII value is correctly encoded in the generated argfile.
            Container.ExecResult content = genericContainer.execInContainer("cat", JETTY_ARGFILE);
            assertThat(asReason(content, "cat " + JETTY_ARGFILE), content.getExitCode(), is(0));
            assertThat("argfile preserves the non-ASCII value", content.getStdout(), containsString("test.marker=" + marker));

            genericContainer.execInContainer("/var/test/jetty-home/bin/jetty.sh", "stop");
        }
    }

    private void ensureParentImagesExist(ImageFromDSL jettyImage)
    {
        // The build stack for images
        Stack<ImageFromDSL> images = new Stack<>();

        ImageFromDSL parent = jettyImage;
        while ((parent = parent.getParentImage()) != null)
        {
            images.push(parent);
        }

        // Create the images (allowing testcontainers cache to do its thing)
        while (!images.isEmpty())
        {
            ImageFromDSL image = images.pop();
            createImage(image);
        }
    }

    private void createImage(ImageFromDSL image)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Create Image: {}", image.getDockerImageName());
        try (GenericContainer<?> container = new GenericContainer<>(image))
        {
            container.start();
        }
    }

    private String asReason(Container.ExecResult result, String reason)
    {
        return String.format("%s%n--STDERR-%n%s--STDOUT--%s", reason, result.getStderr(), result.getStdout());
    }

    /**
     * Default location of the generated java @argument-file for these test images:
     * {@code NAME} defaults to {@code jetty} (the basename of {@code jetty.sh}), and
     * {@code /etc/default/jetty} sets {@code JETTY_RUN=${JETTY_BASE}=/var/test/jetty-base}.
     */
    private static final String JETTY_ARGFILE = "/var/test/jetty-base/jetty.argfile";

    /**
     * A recognizable secret value placed in a {@code start.d} ini (see
     * {@link #injectSecretMarker(GenericContainer)}) so the property can be asserted
     * directly: it must land in the {@code @argument-file} but never on a process command line.
     */
    private static final String SECRET_MARKER = "S3cr3tMarker_doNotLeak";

    /**
     * A {@code start.d} ini carrying {@link #SECRET_MARKER} as a property value. Jetty reads
     * {@code start.d/*.ini} in addition to {@code start.ini}, so this is picked up regardless of
     * which the base configuration uses, and the property is emitted into the arguments file.
     */
    private void injectSecretMarker(GenericContainer<?> container) throws Exception
    {
        Container.ExecResult result = container.execInContainer("/bin/sh", "-c",
            "mkdir -p /var/test/jetty-base/start.d && " +
                "printf '%s\\n' 'my.secret.marker=" + SECRET_MARKER + "' > /var/test/jetty-base/start.d/zzz-secret-marker.ini");
        assertThat(asReason(result, "inject secret marker"), result.getExitCode(), is(0));
    }

    /**
     * Assert the issue #14005 behavior: while Jetty is running the fully-expanded
     * configuration lives in a {@code 0600} java @argument-file and does NOT appear on the
     * process command line, so values such as keystore passwords do not leak into
     * {@code ps} / {@code /proc} output.
     */
    private void assertArgFileHidesConfiguration(GenericContainer<?> container) throws Exception
    {
        // 1. the argfile exists with restrictive 0600 permissions
        Container.ExecResult perms = container.execInContainer("stat", "-c", "%a", JETTY_ARGFILE);
        assertThat(asReason(perms, "stat " + JETTY_ARGFILE), perms.getExitCode(), is(0));
        assertThat("argfile permissions of " + JETTY_ARGFILE, perms.getStdout().trim(), is("600"));

        // 2. the expanded configuration really is in the argfile (XmlConfiguration is the main class)
        Container.ExecResult inFile = container.execInContainer("grep", "-q", "XmlConfiguration", JETTY_ARGFILE);
        assertThat(asReason(inFile, "argfile should contain the expanded configuration"), inFile.getExitCode(), is(0));

        // 3. gather every readable process command line (NUL-separated -> space-separated)
        Container.ExecResult cmdlines = container.execInContainer("/bin/sh", "-c",
            "cat /proc/[0-9]*/cmdline 2>/dev/null | tr '\\0' ' '");
        String allCmdLines = cmdlines.getStdout();

        // 3a. a java process was launched via @argfile ...
        assertThat("a process should be launched via @argfile, cmdlines=" + allCmdLines, allCmdLines,
            containsString("@" + JETTY_ARGFILE));
        // 3b. ... and the expanded configuration is NOT on any command line (the #14005 fix)
        assertThat("expanded configuration must not appear on a command line, cmdlines=" + allCmdLines, allCmdLines,
            not(containsString("XmlConfiguration")));

        // 4. the same, tested directly with a known secret placed in start.d (see injectSecretMarker):
        //    it is in the argfile but on no command line.
        Container.ExecResult secretInFile = container.execInContainer("grep", "-q", SECRET_MARKER, JETTY_ARGFILE);
        assertThat(asReason(secretInFile, "argfile should contain the start.d secret marker"), secretInFile.getExitCode(), is(0));
        assertThat("secret marker from start.d must not appear on a command line, cmdlines=" + allCmdLines, allCmdLines,
            not(containsString(SECRET_MARKER)));
    }
}
