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

package org.eclipse.jetty.ee9.servlet;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.nested.AllowedResourceAliasChecker;
import org.eclipse.jetty.ee9.nested.ResourceContentFactory;
import org.eclipse.jetty.ee9.nested.ResourceService;
import org.eclipse.jetty.ee9.nested.SymlinkAllowedResourceAliasChecker;
import org.eclipse.jetty.http.DateGenerator;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.resource.PathResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jetty.http.tools.matchers.HttpFieldsMatchers.containsHeader;
import static org.eclipse.jetty.http.tools.matchers.HttpFieldsMatchers.containsHeaderValue;
import static org.eclipse.jetty.http.tools.matchers.HttpFieldsMatchers.headerValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@ExtendWith(WorkDirExtension.class)
public class DefaultServletTest
{
    public WorkDir workDir;

    public Path docRoot;

    // The name of the odd-jar used for testing "jar:file://" based resource access.
    private static final String ODD_JAR = "jar-resource-odd.jar";

    private Server server;
    private LocalConnector connector;
    private ServletContextHandler context;

    @BeforeEach
    public void init() throws Exception
    {
        docRoot = workDir.getEmptyPathDir().resolve("docroot");
        FS.ensureDirExists(docRoot);

        server = new Server();

        connector = new LocalConnector(server);
        connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendServerVersion(false);

        File extraJarResources = MavenTestingUtils.getTestResourceFile(ODD_JAR);
        URL[] urls = new URL[]{extraJarResources.toURI().toURL()};

        ClassLoader parentClassLoader = Thread.currentThread().getContextClassLoader();
        URLClassLoader extraClassLoader = new URLClassLoader(urls, parentClassLoader);

        context = new ServletContextHandler();
        context.setBaseResource(new PathResource(docRoot));
        context.setContextPath("/context");
        context.setWelcomeFiles(new String[]{"index.html", "index.jsp", "index.htm"});
        context.setClassLoader(extraClassLoader);

        server.setHandler(context);
        server.addConnector(connector);

        server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        server.stop();
        server.join();
    }

