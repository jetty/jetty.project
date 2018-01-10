//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletTester;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import com.acme.ChatServlet;

@RunWith(JUnit4.class)
public class ChatServletTest
{

    private final ServletTester tester = new ServletTester();

    @Before
    public void setUp() throws Exception
    {
        tester.setContextPath("/");

        ServletHolder dispatch = tester.addServlet(ChatServlet.class, "/chat/*");
        dispatch.setInitParameter("asyncTimeout", "500");
        tester.start();
    }

    @After
    public void tearDown() throws Exception
    {
        tester.stop();
    }

    @Test
    public void testLogin() throws Exception
    {
        assertResponse("user=test&join=true&message=has%20joined!", "{\"from\":\"test\",\"chat\":\"has joined!\"}");
    }

    @Test
    public void testChat() throws Exception
    {
        assertResponse("user=test&join=true&message=has%20joined!", "{\"from\":\"test\",\"chat\":\"has joined!\"}");
        String response = tester.getResponses(createRequestString("user=test&message=message"));
        assertThat(response.contains("{"), is(false)); // make sure we didn't get a json body
    }

    @Test
    public void testPoll() throws Exception
    {
        assertResponse("user=test", "{action:\"poll\"}");
    }

    private void assertResponse(String requestBody, String expectedResponse) throws Exception
    {
        String response = tester.getResponses(createRequestString(requestBody));
        assertThat(response.contains(expectedResponse), is(true));
    }

    private String createRequestString(String body)
    {
        StringBuilder req1 = new StringBuilder();
        req1.append("POST /chat/ HTTP/1.1\r\n");
        req1.append("Host: tester\r\n");
        req1.append("Content-length: " + body.length() + "\r\n");
        req1.append("Content-type: application/x-www-form-urlencoded\r\n");
        req1.append("Connection: close\r\n");
        req1.append("\r\n");
        req1.append(body);
        return req1.toString();
    }
}
