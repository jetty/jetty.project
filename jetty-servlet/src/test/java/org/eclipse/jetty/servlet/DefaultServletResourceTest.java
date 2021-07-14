//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.servlet;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Test access to Resource content via a Request / Servlet / DefaultServlet / ResourceService.
 * <p>
 * This is to ensure that the process is possible, and that we don't accidentally decode
 * the requested resource on the way to the Resource object.
 * </p>
 */
public class DefaultServletResourceTest
{
    private Server server;
    private LocalConnector connector;
    private Resource resourceUriReserved;
    private Resource resourceOdd;

    @BeforeEach
    public void init() throws Exception
    {
        server = new Server();

        connector = new LocalConnector(server);
        connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendServerVersion(false);
        server.addConnector(connector);

        ServletContextHandler reservedContext = new ServletContextHandler();
        reservedContext.setContextPath("/reserved-jar");
        File uriReservedJar = MavenTestingUtils.getTestResourceFile("uri-reserved-chars.jar");
        URL uriReservedJarUrl = new URL("jar:" + uriReservedJar.toURI().toASCIIString() + "!/");
        resourceUriReserved = Resource.newResource(uriReservedJarUrl);
        reservedContext.setBaseResource(resourceUriReserved);
        reservedContext.addServlet(DefaultServlet.class, "/");

        ServletContextHandler oddContext = new ServletContextHandler();
        oddContext.setContextPath("/odd-jar");
        File oddJar = MavenTestingUtils.getTestResourceFile("jar-resource-odd.jar");
        URL oddJarUrl = new URL("jar:" + oddJar.toURI().toASCIIString() + "!/");
        resourceOdd = Resource.newResource(oddJarUrl);
        oddContext.setBaseResource(resourceOdd);
        oddContext.addServlet(DefaultServlet.class, "/");

        ContextHandlerCollection handlers = new ContextHandlerCollection();
        handlers.addHandler(reservedContext);
        handlers.addHandler(oddContext);

        server.setHandler(handlers);
        server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        server.stop();
        server.join();
    }

    private static List<String[]> uriReservedJarEntries()
    {
        List<String[]> cases = new ArrayList<>();

        cases.add(new String[]{"/uri-reserved-chars/reserved*asterisk.txt", "reserved[*]"});
        cases.add(new String[]{"/uri-reserved-chars/reserved[square]brackets.txt", "reserved[[]]"});
        cases.add(new String[]{"/uri-reserved-chars/reserved?question.txt", "reserved[?]"});
        cases.add(new String[]{"/uri-reserved-chars/reserved:colon.txt", "reserved[:]"});
        cases.add(new String[]{"/uri-reserved-chars/reserved!exclamation.txt", "reserved[!]"});
        cases.add(new String[]{"/uri-reserved-chars/reserved+plus.txt", "reserved[+]"});
        cases.add(new String[]{"/uri-reserved-chars/reserved,comma.txt", "reserved[,]"});
        cases.add(new String[]{"/uri-reserved-chars/reserved&ampersand.txt", "reserved[&]"});
        cases.add(new String[]{"/uri-reserved-chars/reserved;semi.txt", "reserved[;]"});
        cases.add(new String[]{"/uri-reserved-chars/reserved=equals.txt", "reserved[=]"});
        cases.add(new String[]{"/uri-reserved-chars/reserved'apostrophe.txt", "reserved[']"});
        cases.add(new String[]{"/uri-reserved-chars/reserved$dollar.txt", "reserved[$]"});
        cases.add(new String[]{"/uri-reserved-chars/reserved@at.txt", "reserved[@]"});
        cases.add(new String[]{"/uri-reserved-chars/reserved(parens).txt", "reserved[()]"});
        cases.add(new String[]{"/uri-reserved-chars/reserved#hash.txt", "reserved[#]"});
        return cases;
    }

    public static Stream<Arguments> uriReservedJarCases()
    {
        return uriReservedJarEntries().stream()
            .map(entry -> Arguments.of(entry[0], entry[1]));
    }

    /**
     * Test of resource access, where the resource being requested contains URI reserved characters
     * that can have special processing / meaning in a URI which might prevent their access via
     * a HTTP request to the resource.
     * <p>
     * The requested resource might need to encode these special characters.
     * But then what characters are decoded before being requested of the resource?
     * </p>
     */
    public static Stream<Arguments> uriReservedJarRequestCases()
    {
        List<Arguments> cases = new ArrayList<>();

        for (String[] entry : uriReservedJarEntries())
        {
            cases.add(Arguments.of(entry[0], entry[1]));
            cases.add(Arguments.of(URIUtil.encodePath(entry[0]), entry[1]));
        }

        return cases.stream();
    }

