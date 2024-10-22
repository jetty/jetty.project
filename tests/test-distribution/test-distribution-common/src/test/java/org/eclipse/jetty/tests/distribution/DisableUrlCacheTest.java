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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.tests.testers.Tester;
import org.eclipse.jetty.toolchain.test.FS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
public class DisableUrlCacheTest extends AbstractJettyHomeTest
{
    private static final Logger LOG = LoggerFactory.getLogger(DisableUrlCacheTest.class);

    @Test
    public void testReloadWebAppWithLog4j2() throws Exception
    {
        Path jettyBase = newTestJettyBaseDirectory();
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(jettyBase)
            .jvmArgs(List.of("-Dorg.eclipse.jetty.deploy.LEVEL=DEBUG"))
            .build();

        String[] setupArgs = {
            "--add-modules=http,ee10-webapp,ee10-deploy,disable-urlcache"
        };

        try (JettyHomeTester.Run setupRun = distribution.start(setupArgs))
        {
            assertTrue(setupRun.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, setupRun.getExitValue());

            Path webApp = distribution.resolveArtifact("org.eclipse.jetty.ee10:jetty-ee10-test-log4j2-webapp:war:" + jettyVersion);
            Path testWebApp = distribution.getJettyBase().resolve("webapps/test.war");

            Files.copy(webApp, testWebApp);

            Path tempDir = distribution.getJettyBase().resolve("work");
            FS.ensureEmpty(tempDir);

            Path resourcesDir = distribution.getJettyBase().resolve("resources");
            FS.ensureEmpty(resourcesDir);

            Path webappsDir = distribution.getJettyBase().resolve("webapps");
            String warXml = """
                <?xml version="1.0"  encoding="ISO-8859-1"?>
                <!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure_10_0.dtd">
                <Configure class="org.eclipse.jetty.ee10.webapp.WebAppContext">
                   <Set name="contextPath">/test</Set>
                   <Set name="war"><Property name="jetty.webapps"/>/test.war</Set>
                   <Set name="tempDirectory"><Property name="jetty.base"/>/work/test</Set>
                   <Set name="tempDirectoryPersistent">false</Set>
                </Configure>
                """;
            Path warXmlPath = webappsDir.resolve("test.xml");
            Files.writeString(warXmlPath, warXml, StandardCharsets.UTF_8);

            Path loggingFile = resourcesDir.resolve("jetty-logging.properties");
            String loggingConfig = """
                org.eclipse.jetty.LEVEL=INFO
                org.eclipse.jetty.deploy.LEVEL=DEBUG
                org.eclipse.jetty.ee10.webapp.LEVEL=DEBUG
                org.eclipse.jetty.ee10.webapp.WebAppClassLoader.LEVEL=INFO
                org.eclipse.jetty.ee10.servlet.LEVEL=DEBUG
                """;
            Files.writeString(loggingFile, loggingConfig, StandardCharsets.UTF_8);


            int port = Tester.freePort();
            String[] runArgs = {
                "jetty.http.port=" + port,
                "jetty.deploy.scanInterval=1"
                //"jetty.server.dumpAfterStart=true",
            };
            try (JettyHomeTester.Run run2 = distribution.start(runArgs))
            {
                assertTrue(run2.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));
                startHttpClient(false);

                // Test webapp is there
                ContentResponse response = client.GET("http://localhost:" + port + "/test/log/");
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                String content = response.getContentAsString();
                assertThat(content, containsString("GET at LogServlet"));

                // Trigger a hot-reload
                run2.getLogs().clear();
                touch(warXmlPath);

                // Wait for reload to start context
                assertTrue(run2.awaitConsoleLogsFor("Started oeje10w.WebAppContext@", START_TIMEOUT, TimeUnit.SECONDS));
                // wait for deployer node to complete so context is Started not Starting
                assertTrue(run2.awaitConsoleLogsFor("Executing Node Node[started]", START_TIMEOUT, TimeUnit.SECONDS));

                // Is webapp still there?
                response = client.GET("http://localhost:" + port + "/test/log/");
                content = response.getContentAsString();
                assertThat(content, response.getStatus(), is(HttpStatus.OK_200));
                assertThat(content, containsString("GET at LogServlet"));
            }
        }
    }

    private void touch(Path path) throws IOException
    {
        LOG.info("Touch: {}", path);
        FileTime now = FileTime.fromMillis(System.currentTimeMillis() + 2000);
        Files.setLastModifiedTime(path, now);
    }
}
