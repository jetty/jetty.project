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

package org.eclipse.jetty.tests.distribution.start;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

import org.awaitility.Awaitility;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.tests.distribution.AbstractJettyHomeTest;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
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
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the <em>forked</em> start.jar path: {@code java -jar start.jar --exec ...}, where
 * {@code Main} forks a second JVM and launches it via a {@code java @argument-file}
 * ({@code Main.writeExecArgFile}). This is distinct from {@code jetty.sh}, which flattens the
 * configuration through {@code --arglines} and launches a plain {@code java @jetty.argfile} (so it
 * never reaches the fork path) -- that is covered by {@code JettyShStartTest}.
 * <p>
 * The focus is the encoding round-trip of a non-ASCII core-environment property value. The core
 * properties are passed to the forked child through a temp {@code .properties} file
 * ({@code Properties.store}/{@code load}, ISO-8859-1 + {@code \\uXXXX} escapes), so a value such as
 * {@code café-€-naïve-中文密码} round-trips losslessly under any locale. Were the properties inlined as
 * {@code key=value} lines into the argument file instead, they would be written in the platform
 * native encoding (the {@code native.encoding} property) and corrupted to {@code caf?-?-na?ve-????}
 * under a non-UTF-8 locale ({@code LANG=C}/POSIX); this test guards against that.
 * <p>
 * Observation is end-to-end: a {@link org.eclipse.jetty.server.handler.DumpHandler} is added at
 * {@code /dump} by a top-level {@code etc/deploy-dump.xml}, with its label set from the core-env
 * property {@code <Property name="test.marker"/>} and its response written as UTF-8. Because that XML
 * is processed by {@code XmlConfiguration.main} in the forked child with the core-env properties, the
 * echoed label is exactly the value the fork delivered. The {@code .ini} carrying the value is read
 * by start.jar as UTF-8 regardless of locale ({@code TextFile}), so the only encoding variable
 * exercised is the fork's property delivery.
 * <p>
 * Every locale case asserts the value reaches the child intact. The {@code default} case (these
 * images default to {@code LANG=C.UTF-8}) and the explicit {@code C.UTF-8} case are UTF-8; the
 * {@code C} case has an ASCII native encoding, which is where inlining would corrupt the value.
 * <p>
 * Both JDK images (straddling JEP 400) are run: the fork path's property delivery is locale-driven
 * (the native encoding, {@code native.encoding}), which JEP 400 did <em>not</em> change, so the two
 * JDK columns behave identically; the matrix documents that invariance.
 */
public class JettyStartForkedModeTest extends AbstractJettyHomeTest
{
    private static final Logger LOG = LoggerFactory.getLogger(JettyStartForkedModeTest.class);

    // café-€-naïve-中文密码 : é/ï are Latin-1, € is a symbol, 中文密码 (Chinese "Chinese password") is
    // well outside Latin-1. None survive an ASCII (LANG=C) native encoding.
    private static final String MARKER_VALUE = "café-€-naïve-中文密码";
    private static final String MARKER_KEY = "test.marker";

    private static final String JETTY_HOME = "/opt/jetty/jetty-home";
    private static final String JETTY_BASE = "/var/test/jetty-base";
    // A jetty.base under a Chinese-named directory, to exercise the native encoding's filename-path
    // domain (non-ASCII filesystem paths).
    private static final String CHINESE_BASE = "/var/test/中文/jetty-base";
    // The forked process' console log, always at an ASCII path (even for the Chinese-base case).
    private static final String FORK_LOG = "/var/test/fork.log";

    // Pinned image versions (not floating ":17"/":25") so the test is stable when the rolling tags
    // move. 17 matches ImageOSAmazonCorretto17.
    private static final String JDK17_IMAGE = "amazoncorretto:17.0.20-al2023";
    private static final String JDK25_IMAGE = "amazoncorretto:25.0.4-al2023";