    /**
     * Request resource contents directly from URI Reserved Resource.
     */
    @ParameterizedTest
    @MethodSource("uriReservedJarCases")
    public void testUriReservedCharJarFileResourceAccess(String rawRequestPath, String expectedContent) throws Exception
    {
        // Attempt access using raw path against the Resource directly
        Resource resource = resourceUriReserved.addPath(rawRequestPath);
        assertThat("Resource exists: " + resource, resource.exists(), is(true));

        try (InputStream in = resource.getInputStream())
        {
            String body = IO.toString(in, StandardCharsets.UTF_8);
            assertThat(body, containsString(expectedContent));
        }
    }

    /**
     * Request "/reserved-jar" resource via an HTTP Request
     */
    @ParameterizedTest
    @MethodSource("uriReservedJarRequestCases")
    public void testUriReservedCharJarFileRequestAccess(String rawRequestPath, String expectedContent) throws Exception
    {
        // Attempt a request of the resource
        StringBuilder req = new StringBuilder();
        req.append("GET /reserved-jar").append(rawRequestPath).append(" HTTP/1.1\r\n");
        req.append("Host: local\r\n");
        req.append("Connection: close\r\n");
        req.append("\r\n");

        String rawResponse = connector.getResponse(req.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        String body = response.getContent();
        assertThat(body, containsString(expectedContent));
    }

    private static List<String[]> oddJarEntries()
    {
        List<String[]> cases = new ArrayList<>();

        cases.add(new String[]{"/rez/one", "is this the one?"});
        cases.add(new String[]{"/rez/aaa", "this is aaa"});
        cases.add(new String[]{"/rez/bbb", "this is bbb"});
        cases.add(new String[]{"/rez/ccc", "this is ccc"});
        cases.add(new String[]{"/rez/deep/xxx", "this is just a file named xxx"});
        cases.add(new String[]{"/rez/deep/yyy", "a file named yyy"});
        cases.add(new String[]{"/rez/deep/zzz", "another file, this time named zzz"});
        cases.add(new String[]{"/rez/oddities/;", "a nonescaped semicolon?"});
        cases.add(new String[]{"/rez/oddities/#hashcode", "a file with a hashcode in its name"});
        cases.add(new String[]{"/rez/oddities/index.html#fragment", "a file ending with a hashcode"});
        cases.add(new String[]{"/rez/oddities/other%2fkind%2Fof%2fslash", "other kind of slash"});
        cases.add(new String[]{"/rez/oddities/a file with a space", "a simple file with spaces in their name"});
        cases.add(new String[]{
            "/rez/oddities/;\" onmousedown=\"alert(document.location)\"", "this is the javascript / document.location test file"
        });
        cases.add(new String[]{"/rez/oddities/some\\slash\\you\\got\\there", "this is the slash slash slash file"});
        return cases;
    }

    public static Stream<Arguments> oddJarCases()
    {
        return oddJarEntries().stream()
            .map(entry -> Arguments.of(entry[0], entry[1]));
    }

    public static Stream<Arguments> oddJarRequestCases()
    {
        List<Arguments> cases = new ArrayList<>();

        for (String[] entry : oddJarEntries())
        {
            cases.add(Arguments.of(entry[0], entry[1]));
            cases.add(Arguments.of(URIUtil.encodePath(entry[0]), entry[1]));
        }

        return cases.stream();
    }

    /**
     * Request resource contents directly from Odd Resource.
     */
    @ParameterizedTest
    @MethodSource("oddJarCases")
    public void testOddJarFileResourceAccess(String rawRequestPath, String expectedContent) throws Exception
    {
        // Attempt access using raw path against the Resource directly
        Resource resource = resourceOdd.addPath(rawRequestPath);
        assertThat("Resource exists: " + resource, resource.exists(), is(true));

        try (InputStream in = resource.getInputStream())
        {
            String body = IO.toString(in, StandardCharsets.UTF_8);
            assertThat(body, containsString(expectedContent));
        }
    }

    /**
     * Request "/odd-jar" resource via an HTTP Request
     */
    @ParameterizedTest
    @MethodSource("oddJarRequestCases")
    public void testOddJarFileRequestAccess(String rawRequestPath, String expectedContent) throws Exception
    {
        // Attempt a request of the resource
        StringBuilder req = new StringBuilder();
        req.append("GET /odd-jar").append(rawRequestPath).append(" HTTP/1.1\r\n");
        req.append("Host: local\r\n");
        req.append("Connection: close\r\n");
        req.append("\r\n");

        String rawResponse = connector.getResponse(req.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        String body = response.getContent();
        if (body.contains("class=\"listing\""))
        {
            System.out.println(body);
            throw new IllegalStateException("EEK! a listing!");
        }
        assertThat(body, containsString(expectedContent));
    }
}
