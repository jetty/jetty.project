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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.tests.testers.JettyHomeTester;
import org.eclipse.jetty.tests.testers.Tester;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test various Shutdown configurations.
 */
@ExtendWith(WorkDirExtension.class)
public class ShutdownTests
{
    public WorkDir workDir;

    @ParameterizedTest
    @ValueSource(strings = {"stop", "forcestop", "stopexit"})
    public void testFromSystemPropertiesStop(String command) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(workDir.getEmptyPathDir())
            .build();

        List<String> configArgs = List.of(
            "--add-module=http",
            "--add-module=core-deploy"
        );

        try (JettyHomeTester.Run configRun = distribution.start(configArgs))
        {
            assertTrue(configRun.awaitForStart());
            assertEquals(0, configRun.getExitValue());

            int httpPort = Tester.freePort();
            int servicePort = Tester.freePort();

            String stopKey = "SEAWALL";

            List<String> runJvmArgs = List.of(
                "-DSTOP.PORT=" + servicePort,
                "-DSTOP.KEY=" + stopKey,
                "-DSTOP.HOST=localhost",
                "-DSTOP.EXIT=true"
            );

            List<String> runStartArgs = List.of(
                "jetty.http.port=" + httpPort
            );

            try (JettyHomeTester.Run startRun = distribution.start(runJvmArgs, runStartArgs))
            {
                assertTrue(startRun.awaitForJettyStart());

                try (HttpClient httpClient = new HttpClient())
                {
                    httpClient.start();

                    // Confirm Jetty server is operational
                    ContentResponse response = httpClient.GET("http://localhost:" + httpPort + "/");
                    assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());

                    // Send shutdown command
                    String reply = sendShutdownCommand("localhost", servicePort, stopKey, command);
                    assertEquals("Stopped", reply);

                    // Confirm Jetty server is no longer operational
                    ExecutionException ex = assertThrows(ExecutionException.class,
                        () -> httpClient.GET("http://localhost:" + httpPort + "/"));
                    assertInstanceOf(ConnectException.class, ex.getCause());
                }
                finally
                {
                    startRun.saveLogs(distribution.getJettyBase().resolve("logs/run.log"));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"stop", "forcestop", "stopexit"})
    public void testModuleConfigStop(String command) throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        JettyHomeTester distribution = JettyHomeTester.Builder.newInstance()
            .jettyVersion(jettyVersion)
            .jettyBase(workDir.getEmptyPathDir())
            .build();

        List<String> configArgs = List.of(
            "--add-module=http",
            "--add-module=core-deploy",
            "--add-module=shutdown"
        );

        try (JettyHomeTester.Run configRun = distribution.start(configArgs))
        {
            assertTrue(configRun.awaitForStart());
            assertEquals(0, configRun.getExitValue());

            int httpPort = Tester.freePort();
            int servicePort = Tester.freePort();

            String stopKey = "SEAWALL";

            List<String> runJvmArgs = List.of();

            List<String> runStartArgs = List.of(
                "jetty.http.port=" + httpPort,
                "jetty.shutdown.host=localhost",
                "jetty.shutdown.port=" + servicePort,
                "jetty.shutdown.key=" + stopKey,
                "jetty.shutdown.exit=true"
            );

            try (JettyHomeTester.Run startRun = distribution.start(runJvmArgs, runStartArgs))
            {
                assertTrue(startRun.awaitForJettyStart());

                try (HttpClient httpClient = new HttpClient())
                {
                    httpClient.start();

                    // Confirm Jetty server is operational
                    ContentResponse response = httpClient.GET("http://localhost:" + httpPort + "/");
                    assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());

                    // Send shutdown command
                    String reply = sendShutdownCommand("localhost", servicePort, stopKey, command);
                    assertEquals("Stopped", reply);

                    // Confirm Jetty server is no longer operational
                    ExecutionException ex = assertThrows(ExecutionException.class,
                        () -> httpClient.GET("http://localhost:" + httpPort + "/"));
                    assertInstanceOf(ConnectException.class, ex.getCause());
                }
                finally
                {
                    startRun.saveLogs(distribution.getJettyBase().resolve("logs/run.log"));
                }
            }
        }
    }

    private static String sendShutdownCommand(String host, int port, String key, String command) throws IOException
    {
        try (Socket s = new Socket(InetAddress.getByName(host), port);
             OutputStream out = s.getOutputStream();
             InputStream in = s.getInputStream();
             InputStreamReader inReader = new InputStreamReader(in, StandardCharsets.US_ASCII);
             BufferedReader reader = new BufferedReader(inReader))
        {
            s.setSoTimeout(5000);
            out.write((key + "\r\n" + command + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String reply = reader.readLine();
            System.err.println("Reply: " + reply);
            return reply;
        }
    }
}