    @Test
    public void testListingWithSession() throws Exception
    {
        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/*");
        defholder.setInitParameter("dirAllowed", "true");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("gzip", "false");

        /* create some content in the docroot */
        FS.ensureDirExists(docRoot.resolve("one"));
        FS.ensureDirExists(docRoot.resolve("two"));
        FS.ensureDirExists(docRoot.resolve("three"));

        String rawResponse = connector.getResponse("GET /context/;JSESSIONID=1234567890 HTTP/1.0\n\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        String body = response.getContent();

        assertThat(body, containsString("/one/;JSESSIONID=1234567890"));
        assertThat(body, containsString("/two/;JSESSIONID=1234567890"));
        assertThat(body, containsString("/three/;JSESSIONID=1234567890"));

        assertThat(body, not(containsString("<script>")));
    }

    @Test
    public void testListingXSS() throws Exception
    {
        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/*");
        defholder.setInitParameter("dirAllowed", "true");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("gzip", "false");

        /* create some content in the docroot */
        Path one = docRoot.resolve("one");
        FS.ensureDirExists(one);
        FS.ensureDirExists(docRoot.resolve("two"));
        FS.ensureDirExists(docRoot.resolve("three"));

        Path alert = one.resolve("onmouseclick='alert(oops)'");
        FS.touch(alert);

        /*
         * Intentionally bad request URI. Sending a non-encoded URI with typically
         * encoded characters '<', '>', and '"'.
         */
        String req1 = "GET /context/;<script>window.alert(\"hi\");</script> HTTP/1.0\r\n" +
            "\r\n";
        String rawResponse = connector.getResponse(req1);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        String body = response.getContent();
        assertThat(body, not(containsString("<script>")));

        req1 = "GET /context/one/;\"onmouseover='alert(document.location)' HTTP/1.0\r\n" +
            "\r\n";

        rawResponse = connector.getResponse(req1);
        response = HttpTester.parseResponse(rawResponse);

        body = response.getContent();

        assertThat(body, not(containsString(";\"onmouseover")));
    }

    @Test
    public void testListingWithQuestionMarks() throws Exception
    {
        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/*");
        defholder.setInitParameter("dirAllowed", "true");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("gzip", "false");

        /* create some content in the docroot */
        FS.ensureDirExists(docRoot.resolve("one"));
        FS.ensureDirExists(docRoot.resolve("two"));
        FS.ensureDirExists(docRoot.resolve("three"));

        // Creating dir 'f??r' (Might not work in Windows)
        assumeMkDirSupported(docRoot, "f??r");

        String rawResponse = connector.getResponse("GET /context/ HTTP/1.0\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        String body = response.getContent();
        assertThat(body, containsString("f??r"));
    }

    /**
     * A regression on windows allowed the directory listing show
     * the fully qualified paths within the directory listing.
     * This test ensures that this behavior will not arise again.
     */
    @Test
    public void testListingFilenamesOnly() throws Exception
    {
        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/*");
        defholder.setInitParameter("dirAllowed", "true");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("gzip", "false");

        /* create some content in the docroot */
        FS.ensureDirExists(docRoot);
        Path one = docRoot.resolve("one");
        FS.ensureDirExists(one);
        Path deep = one.resolve("deep");
        FS.ensureDirExists(deep);
        FS.touch(deep.resolve("foo"));
        FS.ensureDirExists(docRoot.resolve("two"));
        FS.ensureDirExists(docRoot.resolve("three"));

        String resBasePath = docRoot.toAbsolutePath().toString();
        defholder.setInitParameter("resourceBase", resBasePath);

        String req1 = "GET /context/one/deep/ HTTP/1.0\n\n";
        String rawResponse = connector.getResponse(req1);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        String body = response.getContent();
        assertThat(body, containsString("/foo"));
        assertThat(body, not(containsString(resBasePath)));
    }

    /**
     * A regression on windows allowed the directory listing show
     * the fully qualified paths within the directory listing.
     * This test ensures that this behavior will not arise again.
     */
    @Test
    @Disabled // TODO
    public void testListingFilenamesOnlyUrlResource() throws Exception
    {
        URL extraResource = context.getClassLoader().getResource("rez/one");
        assertNotNull(extraResource, "Must have extra jar resource in classloader");

        String extraResourceBaseString = extraResource.toURI().toASCIIString();
        extraResourceBaseString = extraResourceBaseString.substring(0, extraResourceBaseString.length() - "/one".length());

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/extra/*");
        defholder.setInitParameter("resourceBase", extraResourceBaseString);
        defholder.setInitParameter("pathInfoOnly", "true");
        defholder.setInitParameter("dirAllowed", "true");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("gzip", "false");

        StringBuffer req1;
        String rawResponse;
        HttpTester.Response response;
        String body;

        // Test that GET works first.
        req1 = new StringBuffer();
        req1.append("GET /context/extra/one HTTP/1.0\n");
        req1.append("\n");

        rawResponse = connector.getResponse(req1.toString());
        response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        body = response.getContent();
        assertThat(body, containsString("is this the one?"));

        // Typical directory listing of location in jar:file:// URL
        req1 = new StringBuffer();
        req1.append("GET /context/extra/deep/ HTTP/1.0\r\n");
        req1.append("\r\n");

        rawResponse = connector.getResponse(req1.toString());
        response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        body = response.getContent();
        assertThat(body, containsString("/xxx"));
        assertThat(body, containsString("/yyy"));
        assertThat(body, containsString("/zzz"));

        assertThat(body, not(containsString(extraResourceBaseString)));
        assertThat(body, not(containsString(ODD_JAR)));

        // Get deep resource
        req1 = new StringBuffer();
        req1.append("GET /context/extra/deep/yyy HTTP/1.0\r\n");
        req1.append("\r\n");

        rawResponse = connector.getResponse(req1.toString());
        response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        body = response.getContent();
        assertThat(body, containsString("a file named yyy"));

        // Convoluted directory listing of location in jar:file:// URL
        // This exists to test proper encoding output
        req1 = new StringBuffer();
        req1.append("GET /context/extra/oddities/ HTTP/1.0\r\n");
        req1.append("\r\n");

        rawResponse = connector.getResponse(req1.toString());
        response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        body = response.getContent();
        assertThat(body, containsString(">#hashcode&nbsp;<")); // text on page
        assertThat(body, containsString("/oddities/%23hashcode")); // generated link

        assertThat(body, containsString(">other%2fkind%2Fof%2fslash&nbsp;<")); // text on page
        assertThat(body, containsString("/oddities/other%252fkind%252Fof%252fslash")); // generated link

        assertThat(body, containsString(">a file with a space&nbsp;<")); // text on page
        assertThat(body, containsString("/oddities/a%20file%20with%20a%20space")); // generated link

        assertThat(body, not(containsString(extraResourceBaseString)));
        assertThat(body, not(containsString(ODD_JAR)));
    }

    @Test
    @Disabled // TODO
    public void testListingProperUrlEncoding() throws Exception
    {
        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/*");
        defholder.setInitParameter("dirAllowed", "true");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("gzip", "false");

        /* create some content in the docroot */

        Path wackyDir = docRoot.resolve("dir;"); // this should not be double-encoded.
        FS.ensureDirExists(wackyDir);

        FS.ensureDirExists(wackyDir.resolve("four"));
        FS.ensureDirExists(wackyDir.resolve("five"));
        FS.ensureDirExists(wackyDir.resolve("six"));

        /* At this point we have the following
         * testListingProperUrlEncoding/
         * `-- docroot
         *     `-- dir;
         *         |-- five
         *         |-- four
         *         `-- six
         */

        // First send request in improper, unencoded way.
        String rawResponse = connector.getResponse("GET /context/dir;/ HTTP/1.0\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_FOUND_404));

        // Now send request in proper, encoded format.
        rawResponse = connector.getResponse("GET /context/dir%3B/ HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);

        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));

        String body = response.getContent();
        // Should not see double-encoded ";"
        // First encoding: ";" -> "%3b"
        // Second encoding: "%3B" -> "%253B" (BAD!)
        assertThat(body, not(containsString("%253B")));

        assertThat(body, containsString("/dir%3B/"));
        assertThat(body, containsString("/dir%3B/four/"));
        assertThat(body, containsString("/dir%3B/five/"));
        assertThat(body, containsString("/dir%3B/six/"));
    }

    @SuppressWarnings("Duplicates")
    public static Stream<Arguments> contextBreakoutScenarios()
    {
        Scenarios scenarios = new Scenarios();

        scenarios.addScenario(
            "GET normal",
            "GET /context/ HTTP/1.0\r\n\r\n",
            HttpStatus.OK_200,
            (response) -> assertThat(response.getContent(), containsString("<h1>Hello Index</h1>"))
        );

        scenarios.addScenario(
            "GET /context/index.html",
            "GET /context/index.html HTTP/1.0\r\n\r\n",
            HttpStatus.OK_200,
            (response) -> assertThat(response.getContent(), containsString("Hello Index"))
        );

        List<String> notEncodedPrefixes = new ArrayList<>();
        if (!OS.WINDOWS.isCurrentOs())
        {
            notEncodedPrefixes.add("/context/dir?");
        }
        notEncodedPrefixes.add("/context/dir;");

        for (String prefix : notEncodedPrefixes)
        {
            scenarios.addScenario(
                "GET " + prefix,
                "GET " + prefix + " HTTP/1.0\r\n\r\n",
                HttpStatus.NOT_FOUND_404
            );

            scenarios.addScenario(
                "GET " + prefix + "/",
                "GET " + prefix + "/ HTTP/1.0\r\n\r\n",
                HttpStatus.NOT_FOUND_404
            );

            scenarios.addScenario(
                "GET " + prefix + "/../../sekret/pass",
                "GET " + prefix + "/../../sekret/pass HTTP/1.0\r\n\r\n",
                HttpStatus.NOT_FOUND_404,
                (response) -> assertThat(response.getContent(), not(containsString("Sssh")))
            );

            scenarios.addScenario(
                "GET " + prefix + "/..;/..;/sekret/pass",
                "GET " + prefix + "/..;/..;/sekret/pass HTTP/1.0\r\n\r\n",
                prefix.endsWith("?") ? HttpStatus.NOT_FOUND_404 : HttpStatus.BAD_REQUEST_400,
                (response) -> assertThat(response.getContent(), not(containsString("Sssh")))
            );

            scenarios.addScenario(
                "GET " + prefix + "/%2E%2E/%2E%2E/sekret/pass",
                "GET " + prefix + "/%2E%2E/%2E%2E/sekret/pass HTTP/1.0\r\n\r\n",
                prefix.endsWith("?") ? HttpStatus.NOT_FOUND_404 : HttpStatus.BAD_REQUEST_400,
                (response) -> assertThat(response.getContent(), not(containsString("Sssh")))
            );

            // A Raw Question mark in the prefix can be interpreted as a query section
            if (prefix.contains("?"))
            {
                scenarios.addScenario(
                    "GET " + prefix + "/../index.html",
                    "GET " + prefix + "/../index.html HTTP/1.0\r\n\r\n",
                    HttpStatus.NOT_FOUND_404
                );
            }
            else
            {
                scenarios.addScenario(
                    "GET " + prefix + "/../index.html",
                    "GET " + prefix + "/../index.html HTTP/1.0\r\n\r\n",
                    HttpStatus.OK_200,
                    (response) -> assertThat(response.getContent(), containsString("Hello Index"))
                );
            }

            scenarios.addScenario(
                "GET " + prefix + "/%2E%2E/index.html",
                "GET " + prefix + "/%2E%2E/index.html HTTP/1.0\r\n\r\n",
                prefix.endsWith("?") ? HttpStatus.NOT_FOUND_404 : HttpStatus.BAD_REQUEST_400
            );

            scenarios.addScenario(
                "GET " + prefix + "/../../",
                "GET " + prefix + "/../../ HTTP/1.0\r\n\r\n",
                HttpStatus.NOT_FOUND_404,
                (response) ->
                {
                    String body = response.getContent();
                    assertThat(body, not(containsString("Directory: ")));
                }
            );
        }

        List<String> encodedPrefixes = new ArrayList<>();

        if (!OS.WINDOWS.isCurrentOs())
        {
            encodedPrefixes.add("/context/dir%3F");
        }
        encodedPrefixes.add("/context/dir%3B");

        for (String prefix : encodedPrefixes)
        {
            scenarios.addScenario(
                "GET " + prefix,
                "GET " + prefix + " HTTP/1.0\r\n\r\n",
                HttpStatus.MOVED_TEMPORARILY_302,
                (response) -> assertThat("Location header", response.get(HttpHeader.LOCATION), endsWith(prefix + "/"))
            );

            scenarios.addScenario(
                "GET " + prefix + "/",
                "GET " + prefix + "/ HTTP/1.0\r\n\r\n",
                HttpStatus.OK_200
            );

            scenarios.addScenario(
                "GET " + prefix + "/.%2E/.%2E/sekret/pass",
                "GET " + prefix + "/ HTTP/1.0\r\n\r\n",
                HttpStatus.OK_200,
                (response) -> assertThat(response.getContent(), not(containsString("Sssh")))
            );

            scenarios.addScenario(
                "GET " + prefix + "/../index.html",
                "GET " + prefix + "/../index.html HTTP/1.0\r\n\r\n",
                HttpStatus.OK_200,
                (response) -> assertThat(response.getContent(), containsString("Hello Index"))
            );

            scenarios.addScenario(
                "GET " + prefix + "/../../",
                "GET " + prefix + "/../../ HTTP/1.0\r\n\r\n",
                HttpStatus.NOT_FOUND_404,
                (response) ->
                {
                    String body = response.getContent();
                    assertThat(body, containsString("/../../"));
                    assertThat(body, not(containsString("Directory: ")));
                }
            );

            scenarios.addScenario(
                "GET " + prefix + "/../../sekret/pass",
                "GET " + prefix + "/../../sekret/pass HTTP/1.0\r\n\r\n",
                HttpStatus.NOT_FOUND_404,
                (response) -> assertThat(response.getContent(), not(containsString("Sssh")))
            );

            scenarios.addScenario(
                "GET " + prefix + "/../index.html",
                "GET " + prefix + "/../index.html HTTP/1.0\r\n\r\n",
                HttpStatus.OK_200,
                (response) -> assertThat(response.getContent(), containsString("Hello Index"))
            );
        }

        return scenarios.stream();
    }

    @ParameterizedTest
    @MethodSource("contextBreakoutScenarios")
    @Disabled // TODO
    public void testListingContextBreakout(Scenario scenario) throws Exception
    {
        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("dirAllowed", "true");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("gzip", "false");
        defholder.setInitParameter("aliases", "true");

        /* create some content in the docroot */

        Path index = docRoot.resolve("index.html");
        createFile(index, "<h1>Hello Index</h1>");

        if (!OS.WINDOWS.isCurrentOs())
        {
            // FileSystem should support question dirs
            assumeMkDirSupported(docRoot, "dir?");
        }

        // FileSystem should support semicolon dirs
        assumeMkDirSupported(docRoot, "dir;");

        /* create some content outside of the docroot */
        Path sekret = workDir.getPath().resolve("sekret");
        FS.ensureDirExists(sekret);
        Path pass = sekret.resolve("pass");
        createFile(pass, "Sssh, you shouldn't be seeing this");

        /* At this point we have the following
         * testListingContextBreakout/
         * |-- docroot
         * |   |-- index.html
         * |   |-- dir?   (Might be missing on Windows)
         * |   |-- dir;
         * `-- sekret
         *     `-- pass
         */

        String rawResponse = connector.getResponse(scenario.rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(scenario.expectedStatus));
        if (scenario.extraAsserts != null)
            scenario.extraAsserts.accept(response);
    }

    private static void addBasicWelcomeScenarios(Scenarios scenarios)
    {
        scenarios.addScenario(
            "GET /context/one/ (index.htm match)",
            "GET /context/one/ HTTP/1.0\r\n\r\n",
            HttpStatus.OK_200,
            (response) -> assertThat(response.getContent(), containsString("<h1>Hello Inde</h1>"))
        );

        scenarios.addScenario(
            "GET /context/two/ (index.html match)",
            "GET /context/two/ HTTP/1.0\r\n\r\n",
            HttpStatus.OK_200,
            (response) -> assertThat(response.getContent(), containsString("<h1>Hello Index</h1>"))
        );

        scenarios.addScenario(
            "GET /context/three/ (index.html wins over index.htm)",
            "GET /context/three/ HTTP/1.0\r\n\r\n",
            HttpStatus.OK_200,
            (response) -> assertThat(response.getContent(), containsString("<h1>Three Index</h1>"))
        );
    }

    public static Stream<Arguments> welcomeScenarios()
    {
        Scenarios scenarios = new Scenarios();

        scenarios.addScenario(
            "GET /context/ - (no match)",
            "GET /context/ HTTP/1.0\r\n\r\n",
            HttpStatus.FORBIDDEN_403
        );

        addBasicWelcomeScenarios(scenarios);

        return scenarios.stream();
    }

    @ParameterizedTest
    @MethodSource("welcomeScenarios")
    public void testWelcome(Scenario scenario) throws Exception
    {
        Path one = docRoot.resolve("one");
        Path two = docRoot.resolve("two");
        Path three = docRoot.resolve("three");
        FS.ensureDirExists(one);
        FS.ensureDirExists(two);
        FS.ensureDirExists(three);

        createFile(one.resolve("index.htm"), "<h1>Hello Inde</h1>");
        createFile(two.resolve("index.html"), "<h1>Hello Index</h1>");

        createFile(three.resolve("index.html"), "<h1>Three Index</h1>");
        createFile(three.resolve("index.htm"), "<h1>Three Inde</h1>");

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("dirAllowed", "false");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("welcomeServlets", "false");
        defholder.setInitParameter("gzip", "false");

        defholder.setInitParameter("maxCacheSize", "1024000");
        defholder.setInitParameter("maxCachedFileSize", "512000");
        defholder.setInitParameter("maxCachedFiles", "100");

        ServletHolder jspholder = context.addServlet(NoJspServlet.class, "*.jsp");
        context.addServlet(jspholder, "/index.jsp");

        String rawResponse = connector.getResponse(scenario.rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(scenario.expectedStatus));
        if (scenario.extraAsserts != null)
            scenario.extraAsserts.accept(response);
    }

    @Test
    public void testWelcomeMultipleBasesBase() throws Exception
    {
        Path dir = docRoot.resolve("dir");
        FS.ensureDirExists(dir);
        Path inde = dir.resolve("index.htm");
        Path index = dir.resolve("index.html");

        Path altRoot = workDir.getPath().resolve("altroot");
        Path altDir = altRoot.resolve("dir");
        FS.ensureDirExists(altDir);
        Path altInde = altDir.resolve("index.htm");
        Path altIndex = altDir.resolve("index.html");

        ServletHolder altholder = context.addServlet(DefaultServlet.class, "/alt/*");
        altholder.setInitParameter("resourceBase", altRoot.toUri().toASCIIString());
        altholder.setInitParameter("pathInfoOnly", "true");
        altholder.setInitParameter("dirAllowed", "false");
        altholder.setInitParameter("redirectWelcome", "false");
        altholder.setInitParameter("welcomeServlets", "false");
        altholder.setInitParameter("gzip", "false");

        ServletHolder otherholder = context.addServlet(DefaultServlet.class, "/other/*");
        otherholder.setInitParameter("resourceBase", altRoot.toUri().toASCIIString());
        otherholder.setInitParameter("pathInfoOnly", "true");
        otherholder.setInitParameter("dirAllowed", "true");
        otherholder.setInitParameter("redirectWelcome", "false");
        otherholder.setInitParameter("welcomeServlets", "false");
        otherholder.setInitParameter("gzip", "false");

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("dirAllowed", "false");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("welcomeServlets", "false");
        defholder.setInitParameter("gzip", "false");

        @SuppressWarnings("unused")
        ServletHolder jspholder = context.addServlet(NoJspServlet.class, "*.jsp");

        String rawResponse;
        HttpTester.Response response;

        // Test other redirect
        rawResponse = connector.getResponse("GET /context/other HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response, containsHeaderValue("Location", "http://0.0.0.0/context/other/"));

        // Test alt default
        rawResponse = connector.getResponse("GET /context/alt/dir/ HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.FORBIDDEN_403));

        createFile(altIndex, "<h1>Alt Index</h1>");
        rawResponse = connector.getResponse("GET /context/alt/dir/index.html HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("<h1>Alt Index</h1>"));

        rawResponse = connector.getResponse("GET /context/alt/dir/ HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("<h1>Alt Index</h1>"));

        createFile(altInde, "<h1>Alt Inde</h1>");
        rawResponse = connector.getResponse("GET /context/alt/dir/ HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("<h1>Alt Index</h1>"));

        if (deleteFile(altIndex))
        {
            rawResponse = connector.getResponse("GET /context/alt/dir/ HTTP/1.0\r\n\r\n");
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), containsString("<h1>Alt Inde</h1>"));

            if (deleteFile(altInde))
            {
                rawResponse = connector.getResponse("GET /context/alt/dir/ HTTP/1.0\r\n\r\n");
                response = HttpTester.parseResponse(rawResponse);
                assertThat(response.toString(), response.getStatus(), is(HttpStatus.FORBIDDEN_403));
            }
        }

        // Test normal default
        rawResponse = connector.getResponse("GET /context/dir/ HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.FORBIDDEN_403));

        createFile(index, "<h1>Hello Index</h1>");
        rawResponse = connector.getResponse("GET /context/dir/ HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("<h1>Hello Index</h1>"));

        createFile(inde, "<h1>Hello Inde</h1>");
        rawResponse = connector.getResponse("GET /context/dir/ HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("<h1>Hello Index</h1>"));

        if (deleteFile(index))
        {
            rawResponse = connector.getResponse("GET /context/dir/ HTTP/1.0\r\n\r\n");
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), containsString("<h1>Hello Inde</h1>"));

            if (deleteFile(inde))
            {
                rawResponse = connector.getResponse("GET /context/dir/ HTTP/1.0\r\n\r\n");
                response = HttpTester.parseResponse(rawResponse);
                assertThat(response.toString(), response.getStatus(), is(HttpStatus.FORBIDDEN_403));
            }
        }
    }

    @Test
    public void testIncludedWelcomeDifferentBase() throws Exception
    {
        Path altRoot = workDir.getPath().resolve("altroot");
        FS.ensureDirExists(altRoot);
        Path altIndex = altRoot.resolve("index.html");

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/alt/*");
        defholder.setInitParameter("resourceBase", altRoot.toUri().toASCIIString());
        defholder.setInitParameter("dirAllowed", "false");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("welcomeServlets", "true");
        defholder.setInitParameter("pathInfoOnly", "true");

        ServletHolder gwholder = new ServletHolder("gateway", new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                    throws ServletException, IOException
            {
                req.getRequestDispatcher("/alt/").include(req, resp);
            }
        });
        context.addServlet(gwholder, "/gateway");

        String rawResponse;
        HttpTester.Response response;

        // Test included alt default
        rawResponse = connector.getResponse("GET /context/gateway HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        // 9.3 "The Include Method" - when include() is used, FileNotFoundException (and HTTP 500)
        // should be used
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));

        createFile(altIndex, "<h1>Alt Index</h1>");
        rawResponse = connector.getResponse("GET /context/gateway HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("<h1>Alt Index</h1>"));
    }

    @Test
    public void testWelcomeRedirect() throws Exception
    {
        Path dir = docRoot.resolve("dir");
        FS.ensureDirExists(dir);
        Path inde = dir.resolve("index.htm");
        Path index = dir.resolve("index.html");

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("dirAllowed", "false");
        defholder.setInitParameter("redirectWelcome", "true");
        defholder.setInitParameter("welcomeServlets", "false");
        defholder.setInitParameter("gzip", "false");

        defholder.setInitParameter("maxCacheSize", "1024000");
        defholder.setInitParameter("maxCachedFileSize", "512000");
        defholder.setInitParameter("maxCachedFiles", "100");

        @SuppressWarnings("unused")
        ServletHolder jspholder = context.addServlet(NoJspServlet.class, "*.jsp");

        String rawResponse;
        HttpTester.Response response;

        rawResponse = connector.getResponse("GET /context/dir/ HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.FORBIDDEN_403));

        createFile(index, "<h1>Hello Index</h1>");
        rawResponse = connector.getResponse("GET /context/dir/ HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response, headerValue("Location", "http://0.0.0.0/context/dir/index.html"));

        createFile(inde, "<h1>Hello Inde</h1>");
        rawResponse = connector.getResponse("GET /context/dir HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response, headerValue("Location", "http://0.0.0.0/context/dir/"));

        rawResponse = connector.getResponse("GET /context/dir/ HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response, headerValue("Location", "http://0.0.0.0/context/dir/index.html"));

        if (deleteFile(index))
        {
            rawResponse = connector.getResponse("GET /context/dir/ HTTP/1.0\r\n\r\n");
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
            assertThat(response, headerValue("Location", "http://0.0.0.0/context/dir/index.htm"));

            if (deleteFile(inde))
            {
                rawResponse = connector.getResponse("GET /context/dir/ HTTP/1.0\r\n\r\n");
                response = HttpTester.parseResponse(rawResponse);
                assertThat(response.toString(), response.getStatus(), is(HttpStatus.FORBIDDEN_403));
            }
        }
    }

    @Test
    public void testRelativeRedirect() throws Exception
    {
        Path dir = docRoot.resolve("dir");
        FS.ensureDirExists(dir);
        Path index = dir.resolve("index.html");
        createFile(index, "<h1>Hello Index</h1>");

        context.addAliasCheck((p, r) -> true);
        connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setRelativeRedirectAllowed(true);

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("dirAllowed", "false");
        defholder.setInitParameter("redirectWelcome", "true");
        defholder.setInitParameter("welcomeServlets", "false");
        defholder.setInitParameter("gzip", "false");

        defholder.setInitParameter("maxCacheSize", "1024000");
        defholder.setInitParameter("maxCachedFileSize", "512000");
        defholder.setInitParameter("maxCachedFiles", "100");

        String rawResponse;
        HttpTester.Response response;

        rawResponse = connector.getResponse("GET /context/dir HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response, headerValue("Location", "/context/dir/"));

        rawResponse = connector.getResponse("GET /context/dir/ HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response, headerValue("Location", "/context/dir/index.html"));

        rawResponse = connector.getResponse("GET /context/dir/index.html/ HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response, headerValue("Location", "/context/dir/index.html"));
    }

    /**
     * Ensure that oddball directory names are served with proper escaping
     */
    @Test
    @Disabled // TODO
    public void testWelcomeRedirectDirWithQuestion() throws Exception
    {
        FS.ensureDirExists(docRoot);
        Path dir = assumeMkDirSupported(docRoot, "dir?");

        Path index = dir.resolve("index.html");
        createFile(index, "<h1>Hello Index</h1>");

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("dirAllowed", "false");
        defholder.setInitParameter("redirectWelcome", "true");
        defholder.setInitParameter("welcomeServlets", "false");
        defholder.setInitParameter("gzip", "false");

        String rawResponse;
        HttpTester.Response response;

        rawResponse = connector.getResponse("GET /context/dir%3F HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response, containsHeaderValue("Location", "http://0.0.0.0/context/dir%3F/"));

        rawResponse = connector.getResponse("GET /context/dir%3F/ HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response, containsHeaderValue("Location", "http://0.0.0.0/context/dir%3F/index.html"));
    }

    /**
     * Ensure that oddball directory names are served with proper escaping
     */
    @Test
    @Disabled // TODO
    public void testWelcomeRedirectDirWithSemicolon() throws Exception
    {
        FS.ensureDirExists(docRoot);
        Path dir = assumeMkDirSupported(docRoot, "dir;");

        Path index = dir.resolve("index.html");
        createFile(index, "<h1>Hello Index</h1>");

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("dirAllowed", "false");
        defholder.setInitParameter("redirectWelcome", "true");
        defholder.setInitParameter("welcomeServlets", "false");
        defholder.setInitParameter("gzip", "false");

        String rawResponse;
        HttpTester.Response response;

        rawResponse = connector.getResponse("GET /context/dir%3B HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response, containsHeaderValue("Location", "http://0.0.0.0/context/dir%3B/"));

        rawResponse = connector.getResponse("GET /context/dir%3B/ HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response, containsHeaderValue("Location", "http://0.0.0.0/context/dir%3B/index.html"));
    }

    @Test
    public void testWelcomeServlet() throws Exception
    {
        Path inde = docRoot.resolve("index.htm");
        Path index = docRoot.resolve("index.html");

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("dirAllowed", "false");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("welcomeServlets", "true");
        defholder.setInitParameter("gzip", "false");

        @SuppressWarnings("unused")
        ServletHolder jspholder = context.addServlet(NoJspServlet.class, "*.jsp");

        String rawResponse;
        HttpTester.Response response;

        rawResponse = connector.getResponse("GET /context/ HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
        assertThat(response.getContent(), containsString("JSP support not configured"));

        createFile(index, "<h1>Hello Index</h1>");
        rawResponse = connector.getResponse("GET /context/ HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("<h1>Hello Index</h1>"));

        createFile(inde, "<h1>Hello Inde</h1>");
        rawResponse = connector.getResponse("GET /context/ HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("<h1>Hello Index</h1>"));

        if (deleteFile(index))
        {
            rawResponse = connector.getResponse("GET /context/ HTTP/1.0\r\n\r\n");
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), containsString("<h1>Hello Inde</h1>"));

            if (deleteFile(inde))
            {
                rawResponse = connector.getResponse("GET /context/ HTTP/1.0\r\n\r\n");
                response = HttpTester.parseResponse(rawResponse);
                assertThat(response.toString(), response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
                assertThat(response.getContent(), containsString("JSP support not configured"));
            }
        }
    }

    @Test
    public void testSymLinks() throws Exception
    {
        FS.ensureDirExists(docRoot);
        Path dir = docRoot.resolve("dir");
        Path dirLink = docRoot.resolve("dirlink");
        Path dirRLink = docRoot.resolve("dirrlink");
        FS.ensureDirExists(dir);
        Path foobar = dir.resolve("foobar.txt");
        Path link = dir.resolve("link.txt");
        Path rLink = dir.resolve("rlink.txt");
        createFile(foobar, "Foo Bar");
        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");

        defholder.setInitParameter("gzip", "false");

        String rawResponse;
        HttpTester.Response response;

        rawResponse = connector.getResponse("GET /context/dir/foobar.txt HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("Foo Bar"));

        if (!OS.WINDOWS.isCurrentOs())
        {
            context.clearAliasChecks();

            Files.createSymbolicLink(dirLink, dir);
            Files.createSymbolicLink(dirRLink, new File("dir").toPath());
            Files.createSymbolicLink(link, foobar);
            Files.createSymbolicLink(rLink, new File("foobar.txt").toPath());
            rawResponse = connector.getResponse("GET /context/dir/link.txt HTTP/1.0\r\n\r\n");
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_FOUND_404));

            rawResponse = connector.getResponse("GET /context/dir/rlink.txt HTTP/1.0\r\n\r\n");
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_FOUND_404));

            rawResponse = connector.getResponse("GET /context/dirlink/foobar.txt HTTP/1.0\r\n\r\n");
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_FOUND_404));

            rawResponse = connector.getResponse("GET /context/dirrlink/foobar.txt HTTP/1.0\r\n\r\n");
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_FOUND_404));

            rawResponse = connector.getResponse("GET /context/dirlink/link.txt HTTP/1.0\r\n\r\n");
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_FOUND_404));

            rawResponse = connector.getResponse("GET /context/dirrlink/rlink.txt HTTP/1.0\r\n\r\n");
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_FOUND_404));

            context.addAliasCheck(new SymlinkAllowedResourceAliasChecker(context));
            rawResponse = connector.getResponse("GET /context/dir/link.txt HTTP/1.0\r\n\r\n");
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), containsString("Foo Bar"));

            rawResponse = connector.getResponse("GET /context/dir/rlink.txt HTTP/1.0\r\n\r\n");
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), containsString("Foo Bar"));