    // A top-level etc XML that adds a DumpHandler at /dump whose label is the core-env property
    // test.marker (so it travels through the fork argfile), writing the response as UTF-8 so a
    // non-ASCII label survives the HTTP response leg. Modeled on jetty-core-demo-handler's
    // demo-handler.xml. DumpHandler is a core jetty-server class, so no extra [lib] is needed.
    private static final String DEPLOY_DUMP_XML =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure_10_0.dtd">
        <Configure id="Contexts" class="org.eclipse.jetty.server.handler.ContextHandlerCollection">
          <Call name="addHandler">
            <Arg>
              <New class="org.eclipse.jetty.server.handler.ContextHandler">
                <Set name="contextPath">/dump</Set>
                <Set name="handler">
                  <New class="org.eclipse.jetty.server.handler.DumpHandler">
                    <Set name="label"><Property name="test.marker" default="(test.marker unset)"/></Set>
                    <Set name="responseEncoding">
                      <Get class="java.nio.charset.StandardCharsets" name="UTF_8"/>
                    </Set>
                  </New>
                </Set>
              </New>
            </Arg>
          </Call>
        </Configure>
        """;

    /**
     * (image, locale label, LANG value): {@code null} LANG uses the image default, which for these
     * images is {@code C.UTF-8} (so the native encoding is UTF-8); only the explicit
     * {@code LANG=C} gives an ASCII native encoding.
     */
    public static Stream<Arguments> localeMatrix()
    {
        return Stream.of(JDK17_IMAGE, JDK25_IMAGE).flatMap(image -> Stream.of(
            Arguments.of(image, "default", null),
            Arguments.of(image, "C", "C"),            // ASCII native encoding
            Arguments.of(image, "C.UTF-8", "C.UTF-8") // positive control
        ));
    }

    public static Stream<Arguments> jdkImages()
    {
        return Stream.of(Arguments.of(JDK17_IMAGE), Arguments.of(JDK25_IMAGE));
    }

    /**
     * The forked child must echo the non-ASCII core-env property intact at {@code /dump}, for every
     * locale including the ASCII {@code LANG=C}.
     */
    @ParameterizedTest(name = "{0} [LANG={1}]")
    @MethodSource("localeMatrix")
    public void testForkedPropertyEchoedOverHttp(String image, String localeLabel, String lang) throws Exception
    {
        try (GenericContainer<?> container = startContainer(image))
        {
            createBase(container, JETTY_BASE, null, "http");
            deployDumpHandler(container, JETTY_BASE);

            launchFork(container, JETTY_BASE, lang, "jetty.http.port=8080");

            String echoed = dumpLabel(container);
            assertForkTaken(container);
            assertEquals(MARKER_VALUE, echoed, diag(echoed, localeLabel, image));

            stopFork(container);
        }
    }

    /**
     * The forked server runs from a jetty.base under a Chinese-named directory and must start and echo
     * the property. Run under {@code C.UTF-8} only: a non-ASCII filesystem path is unusable under an
     * ASCII native encoding (a JDK/locale limit, not a Jetty bug), so this is a positive test of the
     * supported configuration on both JDKs.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("jdkImages")
    public void testForkedServerFromChineseBasePath(String image) throws Exception
    {
        try (GenericContainer<?> container = startContainer(image))
        {
            createBase(container, CHINESE_BASE, "C.UTF-8", "http");
            deployDumpHandler(container, CHINESE_BASE);

            launchFork(container, CHINESE_BASE, "C.UTF-8", "jetty.http.port=8080");

            String echoed = dumpLabel(container);
            assertForkTaken(container);
            assertEquals(MARKER_VALUE, echoed,
                "Forked server from a Chinese-named base path did not echo " + MARKER_KEY + " on " + image);

            stopFork(container);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private GenericContainer<?> startContainer(String image)
    {
        Path jettyHomeDir = resolveJettyHome();
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(image))
            .withCopyFileToContainer(MountableFile.forHostPath(jettyHomeDir), JETTY_HOME)
            // Keep the container alive so we can drive it with execInContainer; the forking start.jar
            // runs as a background process inside it.
            .withCommand("tail", "-f", "/dev/null")
            .withExposedPorts(8080)
            .withStartupCheckStrategy(new IsRunningStartupCheckStrategy())
            // The server binds 8080 only later (when the forked jetty starts, driven by
            // execInContainer), so don't let testcontainers' default strategy wait on that port.
            .waitingFor(new ShellStrategy().withCommand("id"))
            .withLogConsumer(new Slf4jLogConsumer(LOG));
        container.start();
        LOG.info("Started {}", image);
        return container;
    }

    private void createBase(GenericContainer<?> container, String baseDir, String lang, String modules) throws Exception
    {
        String env = (lang == null) ? "" : ("export LANG=" + lang + " LC_ALL=" + lang + " LANGUAGE=; ");
        Container.ExecResult result = exec(container, "create base " + baseDir, "sh", "-c",
            "mkdir -p '" + baseDir + "'/start.d '" + baseDir + "'/etc && cd '" + baseDir + "' && " + env +
                "java -jar " + JETTY_HOME + "/start.jar --add-modules=" + modules);
        assertEquals(0, result.getExitCode(), "create base failed: " + result.getStdout() + result.getStderr());
    }

    /**
     * Add the DumpHandler (via a top-level etc XML referenced from start.d) and the non-ASCII
     * property. The ini is written with guaranteed UTF-8 bytes (never via an in-container shell
     * printf, whose bytes depend on the locale).
     */
    private void deployDumpHandler(GenericContainer<?> container, String baseDir) throws Exception
    {
        writeFileToContainer(container, baseDir + "/etc/deploy-dump.xml", DEPLOY_DUMP_XML);
        writeFileToContainer(container, baseDir + "/start.d/dump-handler.ini", "etc/deploy-dump.xml\n");
        writeFileToContainer(container, baseDir + "/start.d/zzz-nonascii.ini", MARKER_KEY + "=" + MARKER_VALUE + "\n");
    }

    /** Write UTF-8 {@code content} to {@code containerPath} (bytes guaranteed, independent of locale). */
    private void writeFileToContainer(GenericContainer<?> container, String containerPath, String content) throws Exception
    {
        Path tmp = Files.createTempFile("jetty-forked-", ".tmp");
        try
        {
            Files.write(tmp, content.getBytes(UTF_8));
            container.copyFileToContainer(MountableFile.forHostPath(tmp), containerPath);
        }
        finally
        {
            Files.deleteIfExists(tmp);
        }
    }

    private void launchFork(GenericContainer<?> container, String baseDir, String lang, String startArgs) throws Exception
    {
        String env = (lang == null) ? "" : ("export LANG=" + lang + " LC_ALL=" + lang + " LANGUAGE=; ");
        exec(container, "fork launch", "sh", "-c",
            "cd '" + baseDir + "' && " + env +
                "nohup java -jar " + JETTY_HOME + "/start.jar --exec " + startArgs + " > " + FORK_LOG + " 2>&1 & echo launched");
    }

    /** GET {@code /dump} (awaiting the forked server) and return the echoed {@code <h1>} label. */
    private String dumpLabel(GenericContainer<?> container) throws Exception
    {
        URI uri = URI.create("http://" + container.getHost() + ":" + container.getMappedPort(8080) + "/dump");
        startHttpClient();
        try
        {
            Awaitility.await("forked server serves /dump")
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .until(() -> client.GET(uri).getStatus(), is(200));
        }
        catch (Exception timeout)
        {
            Container.ExecResult log = exec(container, "fork.log", "cat", FORK_LOG);
            throw new AssertionError("Forked server never served /dump.\n--- " + FORK_LOG + " ---\n" + log.getStdout(), timeout);
        }

        ContentResponse response = client.GET(uri);
        // DumpHandler wrote the body in the configured response encoding (UTF-8); decode explicitly
        // (the response declares no charset, so getContentAsString() would use a default).
        String body = new String(response.getContent(), UTF_8);
        return substringBetween(body, "<h1>", "</h1>");
    }

    private void assertForkTaken(GenericContainer<?> container) throws Exception
    {
        Container.ExecResult forkLog = exec(container, "read fork.log", "cat", FORK_LOG);
        assertThat("the exec/fork path should have been taken\n" + forkLog.getStdout(),
            forkLog.getStdout(), containsString("Forking second JVM"));
    }

    private void stopFork(GenericContainer<?> container) throws Exception
    {
        exec(container, "stop forked jetty", "sh", "-c", "pkill -f start.jar || true");
    }

    private static String substringBetween(String s, String open, String close)
    {
        int a = s.indexOf(open);
        if (a < 0)
            return null;
        a += open.length();
        int b = s.indexOf(close, a);
        return (b < 0) ? null : s.substring(a, b);
    }

    private static String diag(String echoed, String localeLabel, String image)
    {
        return "Forked child echoed '" + echoed + "' for " + MARKER_KEY + " under LANG=" + localeLabel + " on " + image +
            ". A corrupted value (e.g. containing '?') means the core properties were inlined into the " +
            "native-encoding @argument-file instead of being passed through a temp .properties file, so a " +
            "non-ASCII value did not survive a non-UTF-8 locale.";
    }

    private static Container.ExecResult exec(GenericContainer<?> container, String reason, String... command) throws Exception
    {
        Container.ExecResult result = container.execInContainer(command);
        if (LOG.isDebugEnabled())
            LOG.debug("exec [{}] exit={} stdout=[{}] stderr=[{}]", reason, result.getExitCode(), result.getStdout(), result.getStderr());
        return result;
    }

    private static Path resolveJettyHome()
    {
        try
        {
            JettyHomeTester tester = JettyHomeTester.Builder.newInstance()
                .jettyVersion(System.getProperty("jettyVersion"))
                .mavenLocalRepository(System.getProperty("mavenRepoPath"))
                .build();
            return tester.getJettyHome();
        }
        catch (Exception e)
        {
            throw new RuntimeException("Unable to resolve unpacked JETTY_HOME", e);
        }
    }
}
