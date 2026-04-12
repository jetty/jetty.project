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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.tests.testers.Tester;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.parallel.Isolated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

//@DisabledOnJre(JRE.JAVA_26)
public class ConscryptModuleTest
{
    @Test
    public void testConscryptModule() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .build();

        try (JettyHomeTester.Run configRun = distribution.start("--approve-all-licenses", "--add-modules=https,conscrypt,test-keystore"))
        {
            assertTrue(configRun.awaitForStart());
            assertEquals(0, configRun.getExitValue());

            int httpsPort = Tester.freePort();
            // Conscrypt returns hard-coded obsolete signature algorithms (SHA1withRSA, SHA1withECDSA)
            // which are rejected by JDK 26+'s new certificate checks in SunX509KeyManagerImpl.
            // Disable the check until Conscrypt fixes this upstream.
            // See https://github.com/google/conscrypt/issues/1486
            List<String> jvmArgs = new ArrayList<>();
            if (Runtime.version().feature() >= 26)
            {
                jvmArgs.add("-Djdk.tls.SunX509KeyManager.certChecking=false");
            }
            try (JettyHomeTester.Run startRun = distribution.start(jvmArgs, List.of("jetty.ssl.selectors=1", "jetty.ssl.port=" + httpsPort)))
            {
                assertTrue(startRun.awaitForJettyStart());
                assertTrue(startRun.getLogs().stream().anyMatch(line -> line.contains("provider=Conscrypt")));

                try (HttpClient httpClient = new HttpClient())
                {
                    httpClient.setSslContextFactory(new SslContextFactory.Client(true));
                    httpClient.start();
                    ContentResponse response = httpClient.GET("https://localhost:" + httpsPort);
                    assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
                }
            }
        }
    }
}
