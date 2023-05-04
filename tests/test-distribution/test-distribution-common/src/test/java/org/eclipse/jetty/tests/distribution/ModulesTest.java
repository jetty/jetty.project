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

import java.io.Closeable;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.logging.JettyLevel;
import org.eclipse.jetty.logging.JettyLogger;
import org.eclipse.jetty.tests.hometester.JettyHomeTester;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.tests.distribution.AbstractJettyHomeTest.START_TIMEOUT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ModulesTest
{
    private static class LogDisabler implements Closeable
    {
        private final JettyLevel previousLevel;
        private final JettyLogger logger;

        public LogDisabler(Class<?> clazz)
        {
            this.logger = (JettyLogger)LoggerFactory.getLogger(clazz);
            this.previousLevel = logger.getLevel();
            this.logger.setLevel(JettyLevel.OFF);
        }

        @Override
        public void close()
        {
            logger.setLevel(previousLevel);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ee8-openid", "ee9-openid", "openid"})
    public void testOpenidModules(String module) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        // Add module.
        try (JettyHomeTester.Run run = distribution.start("--add-modules=" + module))
        {
            run.awaitFor(START_TIMEOUT, TimeUnit.SECONDS);
            assertThat(run.getExitValue(), is(0));
        }

        // Verify that Jetty fails to start b/c the issue was not configured.
        try (LogDisabler ignored = new LogDisabler(JettyHomeTester.class);
            JettyHomeTester.Run run = distribution.start())
        {
            assertThat(run.awaitConsoleLogsFor("Issuer was not configured", START_TIMEOUT, TimeUnit.SECONDS), is(true));
            run.stop();
            assertThat(run.awaitFor(START_TIMEOUT, TimeUnit.SECONDS), is(true));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ee8-websocket-javax", "ee9-websocket-jakarta", "ee10-websocket-jakarta"})
    public void testWebsocketModules(String module) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        // Add module.
        try (JettyHomeTester.Run run = distribution.start("--add-modules=" + module))
        {
            run.awaitFor(START_TIMEOUT, TimeUnit.SECONDS);
            assertThat(run.getExitValue(), is(0));
        }

        // Verify that Jetty starts.
        try (JettyHomeTester.Run run = distribution.start())
        {
            assertThat(run.awaitConsoleLogsFor("Started oejs.Server", START_TIMEOUT, TimeUnit.SECONDS), is(true));
            run.stop();
            assertThat(run.awaitFor(START_TIMEOUT, TimeUnit.SECONDS), is(true));
        }
    }

    @Test
    public void testStatistics() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .mavenLocalRepository(System.getProperty("mavenRepoPath"))
            .build();

        // Add module.
        try (JettyHomeTester.Run run = distribution.start("--add-modules=statistics"))
        {
            run.awaitFor(START_TIMEOUT, TimeUnit.SECONDS);
            assertThat(run.getExitValue(), is(0));
        }

        // Verify that Jetty starts.
        try (JettyHomeTester.Run run = distribution.start())
        {
            assertThat(run.awaitConsoleLogsFor("Started oejs.Server", START_TIMEOUT, TimeUnit.SECONDS), is(true));
            run.stop();
            assertThat(run.awaitFor(START_TIMEOUT, TimeUnit.SECONDS), is(true));
        }
    }
}