            rawResponse = connector.getResponse("GET /context/dirlink/foobar.txt HTTP/1.0\r\n\r\n");
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), containsString("Foo Bar"));

            rawResponse = connector.getResponse("GET /context/dirrlink/foobar.txt HTTP/1.0\r\n\r\n");
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), containsString("Foo Bar"));

            rawResponse = connector.getResponse("GET /context/dirlink/link.txt HTTP/1.0\r\n\r\n");
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), containsString("Foo Bar"));

            rawResponse = connector.getResponse("GET /context/dirrlink/link.txt HTTP/1.0\r\n\r\n");
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), containsString("Foo Bar"));
        }
    }

    public static Stream<Arguments> welcomeServletScenarios()
    {
        Scenarios scenarios = new Scenarios();

        scenarios.addScenario(
            "GET /context/ - (/index.jsp servlet match)",
            "GET /context/ HTTP/1.0\r\n\r\n",
            HttpStatus.INTERNAL_SERVER_ERROR_500,
            (response) -> assertThat(response.getContent(), containsString("JSP support not configured"))
        );

        addBasicWelcomeScenarios(scenarios);

        return scenarios.stream();
    }

    @ParameterizedTest
    @MethodSource("welcomeServletScenarios")
    public void testWelcomeExactServlet(Scenario scenario) throws Exception
    {
        FS.ensureDirExists(docRoot);

        Path one = docRoot.resolve("one");
        Path two = docRoot.resolve("two");
        Path three = docRoot.resolve("three");
        FS.ensureDirExists(one);
        FS.ensureDirExists(two);
        FS.ensureDirExists(three);

        createFile(one.resolve("index.htm"), "<h1>Hello Inde</h1>");
        createFile(two.resolve("index.html"), "<h1>Hello Index</h1>");

        createFile(three.resolve("index.html"), "<h1>Three Index</h1>");
        createFile(three.resolve("index.htm"), "<h1>Three Inde</h1>");

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("dirAllowed", "false");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("welcomeServlets", "exact");
        defholder.setInitParameter("gzip", "false");

        ServletHolder jspholder = context.addServlet(NoJspServlet.class, "*.jsp");
        context.addServlet(jspholder, "/index.jsp");

        String rawResponse = connector.getResponse(scenario.rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(scenario.expectedStatus));
        if (scenario.extraAsserts != null)
            scenario.extraAsserts.accept(response);
    }

    @Test
    @Disabled // TODO
    public void testDirectFromResourceHttpContent() throws Exception
    {
        FS.ensureDirExists(docRoot);
        Path index = docRoot.resolve("index.html");
        createFile(index, "<h1>Hello World</h1>");

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("dirAllowed", "true");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("useFileMappedBuffer", "true");
        defholder.setInitParameter("welcomeServlets", "exact");
        defholder.setInitParameter("gzip", "false");
        defholder.setInitParameter("resourceCache", "resourceCache");

        String rawResponse;
        HttpTester.Response response;

        rawResponse = connector.getResponse("GET /context/index.html HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("<h1>Hello World</h1>"));

        ResourceContentFactory factory = (ResourceContentFactory)context.getServletContext().getAttribute("resourceCache");

        HttpContent content = factory.getContent("/index.html", 200);
        ByteBuffer buffer = content.getBuffer();
        assertThat("Buffer is direct", buffer.isDirect(), is(true));
        content = factory.getContent("/index.html", 5);
        buffer = content.getBuffer();
        assertThat("Direct buffer", buffer, is(nullValue()));
    }

    @SuppressWarnings("Duplicates")
    public static Stream<Arguments> rangeScenarios()
    {
        Scenarios scenarios = new Scenarios();

        scenarios.addScenario(
            "No range requested",
            "GET /context/data.txt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n\r\n",
            HttpStatus.OK_200,
            (response) -> assertThat(response, containsHeaderValue(HttpHeader.ACCEPT_RANGES, "bytes"))
        );

        scenarios.addScenario(
            "Simple range request (no-close)",
            "GET /context/data.txt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Range: bytes=0-9\r\n" +
                "\r\n",
            HttpStatus.PARTIAL_CONTENT_206,
            (response) ->
            {
                assertThat(response, containsHeaderValue("Content-Type", "text/plain"));
                assertThat(response, containsHeaderValue("Content-Length", "10"));
                assertThat(response, containsHeaderValue("Content-Range", "bytes 0-9/80"));
            }
        );

        scenarios.addScenario(
            "Simple range request w/close",
            "GET /context/data.txt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Range: bytes=0-9\r\n" +
                "Connection: close\r\n" +
                "\r\n",
            HttpStatus.PARTIAL_CONTENT_206,
            (response) ->
            {
                assertThat(response, containsHeaderValue("Content-Type", "text/plain"));
                assertThat(response, containsHeaderValue("Content-Range", "bytes 0-9/80"));
            });

        scenarios.addScenario(
            "Multiple ranges (x3)",
            "GET /context/data.txt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Range: bytes=0-9,20-29,40-49\r\n" +
                "\r\n",
            HttpStatus.PARTIAL_CONTENT_206,
            (response) ->
            {
                String body = response.getContent();

                assertThat(response, containsHeaderValue("Content-Type", "multipart/byteranges"));
                assertThat(response, containsHeaderValue("Content-Length", "" + body.length()));

                HttpField contentType = response.getField(HttpHeader.CONTENT_TYPE);
                String boundary = getContentTypeBoundary(contentType);

                assertThat("Boundary expected: " + contentType.getValue(), boundary, notNullValue());

                assertThat(body, containsString("Content-Range: bytes 0-9/80"));
                assertThat(body, containsString("Content-Range: bytes 20-29/80"));

                assertThat(response.getContent(), startsWith("--" + boundary));
                assertThat(response.getContent(), endsWith(boundary + "--\r\n"));
            }
        );

        scenarios.addScenario("Multiple ranges (x4)",
            "GET /context/data.txt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Range: bytes=0-9,20-29,40-49,70-79\r\n" +
                "\r\n",
            HttpStatus.PARTIAL_CONTENT_206,
            (response) ->
            {
                String body = response.getContent();

                assertThat(response, containsHeaderValue("Content-Type", "multipart/byteranges"));
                assertThat(response, containsHeaderValue("Content-Length", "" + body.length()));

                HttpField contentType = response.getField(HttpHeader.CONTENT_TYPE);
                String boundary = getContentTypeBoundary(contentType);

                assertThat("Boundary expected: " + contentType.getValue(), boundary, notNullValue());

                assertThat(body, containsString("Content-Range: bytes 0-9/80"));
                assertThat(body, containsString("Content-Range: bytes 20-29/80"));
                assertThat(body, containsString("Content-Range: bytes 70-79/80"));

                assertThat(response.getContent(), startsWith("--" + boundary));
                assertThat(response.getContent(), endsWith(boundary + "--\r\n"));
            }
        );

        scenarios.addScenario(
            "Multiple ranges (x4) with empty range request",
            "GET /context/data.txt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Range: bytes=0-9,20-29,40-49,60-60,70-79\r\n" +
                "\r\n",
            HttpStatus.PARTIAL_CONTENT_206,
            (response) ->
            {
                String body = response.getContent();

                assertThat(response, containsHeaderValue("Content-Type", "multipart/byteranges"));
                assertThat(response, containsHeaderValue("Content-Length", "" + body.length()));

                HttpField contentType = response.getField(HttpHeader.CONTENT_TYPE);
                String boundary = getContentTypeBoundary(contentType);

                assertThat("Boundary expected: " + contentType.getValue(), boundary, notNullValue());

                assertThat(body, containsString("Content-Range: bytes 0-9/80"));
                assertThat(body, containsString("Content-Range: bytes 20-29/80"));
                assertThat(body, containsString("Content-Range: bytes 60-60/80")); // empty range request
                assertThat(body, containsString("Content-Range: bytes 70-79/80"));

                assertThat(response.getContent(), startsWith("--" + boundary));
                assertThat(response.getContent(), endsWith(boundary + "--\r\n"));
            }
        );

        //test a range request with a file with no suffix, therefore no mimetype

        scenarios.addScenario(
            "No mimetype resource - no range requested",
            "GET /context/nofilesuffix HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n",
            HttpStatus.OK_200,
            (response) -> assertThat(response, containsHeaderValue(HttpHeader.ACCEPT_RANGES, "bytes"))
        );

        scenarios.addScenario(
            "No mimetype resource - simple range request",
            "GET /context/nofilesuffix HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Range: bytes=0-9\r\n" +
                "\r\n",
            HttpStatus.PARTIAL_CONTENT_206,
            (response) ->
            {
                assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "10"));
                assertThat(response, containsHeaderValue(HttpHeader.CONTENT_RANGE, "bytes 0-9/80"));
                assertThat(response, not(containsHeader(HttpHeader.CONTENT_TYPE)));
            }
        );

        scenarios.addScenario(
            "No mimetype resource - multiple ranges (x3)",
            "GET /context/nofilesuffix HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Range: bytes=0-9,20-29,40-49\r\n" +
                "\r\n",
            HttpStatus.PARTIAL_CONTENT_206,
            (response) ->
            {
                String body = response.getContent();

                assertThat(response, containsHeaderValue("Content-Type", "multipart/byteranges"));
                assertThat(response, containsHeaderValue("Content-Length", "" + body.length()));

                HttpField contentType = response.getField(HttpHeader.CONTENT_TYPE);
                String boundary = getContentTypeBoundary(contentType);

                assertThat("Boundary expected: " + contentType.getValue(), boundary, notNullValue());

                assertThat(body, containsString("Content-Range: bytes 0-9/80"));
                assertThat(body, containsString("Content-Range: bytes 20-29/80"));

                assertThat(response.getContent(), startsWith("--" + boundary));
                assertThat(response.getContent(), endsWith(boundary + "--\r\n"));
            }
        );

        scenarios.addScenario(
            "No mimetype resource - multiple ranges (x5) with empty range request",
            "GET /context/nofilesuffix HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Range: bytes=0-9,20-29,40-49,60-60,70-79\r\n" +
                "\r\n",
            HttpStatus.PARTIAL_CONTENT_206,
            (response) ->
            {
                String body = response.getContent();

                assertThat(response, containsHeaderValue("Content-Type", "multipart/byteranges"));
                assertThat(response, containsHeaderValue("Content-Length", "" + body.length()));

                HttpField contentType = response.getField(HttpHeader.CONTENT_TYPE);
                String boundary = getContentTypeBoundary(contentType);

                assertThat("Boundary expected: " + contentType.getValue(), boundary, notNullValue());

                assertThat(body, containsString("Content-Range: bytes 0-9/80"));
                assertThat(body, containsString("Content-Range: bytes 20-29/80"));
                assertThat(body, containsString("Content-Range: bytes 40-49/80"));
                assertThat(body, containsString("Content-Range: bytes 60-60/80")); // empty range
                assertThat(body, containsString("Content-Range: bytes 70-79/80"));

                assertThat(response.getContent(), startsWith("--" + boundary));
                assertThat(response.getContent(), endsWith(boundary + "--\r\n"));
            }
        );

        return scenarios.stream();
    }

    @ParameterizedTest
    @MethodSource("rangeScenarios")
    public void testRangeRequests(Scenario scenario) throws Exception
    {
        FS.ensureDirExists(docRoot);
        Path data = docRoot.resolve("data.txt");
        createFile(data, "01234567890123456789012345678901234567890123456789012345678901234567890123456789");

        // test a range request with a file with no suffix, therefore no mimetype
        Path nofilesuffix = docRoot.resolve("nofilesuffix");
        createFile(nofilesuffix, "01234567890123456789012345678901234567890123456789012345678901234567890123456789");

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("dirAllowed", "false");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("welcomeServlets", "false");
        defholder.setInitParameter("gzip", "false");
        defholder.setInitParameter("acceptRanges", "true");

        String rawResponse = connector.getResponse(scenario.rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(scenario.expectedStatus));
        if (scenario.extraAsserts != null)
            scenario.extraAsserts.accept(response);
    }

    @Test
    public void testFiltered() throws Exception
    {
        FS.ensureDirExists(docRoot);
        Path file0 = docRoot.resolve("data0.txt");
        createFile(file0, "Hello Text 0");
        Path image = docRoot.resolve("image.jpg");
        createFile(image, "not an image");

        server.stop();
        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("dirAllowed", "false");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("welcomeServlets", "false");
        defholder.setInitParameter("gzip", "false");
        server.start();

        String rawResponse;
        HttpTester.Response response;
        String body;

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "12"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, not(containsHeaderValue(HttpHeader.CONTENT_TYPE, "charset=")));
        body = response.getContent();
        assertThat(body, not(containsString("Extra Info")));

        server.stop();
        context.addFilter(OutputFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        server.start();

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        body = response.getContent();
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "" + body.length()));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain;charset=UTF-8"));
        assertThat(body, containsString("Extra Info"));

        rawResponse = connector.getResponse("GET /context/image.jpg HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        body = response.getContent();
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "" + body.length()));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "image/jpeg;charset=utf-8"));
        assertThat(body, containsString("Extra Info"));

        server.stop();
        context.getServletHandler().setFilterMappings(new FilterMapping[]{});
        context.getServletHandler().setFilters(new FilterHolder[]{});
        context.addFilter(WriterFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        server.start();

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        body = response.getContent();
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(body, containsString("Extra Info"));
    }

    @Test
    @Disabled // TODO
    public void testGzip() throws Exception
    {
        FS.ensureDirExists(docRoot);
        Path file0 = docRoot.resolve("data0.txt");
        createFile(file0, "Hello Text 0");
        Path file0gz = docRoot.resolve("data0.txt.gz");
        createFile(file0gz, "fake gzip");

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("dirAllowed", "false");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("welcomeServlets", "false");
        defholder.setInitParameter("gzip", "true");
        defholder.setInitParameter("etags", "true");

        String rawResponse;
        HttpTester.Response response;
        String body;

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "12"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, containsHeader(HttpHeader.ETAG));
        assertThat(response, not(containsHeaderValue(HttpHeader.CONTENT_ENCODING, "gzip")));
        body = response.getContent();
        assertThat(body, containsString("Hello Text 0"));
        String etag = response.get(HttpHeader.ETAG);
        String etagGzip = etag.replaceFirst("([^\"]*)\"(.*)\"", "$1\"$2--gzip\"");

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:gzip\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "9"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_ENCODING, "gzip"));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagGzip));
        body = response.getContent();
        assertThat(body, containsString("fake gzip"));

        rawResponse = connector.getResponse("GET /context/data0.txt.gz HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:gzip\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "9"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "application/gzip"));
        assertThat(response, not(containsHeader(HttpHeader.VARY)));
        assertThat(response, not(containsHeader(HttpHeader.CONTENT_ENCODING)));
        assertThat("Should not contain gzip variant", response, not(containsHeaderValue(HttpHeader.ETAG, etagGzip)));
        assertThat("Should have a different ETag", response, containsHeader(HttpHeader.ETAG));
        body = response.getContent();
        assertThat(body, containsString("fake gzip"));

        rawResponse = connector.getResponse("GET /context/data0.txt.gz HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:gzip\r\nIf-None-Match: W/\"wobble\"\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "9"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "application/gzip"));
        assertThat(response, not(containsHeader(HttpHeader.VARY)));
        assertThat(response, not(containsHeader(HttpHeader.CONTENT_ENCODING)));
        assertThat("Should not contain gzip variant", response, not(containsHeaderValue(HttpHeader.ETAG, etagGzip)));
        assertThat("Should have a different ETag", response, containsHeader(HttpHeader.ETAG));
        body = response.getContent();
        assertThat(body, containsString("fake gzip"));

        String badEtagGzip = etag.replaceFirst("([^\"]*)\"(.*)\"", "$1\"$2X--gzip\"");
        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:gzip\r\nIf-None-Match: " + badEtagGzip + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(not(HttpStatus.NOT_MODIFIED_304)));

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:gzip\r\nIf-None-Match: " + etagGzip + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagGzip));

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:gzip\r\nIf-None-Match: " + etag + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etag));

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:gzip\r\nIf-None-Match: W/\"foobar\"," + etagGzip + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagGzip));

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:gzip\r\nIf-None-Match: W/\"foobar\"," + etag + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etag));
    }

    @Test
    @Disabled // TODO
    public void testCachedGzip() throws Exception
    {
        FS.ensureDirExists(docRoot);
        Path file0 = docRoot.resolve("data0.txt");
        createFile(file0, "Hello Text 0");
        Path file0gz = docRoot.resolve("data0.txt.gz");
        createFile(file0gz, "fake gzip");

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("dirAllowed", "false");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("welcomeServlets", "false");
        defholder.setInitParameter("gzip", "true");
        defholder.setInitParameter("etags", "true");

        defholder.setInitParameter("maxCachedFiles", "1024");
        defholder.setInitParameter("maxCachedFileSize", "200000000");
        defholder.setInitParameter("maxCacheSize", "256000000");

        String rawResponse;
        HttpTester.Response response;
        String body;

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "12"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, not(containsHeader(HttpHeader.CONTENT_ENCODING)));
        assertThat(response, containsHeader(HttpHeader.ETAG));
        body = response.getContent();
        assertThat(body, containsString("Hello Text 0"));

        String etag = response.get(HttpHeader.ETAG);
        String etagGzip = etag.replaceFirst("([^\"]*)\"(.*)\"", "$1\"$2--gzip\"");

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:gzip\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "9"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_ENCODING, "gzip"));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagGzip));
        body = response.getContent();
        assertThat(body, containsString("fake gzip"));

        rawResponse = connector.getResponse("GET /context/data0.txt.gz HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:gzip\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "9"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "application/gzip"));
        assertThat(response, not(containsHeader(HttpHeader.VARY)));
        assertThat(response, not(containsHeader(HttpHeader.CONTENT_ENCODING)));
        assertThat("Should not contain gzip variant", response, not(containsHeaderValue(HttpHeader.ETAG, etagGzip)));
        assertThat("Should have a different ETag", response, containsHeader(HttpHeader.ETAG));
        body = response.getContent();
        assertThat(body, containsString("fake gzip"));

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:gzip\r\nIf-None-Match: " + etagGzip + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagGzip));

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:gzip\r\nIf-None-Match: " + etag + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etag));

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:gzip\r\nIf-None-Match: W/\"foobar\"," + etagGzip + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagGzip));

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:gzip\r\nIf-None-Match: W/\"foobar\"," + etag + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etag));
    }

    @Test
    @Disabled // TODO
    public void testBrotli() throws Exception
    {
        createFile(docRoot.resolve("data0.txt"), "Hello Text 0");
        createFile(docRoot.resolve("data0.txt.br"), "fake brotli");

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("dirAllowed", "false");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("welcomeServlets", "false");
        defholder.setInitParameter("precompressed", "true");
        defholder.setInitParameter("etags", "true");

        String rawResponse;
        HttpTester.Response response;
        String body;

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "12"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, not(containsHeader(HttpHeader.CONTENT_ENCODING)));
        assertThat(response, containsHeader(HttpHeader.ETAG));
        body = response.getContent();
        assertThat(body, containsString("Hello Text 0"));

        String etag = response.get(HttpHeader.ETAG);
        String etagBr = etag.replaceFirst("([^\"]*)\"(.*)\"", "$1\"$2--br\"");

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:gzip;q=0.9,br\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "11"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_ENCODING, "br"));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagBr));
        body = response.getContent();
        assertThat(body, containsString("fake br"));

        rawResponse = connector.getResponse("GET /context/data0.txt.br HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:br,gzip\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "11"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "application/brotli"));
        assertThat(response, not(containsHeader(HttpHeader.VARY)));
        assertThat(response, not(containsHeader(HttpHeader.CONTENT_ENCODING)));
        assertThat("Should not contain br variant", response, not(containsHeaderValue(HttpHeader.ETAG, etagBr)));
        assertThat("Should have a different ETag", response, containsHeader(HttpHeader.ETAG));
        body = response.getContent();
        assertThat(body, containsString("fake br"));

        rawResponse = connector.getResponse("GET /context/data0.txt.br HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:gzip\r\nIf-None-Match: W/\"wobble\"\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "11"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "application/brotli"));
        assertThat(response, not(containsHeader(HttpHeader.VARY)));
        assertThat(response, not(containsHeader(HttpHeader.CONTENT_ENCODING)));
        assertThat("Should not contain br variant", response, not(containsHeaderValue(HttpHeader.ETAG, etagBr)));
        assertThat("Should have a different ETag", response, containsHeader(HttpHeader.ETAG));
        body = response.getContent();
        assertThat(body, containsString("fake br"));

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:br\r\nIf-None-Match: " + etagBr + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagBr));

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:br\r\nIf-None-Match: " + etag + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etag));

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:br\r\nIf-None-Match: W/\"foobar\"," + etagBr + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagBr));

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:br\r\nIf-None-Match: W/\"foobar\"," + etag + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etag));
    }

    @Test
    @Disabled // TODO
    public void testCachedBrotli() throws Exception
    {
        createFile(docRoot.resolve("data0.txt"), "Hello Text 0");
        createFile(docRoot.resolve("data0.txt.br"), "fake brotli");

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("dirAllowed", "false");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("welcomeServlets", "false");
        defholder.setInitParameter("precompressed", "true");
        defholder.setInitParameter("etags", "true");

        defholder.setInitParameter("maxCachedFiles", "1024");
        defholder.setInitParameter("maxCachedFileSize", "200000000");
        defholder.setInitParameter("maxCacheSize", "256000000");

        String rawResponse;
        HttpTester.Response response;
        String body;

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "12"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, not(containsHeader(HttpHeader.CONTENT_ENCODING)));
        assertThat(response, containsHeader(HttpHeader.ETAG));
        body = response.getContent();
        assertThat(body, containsString("Hello Text 0"));

        String etag = response.get(HttpHeader.ETAG);
        String etagBr = etag.replaceFirst("([^\"]*)\"(.*)\"", "$1\"$2--br\"");

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:br\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "11"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_ENCODING, "br"));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagBr));
        body = response.getContent();
        assertThat(body, containsString("fake brotli"));

        rawResponse = connector.getResponse("GET /context/data0.txt.br HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:br\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "11"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "application/brotli"));
        assertThat(response, not(containsHeader(HttpHeader.VARY)));
        assertThat(response, not(containsHeader(HttpHeader.CONTENT_ENCODING)));
        assertThat("Should not contain br variant", response, not(containsHeaderValue(HttpHeader.ETAG, etagBr)));
        assertThat("Should have a different ETag", response, containsHeader(HttpHeader.ETAG));
        body = response.getContent();
        assertThat(body, containsString("fake brotli"));

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:br\r\nIf-None-Match: " + etagBr + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagBr));

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:br\r\nIf-None-Match: " + etag + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etag));

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:br\r\nIf-None-Match: W/\"foobar\"," + etagBr + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagBr));

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:br\r\nIf-None-Match: W/\"foobar\"," + etag + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etag));
    }

    @Test
    @Disabled // TODO
    public void testDefaultBrotliOverGzip() throws Exception
    {
        createFile(docRoot.resolve("data0.txt"), "Hello Text 0");
        createFile(docRoot.resolve("data0.txt.br"), "fake brotli");
        createFile(docRoot.resolve("data0.txt.gz"), "fake gzip");

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("precompressed", "true");
        defholder.setInitParameter("resourceBase", docRoot.toString());

        String rawResponse;
        HttpTester.Response response;
        String body;

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:gzip, compress, br\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "11"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_ENCODING, "br"));
        body = response.getContent();
        assertThat(body, containsString("fake brotli"));

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:gzip, compress, br;q=0.9\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "9"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_ENCODING, "gzip"));
        body = response.getContent();
        assertThat(body, containsString("fake gzip"));
    }

    @Test
    @Disabled // TODO
    public void testCustomCompressionFormats() throws Exception
    {
        createFile(docRoot.resolve("data0.txt"), "Hello Text 0");
        createFile(docRoot.resolve("data0.txt.br"), "fake brotli");
        createFile(docRoot.resolve("data0.txt.gz"), "fake gzip");
        createFile(docRoot.resolve("data0.txt.bz2"), "fake bzip2");

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("precompressed", "bzip2=.bz2,gzip=.gz,br=.br");
        defholder.setInitParameter("resourceBase", docRoot.toString());

        String rawResponse;
        HttpTester.Response response;
        String body;

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:bzip2, br, gzip\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "10"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_ENCODING, "bzip2"));
        body = response.getContent();
        assertThat(body, containsString("fake bzip2"));

        // TODO: show accept-encoding search order issue (shouldn't this request return data0.txt.br?)

        rawResponse = connector.getResponse("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:br, gzip\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "9"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_ENCODING, "gzip"));
        body = response.getContent();
        assertThat(body, containsString("fake gzip"));
    }

    @Test
    public void testControlCharacter() throws Exception
    {
        FS.ensureDirExists(docRoot);
        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("resourceBase", docRoot.toFile().getAbsolutePath());

        try (StacklessLogging ignore = new StacklessLogging(ResourceService.class))
        {
            String rawResponse = connector.getResponse("GET /context/%0a HTTP/1.1\r\nHost: local\r\nConnection: close\r\n\r\n");
            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat("Response.status", response.getStatus(), anyOf(is(HttpServletResponse.SC_NOT_FOUND), is(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)));
            assertThat("Response.content", response.getContent(), is(not(containsString(docRoot.toString()))));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Hello World",
        "Now is the time for all good men to come to the aid of the party"
    })
    public void testIfModified(String content) throws Exception
    {
        Path file = docRoot.resolve("file.txt");

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");

        defholder.setInitParameter("maxCacheSize", "4096");
        defholder.setInitParameter("maxCachedFileSize", "25");
        defholder.setInitParameter("maxCachedFiles", "100");

        String rawResponse;
        HttpTester.Response response;

        rawResponse = connector.getResponse("GET /context/file.txt HTTP/1.0\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_FOUND_404));

        createFile(file, content);

        rawResponse = connector.getResponse("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeader(HttpHeader.LAST_MODIFIED));

        String lastModified = response.get(HttpHeader.LAST_MODIFIED);

        rawResponse = connector.getResponse("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-Modified-Since: " + lastModified + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));

        rawResponse = connector.getResponse("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-Modified-Since: " + DateGenerator.formatDate(System.currentTimeMillis() - 10000) + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));

        rawResponse = connector.getResponse("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-Modified-Since: " + DateGenerator.formatDate(System.currentTimeMillis() + 10000) + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));

        rawResponse = connector.getResponse("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-Unmodified-Since: " + DateGenerator.formatDate(System.currentTimeMillis() + 10000) + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));

        rawResponse = connector.getResponse("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-Unmodified-Since: " + DateGenerator.formatDate(System.currentTimeMillis() - 10000) + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.PRECONDITION_FAILED_412));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Hello World",
        "Now is the time for all good men to come to the aid of the party"
    })
    public void testIfETag(String content) throws Exception
    {
        createFile(docRoot.resolve("file.txt"), content);

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");

        defholder.setInitParameter("maxCacheSize", "4096");
        defholder.setInitParameter("maxCachedFileSize", "25");
        defholder.setInitParameter("maxCachedFiles", "100");
        defholder.setInitParameter("etags", "true");

        String rawResponse;
        HttpTester.Response response;

        rawResponse = connector.getResponse("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeader(HttpHeader.ETAG));

        String etag = response.get(HttpHeader.ETAG);

        rawResponse = connector.getResponse("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-None-Match: " + etag + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));

        rawResponse = connector.getResponse("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-None-Match: wibble," + etag + ",wobble\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));

        rawResponse = connector.getResponse("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-None-Match: wibble\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));

        rawResponse = connector.getResponse("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-None-Match: wibble, wobble\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));

        rawResponse = connector.getResponse("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-Match: " + etag + "\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));

        rawResponse = connector.getResponse("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-Match: wibble," + etag + ",wobble\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));

        rawResponse = connector.getResponse("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-Match: wibble\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.PRECONDITION_FAILED_412));

        rawResponse = connector.getResponse("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-Match: wibble, wobble\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.PRECONDITION_FAILED_412));
    }

    @Test
    public void testGetUtf8NfcFile() throws Exception
    {
        FS.ensureEmpty(docRoot);

        context.addServlet(DefaultServlet.class, "/");
        context.addAliasCheck(new AllowedResourceAliasChecker(context));

        // Create file with UTF-8 NFC format
        String filename = "swedish-" + new String(TypeUtil.fromHexString("C3A5"), UTF_8) + ".txt";
        createFile(docRoot.resolve(filename), "hi a-with-circle");

        // Using filesystem, attempt to access via NFD format
        Path nfdPath = docRoot.resolve("swedish-a" + new String(TypeUtil.fromHexString("CC8A"), UTF_8) + ".txt");
        boolean filesystemSupportsNFDAccess = Files.exists(nfdPath);

        // Make requests
        String rawResponse;
        HttpTester.Response response;

        // Request as UTF-8 NFC
        rawResponse = connector.getResponse("GET /context/swedish-%C3%A5.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), is("hi a-with-circle"));

        // Request as UTF-8 NFD
        rawResponse = connector.getResponse("GET /context/swedish-a%CC%8A.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        if (filesystemSupportsNFDAccess)
        {
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), is("hi a-with-circle"));
        }
        else
        {
            assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));
        }
    }

    @Test
    public void testGetUtf8NfdFile() throws Exception
    {
        FS.ensureEmpty(docRoot);

        context.addServlet(DefaultServlet.class, "/");
        context.addAliasCheck(new AllowedResourceAliasChecker(context));

        // Create file with UTF-8 NFD format
        String filename = "swedish-a" + new String(TypeUtil.fromHexString("CC8A"), UTF_8) + ".txt";
        createFile(docRoot.resolve(filename), "hi a-with-circle");

        // Using filesystem, attempt to access via NFC format
        Path nfcPath = docRoot.resolve("swedish-" + new String(TypeUtil.fromHexString("C3A5"), UTF_8) + ".txt");
        boolean filesystemSupportsNFCAccess = Files.exists(nfcPath);

        String rawResponse;
        HttpTester.Response response;

        // Request as UTF-8 NFD
        rawResponse = connector.getResponse("GET /context/swedish-a%CC%8A.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), is("hi a-with-circle"));

        // Request as UTF-8 NFC
        rawResponse = connector.getResponse("GET /context/swedish-%C3%A5.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\n\r\n");
        response = HttpTester.parseResponse(rawResponse);
        if (filesystemSupportsNFCAccess)
        {
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), is("hi a-with-circle"));
        }
        else
        {
            assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));
        }
    }

    public static class OutputFilter implements Filter
    {
        @Override
        public void init(FilterConfig filterConfig)
        {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            response.getOutputStream().println("Extra Info");
            response.setCharacterEncoding("utf-8");
            chain.doFilter(request, response);
        }

        @Override
        public void destroy()
        {
        }
    }

    public static class WriterFilter implements Filter
    {
        @Override
        public void init(FilterConfig filterConfig)
        {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            response.getWriter().println("Extra Info");
            chain.doFilter(request, response);
        }

        @Override
        public void destroy()
        {
        }
    }

    private void createFile(Path path, String str) throws IOException
    {
        try (OutputStream out = Files.newOutputStream(path))
        {
            out.write(str.getBytes(UTF_8));
            out.flush();
        }
    }

    private boolean deleteFile(Path file) throws IOException
    {
        if (!Files.exists(file))
            return true;

        // Some OS's (Windows) do not seem to like to delete content that was recently created.
        // Attempt a delete and if it fails, attempt a rename/move.
        try
        {
            Files.delete(file);
        }
        catch (IOException ignore)
        {
            Path deletedDir = MavenTestingUtils.getTargetTestingPath(".deleted");
            FS.ensureDirExists(deletedDir);
            Path dest = Files.createTempFile(deletedDir, file.getFileName().toString(), "deleted");
            try
            {
                Files.move(file, dest);
            }
            catch (UnsupportedOperationException | IOException e)
            {
                System.err.println("WARNING: unable to move file out of the way: " + file);
            }
        }

        return !Files.exists(file);
    }

    private static String getContentTypeBoundary(HttpField contentType)
    {
        Pattern pat = Pattern.compile("boundary=([a-zA-Z0-9]*)");
        for (String value : contentType.getValues())
        {
            Matcher mat = pat.matcher(value);
            if (mat.find())
                return mat.group(1);
        }

        return null;
    }

    /**
     * Attempt to create the directory, skip testcase if not supported on OS.
     */
    private static Path assumeMkDirSupported(Path path, String subpath)
    {
        Path ret = null;

        try
        {
            ret = path.resolve(subpath);

            if (Files.exists(ret))
                return ret;

            Files.createDirectories(ret);
        }
        catch (InvalidPathException | IOException ignore)
        {
            // ignore
        }

        assumeTrue(ret != null, "Directory creation not supported on OS: " + path + File.separator + subpath);
        assumeTrue(Files.exists(ret), "Directory creation not supported on OS: " + ret);
        return ret;
    }

    public static class Scenarios extends ArrayList<Arguments>
    {
        public void addScenario(String description, String rawRequest, int expectedStatus)
        {
            add(Arguments.of(new Scenario(description, rawRequest, expectedStatus)));
        }

        public void addScenario(String description, String rawRequest, int expectedStatus, Consumer<HttpTester.Response> extraAsserts)
        {
            add(Arguments.of(new Scenario(description, rawRequest, expectedStatus, extraAsserts)));
        }
    }

    public static class Scenario
    {
        private final String description;
        public final String rawRequest;
        public final int expectedStatus;
        public Consumer<HttpTester.Response> extraAsserts;

        public Scenario(String description, String rawRequest, int expectedStatus)
        {
            this.description = description;
            this.rawRequest = rawRequest;
            this.expectedStatus = expectedStatus;
        }

        public Scenario(String description, String rawRequest, int expectedStatus, Consumer<HttpTester.Response> extraAsserts)
        {
            this(description, rawRequest, expectedStatus);
            this.extraAsserts = extraAsserts;
        }

        @Override
        public String toString()
        {
            return description;
        }
    }
}
