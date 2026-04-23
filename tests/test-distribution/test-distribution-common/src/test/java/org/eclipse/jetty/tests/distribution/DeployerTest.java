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

package org.eclipse.jetty.tests.distribution;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.tests.testers.Tester;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.URIUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DeployerTest extends AbstractJettyHomeTest
{
    private static final Logger LOG = LoggerFactory.getLogger(DeployerTest.class);

    @Test
    public void testCoreDeployXmlOnly() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start(List.of("--add-modules=resources,http,core-deploy")))
        {
            assertTrue(run1.awaitForStart());
            assertEquals(0, run1.getExitValue());

            Path baseResourcePath = jettyBase.resolve("work/test");
            FS.ensureDirExists(baseResourcePath);
            Path testXml = jettyBase.resolve("webapps").resolve("test.xml");
            Files.writeString(testXml, """
                <?xml version="1.0"?>
                <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure.dtd">
                <Configure class="org.eclipse.jetty.server.handler.ContextHandler">
                  <Set name="contextPath">/test</Set>
                  <Set name="baseResourceAsPath">
                    <Call class="java.nio.file.Path" name="of">
                      <Arg>$R</Arg>
                    </Call>
                  </Set>
                  <Set name="handler">
                    <New class="org.eclipse.jetty.server.handler.ResourceHandler" />
                  </Set>
                </Configure>
                """.replace("$R", baseResourcePath.toAbsolutePath().toString()), StandardOpenOption.CREATE);

            String testFileContent = "hello";
            Files.writeString(baseResourcePath.resolve("test.txt"), testFileContent, StandardOpenOption.CREATE);

            int httpPort = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + httpPort))
            {
                assertTrue(run2.awaitForJettyStart());

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + httpPort + "/test/test.txt");
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                assertThat(response.getContentAsString(), is(testFileContent));
            }
        }
    }

    @Test
    public void testCoreDeployNominatedDirectoryStaticOnly() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start(List.of("--add-modules=resources,http,core-deploy")))
        {
            assertTrue(run1.awaitForStart());
            assertEquals(0, run1.getExitValue());

            Path nominatedDir = jettyBase.resolve("webapps").resolve("test.d");
            FS.ensureDirExists(nominatedDir);

            Path staticDir = nominatedDir.resolve("static");
            FS.ensureDirExists(staticDir);

            String testFileContent = "hello";
            Files.writeString(staticDir.resolve("test.txt"), testFileContent, StandardOpenOption.CREATE);

            int httpPort = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + httpPort))
            {
                assertTrue(run2.awaitForJettyStart());

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + httpPort + "/test/test.txt");
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                assertThat(response.getContentAsString(), is(testFileContent));
            }
        }
    }

    @Test
    public void testCoreDeployNormalDirectoryStaticOnly() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start(List.of("--add-modules=resources,http,core-deploy")))
        {
            assertTrue(run1.awaitForStart());
            assertEquals(0, run1.getExitValue());

            Path nominatedDir = jettyBase.resolve("webapps").resolve("test");
            FS.ensureDirExists(nominatedDir);

            Path staticDir = nominatedDir.resolve("static");
            FS.ensureDirExists(staticDir);

            String testFileContent = "hello";
            Files.writeString(staticDir.resolve("test.txt"), testFileContent, StandardOpenOption.CREATE);

            int httpPort = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + httpPort))
            {
                assertTrue(run2.awaitForJettyStart());

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + httpPort + "/test/test.txt");
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                assertThat(response.getContentAsString(), is(testFileContent));
            }
        }
    }

    @Test
    public void testCoreDeployCustomHandlerInClasses() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start(List.of("--add-modules=resources,http,core-deploy")))
        {
            assertTrue(run1.awaitForStart());
            assertEquals(0, run1.getExitValue());

            Path testDir = jettyBase.resolve("webapps").resolve("test");
            FS.ensureEmpty(testDir);

            Path testWebAppPath = distribution.resolveArtifact("org.eclipse.jetty.tests:jetty-core-demo-webapp:zip:core-webapp:" + jettyVersion);
            unpack(testWebAppPath, testDir);

            String testXmlStr = """
                <?xml version="1.0"?>
                <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure.dtd">
                <Configure class="org.eclipse.jetty.coreapp.CoreAppContext">
                  <Set name="contextPath">/demo</Set>
                  <Set name="displayName">Demo of Core Deploy WebApp</Set>
                </Configure>
                """;
            Files.writeString(jettyBase.resolve("webapps/test.xml"), testXmlStr);

            int httpPort = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + httpPort))
            {
                assertTrue(run2.awaitForJettyStart());

                startHttpClient();
                String uri = "http://localhost:" + httpPort + "/demo/test.txt";
                ContentResponse response = client.GET(uri);
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                assertThat(response.getContentAsString(),
                    allOf(
                        containsString("Server.info=jetty/" + Jetty.VERSION),
                        containsString("request.uri=" + uri),
                        containsString("messages.size=1"),
                        containsString("message[0]=On the other side of the screen, it all looks so easy.")
                    ));
            }
        }
    }

    private void unpack(Path srcPath, Path destPath) throws IOException
    {
        Map<String, String> env = new HashMap<>();

        URI jarUri = URIUtil.uriJarPrefix(srcPath.toUri(), "!/");
        try (FileSystem zipfs = FileSystems.newFileSystem(jarUri, env))
        {
            Path root = zipfs.getPath("/");
            IO.copyDir(root, destPath);
        }
    }

    @Test
    public void testStaticDeployDirectory() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start(List.of("--add-modules=resources,http,static-deploy")))
        {
            assertTrue(run1.awaitForStart());
            assertEquals(0, run1.getExitValue());

            Path staticDir = jettyBase.resolve("webapps/test");
            FS.ensureDirExists(staticDir);

            String testFileContent = "hello";
            Files.writeString(staticDir.resolve("test.txt"), testFileContent, StandardOpenOption.CREATE);

            int httpPort = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + httpPort))
            {
                assertTrue(run2.awaitForJettyStart());

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + httpPort + "/test/test.txt");
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                assertThat(response.getContentAsString(), is(testFileContent));
            }
        }
    }

    @Test
    public void testStaticDeployJar() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        try (JettyHomeTester.Run run1 = distribution.start(List.of("--add-modules=resources,http,static-deploy")))
        {
            assertTrue(run1.awaitForStart());
            assertEquals(0, run1.getExitValue());

            Path outputJar = jettyBase.resolve("webapps/test.jar");
            Map<String, String> env = new HashMap<>();
            env.put("create", "true");

            String testFileContent = "hello";
            URI uri = URI.create("jar:" + outputJar.toUri().toASCIIString());
            // Use ZipFS so that we can create paths that are just "/"
            try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
            {
                Path root = zipfs.getPath("/");
                Files.writeString(root.resolve("test.txt"), testFileContent, StandardOpenOption.CREATE);
            }

            int httpPort = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + httpPort))
            {
                assertTrue(run2.awaitForJettyStart());

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + httpPort + "/test/test.txt");
                assertEquals(HttpStatus.OK_200, response.getStatus(), () -> String.join("\n", run2.getLogs()));
                assertThat(response.getContentAsString(), is(testFileContent));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("provideEnvironmentsToTest")
    public void testEEWebAppProperties(String env) throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .build();

        String[] argsConfig = {
            "--add-modules=http," + toEnvironment("deploy", env)
        };

        try (JettyHomeTester.Run run1 = distribution.start(argsConfig))
        {
            assertTrue(run1.awaitForStart());
            assertEquals(0, run1.getExitValue());

            Path wars = jettyBase.resolve("wars");
            FS.ensureDirExists(wars);
            Path outputJar = wars.resolve("app.war");
            Map<String, String> zipfsEnv = new HashMap<>();
            zipfsEnv.put("create", "true");

            String testFileContent = "hello";
            URI uri = URI.create("jar:" + outputJar.toUri().toASCIIString());
            // Use ZipFS so that we can create paths that are just "/"
            try (FileSystem zipfs = FileSystems.newFileSystem(uri, zipfsEnv))
            {
                Path root = zipfs.getPath("/");
                Files.writeString(root.resolve("test.txt"), testFileContent, StandardOpenOption.CREATE);
            }

            try (Writer out = Files.newBufferedWriter(jettyBase.resolve("webapps/test-%s.properties".formatted(env))))
            {
                Properties props = new Properties();
                props.put("environment", env);
                props.put("jetty.deploy.contextPath", "/test");
                props.put("jetty.deploy.baseResource", outputJar.toString());
                props.store(out, "");
            }

            int httpPort = Tester.freePort();
            try (JettyHomeTester.Run run2 = distribution.start("jetty.http.port=" + httpPort))
            {
                assertTrue(run2.awaitForJettyStart());

                startHttpClient();
                ContentResponse response = client.GET("http://localhost:" + httpPort + "/test/test.txt");
                assertEquals(HttpStatus.OK_200, response.getStatus(), () -> String.join("\n", run2.getLogs()));
                assertThat(response.getContentAsString(), is(testFileContent));
            }
        }
    }
}
