//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty;

import com.acme.DispatchServlet;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.component.LifeCycle;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simple tests against DispatchServlet.
 */
public class DispatchServletTest
{
    private Server server;
    private LocalConnector connector;
    private ServletContextHandler context;

    @BeforeEach
    public void setUp() throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);
        context = new ServletContextHandler(server, "/tests");
        server.start();
    }

    @AfterEach
    public void tearDown()
    {
        LifeCycle.stop(server);
    }

    /**
     * As filed in JETTY-978.
     *
     * Security problems in demo dispatch servlet.
     *
     * <blockquote>
     * <p>
     * The dispatcher servlet (com.acme.DispatchServlet) is prone to a Denial of
     * Service vulnerability.
     * </p>
     * <p>
     * This example servlet is meant to be used as a resources dispatcher,
     * however a malicious aggressor may abuse this functionality in order to
     * cause a recursive inclusion. In details, it is possible to abuse the
     * method com.acme.DispatchServlet.doGet(DispatchServlet.java:203) forcing
     * the application to recursively include the "Dispatch" servlet.
     * </p>
     * <p>
     * Dispatch com.acme.DispatchServlet 1 Dispatch /dispatch/* As a result, it
     * is possible to trigger a "java.lang.StackOverflowError" and consequently
     * an internal server error (500).
     * </p>
     * <p>
     * Multiple requests may easily affect the availability of the servlet
     * container. Since this attack can cause the server to consume resources in
     * a non-linear relationship to the size of inputs, it should be considered
     * as a server flaw.
     * </p>
     * <p>
     * The vulnerability seems confined to the example servlet and it does not
     * afflict the Jetty's core."
     * </p>
     * </blockquote>
     */
    @Test
    public void testSelfRefForwardDenialOfService() throws Exception
    {
        ServletHolder dispatch = context.addServlet(DispatchServlet.class, "/dispatch/*");
        context.addServlet(DefaultServlet.class, "/");

        String request = "GET /tests/dispatch/includeN/" + dispatch.getName() + " HTTP/1.1\n" +
            "Host: tester\n" +
            "Connection: close\n" +
            "\n";
        String response = connector.getResponse(request);

        String msg = "Response code on SelfRefDoS";

        assertFalse(response.startsWith("HTTP/1.1 500 "), msg + " should not be code 500.");
        assertTrue(response.startsWith("HTTP/1.1 403 "), msg + " should return error code 403 (Forbidden)");
    }

    @Test
    public void testSelfRefDeep() throws Exception
    {
        context.addServlet(DispatchServlet.class, "/dispatch/*");
        context.addServlet(DefaultServlet.class, "/");

        String[] selfRefs =
            {"/dispatch/forward", "/dispatch/includeS", "/dispatch/includeW", "/dispatch/includeN"};

        /*
         * Number of nested dispatch requests. 220 is a good value, as it won't
         * trigger an Error 413 response (Entity too large). Anything larger
         * than 220 will trigger a 413 response.
         */
        int nestedDepth = 220;

        for (String selfRef : selfRefs)
        {
            String request = "GET /tests" +
                selfRef.repeat(nestedDepth) +
                "/ HTTP/1.1\n" +
                "Host: tester\n" +
                "Connection: close\n" +
                "\n";
            String response = connector.getResponse(request);

            StringBuilder msg = new StringBuilder();
            msg.append("Response code on nested \"").append(selfRef).append("\"");
            msg.append(" (depth:").append(nestedDepth).append(")");

            assertFalse(response.startsWith("HTTP/1.1 413 "),
                msg + " should not be code 413 (Request Entity Too Large)," +
                    "the nestedDepth in the TestCase is too large (reduce it)");

            assertFalse(response.startsWith("HTTP/1.1 500 "), msg + " should not be code 500.");
            assertThat(response, Matchers.startsWith("HTTP/1.1 403 "));
        }
    }
}
