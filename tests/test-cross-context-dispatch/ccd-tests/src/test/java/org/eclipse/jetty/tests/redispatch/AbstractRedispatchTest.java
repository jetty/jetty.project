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

package org.eclipse.jetty.tests.redispatch;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.tests.testers.Tester;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.component.LifeCycle;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class AbstractRedispatchTest
{
    protected static final int START_TIMEOUT = Integer.getInteger("home.start.timeout", 30);
    protected static final List<String> ENVIRONMENTS = List.of("ee8", "ee9", "ee10", "ee11");

    static String toResponseDetails(ContentResponse response)
    {
        return new ResponseDetails(response).get();
    }

    static class InitializedJettyBase
    {
        public Path jettyBase;
        public JettyHomeTester distribution;
        public int httpPort;

        public InitializedJettyBase(TestInfo testInfo) throws Exception
        {
            Path testsDir = MavenPaths.targetTests();
            String cleanBaseName = toCleanDirectoryName(testInfo);
            jettyBase = testsDir.resolve(cleanBaseName);
            FS.ensureEmpty(jettyBase);
            String jettyVersion = System.getProperty("jettyVersion");
            distribution = JettyHomeTester.Builder.newInstance()
                .jettyVersion(jettyVersion)
                .jettyBase(jettyBase)
                .build();

            httpPort = Tester.freePort();

            List<String> configList = new ArrayList<>();
            configList.add("--add-modules=http,resources");
            for (String env : ENVIRONMENTS)
            {
                configList.add("--add-modules=" + env + "-deploy," + env + "-webapp");
            }

            try (JettyHomeTester.Run runConfig = distribution.start(configList))
            {
                assertTrue(runConfig.awaitFor(START_TIMEOUT, TimeUnit.SECONDS));
                assertEquals(0, runConfig.getExitValue());

                Path libDir = jettyBase.resolve("lib");
                FS.ensureDirExists(libDir);
                Path etcDir = jettyBase.resolve("etc");
                FS.ensureDirExists(etcDir);
                Path modulesDir = jettyBase.resolve("modules");
                FS.ensureDirExists(modulesDir);
                Path startDir = jettyBase.resolve("start.d");
                FS.ensureDirExists(startDir);

                // Configure the DispatchPlanHandler
                Path ccdJar = distribution.resolveArtifact("org.eclipse.jetty.tests.ccd:ccd-common:jar:" + jettyVersion);
                Files.copy(ccdJar, libDir.resolve(ccdJar.getFileName()));

                Path installDispatchPlanXml = MavenPaths.findTestResourceFile("install-ccd-handler.xml");
                Files.copy(installDispatchPlanXml, etcDir.resolve(installDispatchPlanXml.getFileName()));

                String module = """
                [depend]
                server
                
                [lib]
                lib/jetty-util-ajax-$J.jar
                lib/ccd-common-$J.jar
                
                [xml]
                etc/install-ccd-handler.xml
                
                [ini]
                jetty.webapp.addProtectedClasses+=,org.eclipse.jetty.tests.ccd.common.
                jetty.webapp.addHiddenClasses+=,-org.eclipse.jetty.tests.ccd.common.
                """.replace("$J", jettyVersion);
                Files.writeString(modulesDir.resolve("ccd.mod"), module, StandardCharsets.UTF_8);

                // -- Error Handler
                Path errorHandlerXml = MavenPaths.findTestResourceFile("error-handler.xml");
                Files.copy(errorHandlerXml, etcDir.resolve("error-handler.xml"));
                String errorHandlerIni = """
                    etc/error-handler.xml
                    """;
                Files.writeString(startDir.resolve("error-handler.ini"), errorHandlerIni);

                // -- Plans Dir
                Path plansDir = MavenPaths.findTestResourceDir("plans");

                Path ccdIni = startDir.resolve("ccd.ini");
                String ini = """
                --module=ccd
                ccd-plans-dir=$D
                """.replace("$D", plansDir.toString());
                Files.writeString(ccdIni, ini, StandardCharsets.UTF_8);

                // -- Add the test wars
                for (String env : ENVIRONMENTS)
                {
                    Path war = distribution.resolveArtifact("org.eclipse.jetty.tests.ccd:ccd-" + env + "-webapp:war:" + jettyVersion);
                    distribution.installWar(war, "ccd-" + env);
                    Path warProperties = jettyBase.resolve("webapps/ccd-" + env + ".properties");
                    Files.writeString(warProperties, "environment: " + env, StandardCharsets.UTF_8);

                    Path webappXmlSrc = MavenPaths.findTestResourceFile("webapp-xmls/ccd-" + env + ".xml");
                    Path webappXmlDest = jettyBase.resolve("webapps/ccd-" + env + ".xml");
                    Files.copy(webappXmlSrc, webappXmlDest);
                }
            }
        }

        /**
         * Create a name that can be used as a Jetty Base home directory in a safe way.
         *
         * Note: unlike the WorkDir object, this strips out {@code [} and {@code ]} characters
         * and also makes any non-alpha-numeric character just {@code _}, which results in
         * a happy {@code ${jetty.base}} and {@code start.jar}.
         *
         * Failure to use this method can result in start.jar behaving in unintended ways
         * when it goes through the Java -> Runtime.exec -> OS behaviors.
         *
         * This change also makes the created directory named {@code target/tests/<method-name>.<display-name>}
         * live and suitable for execution via a console without accidental shell interpretation of special
         * characters in the directory name (that can result from characters like "[]" used in a directory name)
         *
         * @param testInfo the TestInfo to use to generate directory name from.
         * @return the safe to use directory name.
         */
        public static String toCleanDirectoryName(TestInfo testInfo)
        {
            StringBuilder name = new StringBuilder();
            if (testInfo.getTestMethod().isPresent())
            {
                name.append(testInfo.getTestMethod().get().getName());
                name.append(".");
            }
            for (char c: testInfo.getDisplayName().toCharArray())
            {
                if (Character.isLetterOrDigit(c) || c == '.' || c == '-')
                    name.append(c);
                else if (c != '[' && c != ']')
                    name.append("_");
            }
            return name.toString();
        }
    }

    protected HttpClient client;

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void stopClient()
    {
        LifeCycle.stop(client);
    }

    public static void dumpProperties(Properties props)
    {
        props.stringPropertyNames().stream()
            .sorted()
            .forEach((name) ->
                System.out.printf("  %s=%s%n", name, props.getProperty(name)));
    }

    public static void assertProperty(Properties props, String name, Matcher<String> valueMatcher)
    {
        assertThat("Property [" + name + "]", props.getProperty(name), valueMatcher);
    }

    public static void assertProperty(String id, Properties props, String name, Matcher<String> valueMatcher)
    {
        assertThat("id[" + id + "] property[" + name + "]", props.getProperty(name), valueMatcher);
    }
}
