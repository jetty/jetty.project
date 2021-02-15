//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty;

import com.acme.DispatchServlet;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletTester;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simple tests against DispatchServlet.
 */
public class DispatchServletTest
{
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
        ServletTester tester = new ServletTester();
        tester.setContextPath("/tests");

        ServletHolder dispatch = tester.addServlet(DispatchServlet.class, "/dispatch/*");
        tester.addServlet(DefaultServlet.class, "/");
        tester.start();

        StringBuilder req1 = new StringBuilder();
        req1.append("GET /tests/dispatch/includeN/").append(dispatch.getName()).append(" HTTP/1.1\n");
        req1.append("Host: tester\n");
        req1.append("Connection: close\n");
        req1.append("\n");

        String response = tester.getResponses(req1.toString());

        String msg = "Response code on SelfRefDoS";

        assertFalse(response.startsWith("HTTP/1.1 500 "), msg + " should not be code 500.");
        assertTrue(response.startsWith("HTTP/1.1 403 "), msg + " should return error code 403 (Forbidden)");
    }

    @Test
    public void testSelfRefDeep() throws Exception
    {
        ServletTester tester = new ServletTester();
        tester.setContextPath("/tests");
        tester.addServlet(DispatchServlet.class, "/dispatch/*");
        tester.addServlet(DefaultServlet.class, "/");
        tester.start();

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
            StringBuilder req1 = new StringBuilder();
            req1.append("GET /tests");
            for (int i = 0; i < nestedDepth; i++)
            {
                req1.append(selfRef);
            }

            req1.append("/ HTTP/1.1\n");
            req1.append("Host: tester\n");
            req1.append("Connection: close\n");
            req1.append("\n");

            String response = tester.getResponses(req1.toString());

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
