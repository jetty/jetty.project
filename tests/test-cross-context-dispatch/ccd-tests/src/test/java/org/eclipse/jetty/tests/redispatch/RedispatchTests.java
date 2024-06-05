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

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.ccd.common.DispatchPlan;
import org.eclipse.jetty.tests.ccd.common.steps.HttpRequestStep;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.tests.testers.Tester;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class RedispatchTests
{
    public static final int START_TIMEOUT = Integer.getInteger("home.start.timeout", 30);

    private static final List<String> ENVIRONMENTS = List.of("ee8", "ee9", "ee10");
    private static HttpClient client;
    private static Path jettyBase;
    private static JettyHomeTester distribution;
    private static int httpPort;
    private static JettyHomeTester.Run runStart;

    @BeforeAll
    public static void startJettyBase(WorkDir workDir) throws Exception
    {
        jettyBase = workDir.getEmptyPathDir();
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

            String[] argsStart = {
                "jetty.http.port=" + httpPort
            };

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
                jetty.webapp.addSystemClasses+=,org.eclipse.jetty.tests.ccd.common.
                jetty.webapp.addServerClasses+=,-org.eclipse.jetty.tests.ccd.common.
                """.replace("$J", jettyVersion);
            Files.writeString(modulesDir.resolve("ccd.mod"), module, StandardCharsets.UTF_8);

            Path plansDir = MavenPaths.findTestResourceDir("plans");

            Path ccdIni = startDir.resolve("ccd.ini");
            String ini = """
                --module=ccd
                ccd-plans-dir=$D
                """.replace("$D", plansDir.toString());
            Files.writeString(ccdIni, ini, StandardCharsets.UTF_8);

            // Add the test wars
            for (String env : ENVIRONMENTS)
            {
                Path war = distribution.resolveArtifact("org.eclipse.jetty.tests.ccd:ccd-" + env + "-webapp:war:" + jettyVersion);
                distribution.installWar(war, "ccd-" + env);
                Path warProperties = jettyBase.resolve("webapps/ccd-" + env + ".properties");
                Files.writeString(warProperties, "environment: " + env, StandardCharsets.UTF_8);
            }

            runStart = distribution.start(argsStart);

            assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));

            client = new HttpClient();
            client.start();
        }
    }

    @AfterAll
    public static void stopJettyBase()
    {
        runStart.close();
    }

    public static Stream<Arguments> dispatchPlans() throws IOException
    {
        List<DispatchPlan> plans = new ArrayList<>();

        plans.add(loadDispatchPlan("ee10-request-forward-dump.json"));

        return plans.stream().map(Arguments::of);
    }

    private static DispatchPlan loadDispatchPlan(String planFileName) throws IOException
    {
        Path planPath = MavenPaths.findTestResourceFile("plans/" + planFileName);
        return DispatchPlan.read(planPath);
    }

    @ParameterizedTest
    @MethodSource("dispatchPlans")
    public void testRedispatch(DispatchPlan dispatchPlan) throws Exception
    {
        HttpRequestStep requestStep = dispatchPlan.getRequestStep();
        assertNotNull(requestStep);
        ContentResponse response = client.newRequest("localhost", httpPort)
            .method(requestStep.getMethod())
            .headers((headers) ->
                headers.put("X-DispatchPlan", dispatchPlan.id()))
            .path(requestStep.getRequestPath())
            .send();
        String responseDetails = toResponseDetails(response);
        assertThat(responseDetails, response.getStatus(), is(HttpStatus.OK_200));

        Properties responseProps = new Properties();
        try (StringReader stringReader = new StringReader(response.getContentAsString()))
        {
            responseProps.load(stringReader);
        }

        dumpProperties(responseProps);

        int expectedEventCount = dispatchPlan.getExpectedEvents().size();
        assertThat(responseProps.getProperty("dispatchPlan.events.count"), is(Integer.toString(expectedEventCount)));
        for (int i = 0; i < expectedEventCount; i++)
        {
            assertThat("event[" + i + "]", responseProps.getProperty("dispatchPlan.event[" + i + "]"), is(dispatchPlan.getExpectedEvents().get(i)));
        }
    }

    private void dumpProperties(Properties props)
    {
        props.stringPropertyNames().stream()
            .sorted()
            .forEach((name) ->
                System.out.printf("%s=%s%n", name, props.getProperty(name)));
    }

    private static String toResponseDetails(ContentResponse response)
    {
        return new ResponseDetails(response).get();
    }

    protected static class ResponseDetails implements Supplier<String>
    {
        private final ContentResponse response;

        public ResponseDetails(ContentResponse response)
        {
            this.response = response;
        }

        @Override
        public String get()
        {
            StringBuilder ret = new StringBuilder();
            ret.append(response.toString()).append(System.lineSeparator());
            ret.append(response.getHeaders().toString()).append(System.lineSeparator());
            ret.append(response.getContentAsString()).append(System.lineSeparator());
            return ret.toString();
        }
    }
}
