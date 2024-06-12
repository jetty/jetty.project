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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.ccd.common.DispatchPlan;
import org.eclipse.jetty.tests.ccd.common.HttpRequest;
import org.eclipse.jetty.tests.ccd.common.Property;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RedispatchPlansTests extends AbstractRedispatchTest
{
    private InitializedJettyBase jettyBase;
    private JettyHomeTester.Run runStart;

    @BeforeEach
    public void startJettyBase(TestInfo testInfo) throws Exception
    {
        jettyBase = new InitializedJettyBase(testInfo);

        String[] argsStart = {
            "jetty.http.port=" + jettyBase.httpPort
        };

        runStart = jettyBase.distribution.start(argsStart);

        assertTrue(runStart.awaitConsoleLogsFor("Started oejs.Server@", START_TIMEOUT, TimeUnit.SECONDS));
    }

    @AfterEach
    public void stopJettyBase()
    {
        runStart.close();
    }

    public static Stream<Arguments> dispatchPlans() throws IOException
    {
        List<Arguments> plans = new ArrayList<>();

        List<String> disabledTests = new ArrayList<>();
        //disabledTests.add("ee10-session-ee8-ee9-ee8.txt");

        Path testPlansDir = MavenPaths.findTestResourceDir("plans");
        try (Stream<Path> plansStream = Files.list(testPlansDir))
        {
            List<Path> testPlans = plansStream
                .filter(Files::isRegularFile)
                .filter((file) -> file.getFileName().toString().endsWith(".txt"))
                .filter((file) -> !disabledTests.contains(file.getFileName().toString()))
                .toList();

            for (Path plansText : testPlans)
            {
                plans.add(Arguments.of(DispatchPlan.read(plansText)));
            }
        }

        return plans.stream();
    }

    @ParameterizedTest
    @MethodSource("dispatchPlans")
    public void testRedispatch(DispatchPlan dispatchPlan) throws Exception
    {
        HttpRequest requestStep = dispatchPlan.getRequestStep();
        assertNotNull(requestStep);
        ContentResponse response = client.newRequest("localhost", jettyBase.httpPort)
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
            assertThat("id[" + dispatchPlan.id() + "] event[" + i + "]", responseProps.getProperty("dispatchPlan.event[" + i + "]"), is(dispatchPlan.getExpectedEvents().get(i)));
        }

        if (dispatchPlan.getExpectedContentType() != null)
        {
            assertThat("Expected ContentType", response.getHeaders().get(HttpHeader.CONTENT_TYPE), is(dispatchPlan.getExpectedContentType()));
        }

        for (Property expectedProperty : dispatchPlan.getExpectedProperties())
        {
            assertProperty(dispatchPlan.id(), responseProps, expectedProperty.getName(), is(expectedProperty.getValue()));
        }

        // Ensure that all seen session ids are the same.
        if (dispatchPlan.isExpectedSessionIds())
        {
            // Verify that Request Attributes for Session.id are in agreement
            List<String> attrNames = responseProps.keySet().stream()
                .map(Object::toString)
                .filter((name) -> name.startsWith("req.attr[session["))
                .toList();

            if (attrNames.size() > 1)
            {
                String expectedId = responseProps.getProperty(attrNames.get(0));
                for (String name : attrNames)
                {
                    assertEquals(expectedId, responseProps.getProperty(name));
                }
            }

            // Verify that Context Attributes for Session.id are in agreement
            // And that all ids have had their .commit() and .release() methods called.
            Path sessionLog = jettyBase.jettyBase.resolve("work/session.log");
            assertTrue(Files.isRegularFile(sessionLog), "Missing " + sessionLog);

            List<String> logEntries = Files.readAllLines(sessionLog);
            List<String> newSessions = logEntries.stream()
                .filter(line -> line.contains("SessionCache.event.newSession()"))
                .map(line -> line.substring(line.indexOf("=") + 1))
                .toList();
            // we should have the commit() and release() for each new Session.
            for (String sessionId : newSessions)
            {
                assertThat(logEntries, hasItem("SessionCache.event.commit()=" + sessionId));
                assertThat(logEntries, hasItem("SessionCache.event.release()=" + sessionId));
            }

            // TODO: should we check the response headers for a "Set-Cookie" entry?
        }
    }
}
