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

package org.eclipse.jetty.ee10.webapp;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.servlet.GenericServlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.eclipse.jetty.ee10.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class WebAppContextTest
{
    public static final Logger LOG = LoggerFactory.getLogger(WebAppContextTest.class);
    public WorkDir workDir;
    private final List<Object> lifeCycles = new ArrayList<>();

    @AfterEach
    public void tearDown()
    {
        lifeCycles.forEach(LifeCycle::stop);
        Configurations.cleanKnown();
    }

    private Server newServer()
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);
        lifeCycles.add(server);
        return server;
    }

    @Test
    public void testDefaultContextPath() throws Exception
    {
        Server server = newServer();
        File webXml = MavenTestingUtils.getTestResourceFile("web-with-default-context-path.xml");
        File webXmlEmptyPath = MavenTestingUtils.getTestResourceFile("web-with-empty-default-context-path.xml");
        File webDefaultXml = MavenTestingUtils.getTestResourceFile("web-default-with-default-context-path.xml");
        File overrideWebXml = MavenTestingUtils.getTestResourceFile("override-web-with-default-context-path.xml");
        assertNotNull(webXml);
        assertNotNull(webDefaultXml);
        assertNotNull(overrideWebXml);
        assertNotNull(webXmlEmptyPath);

        WebAppContext wac = new WebAppContext();
        wac.setBaseResource(MavenTestingUtils.getTargetTestingDir().getAbsoluteFile().toPath());
        server.setHandler(wac);

        //test that an empty default-context-path defaults to root
        wac.setDescriptor(webXmlEmptyPath.getAbsolutePath());
        server.start();
        assertEquals("/", wac.getContextPath());

        server.stop();

        //test web-default.xml value is used
        wac.setDescriptor(null);
        wac.setDefaultsDescriptor(webDefaultXml.getAbsolutePath());
        server.start();
        assertEquals("/one", wac.getContextPath());

        server.stop();

        //test web.xml value is used
        wac.setDescriptor(webXml.getAbsolutePath());
        server.start();
        assertEquals("/two", wac.getContextPath());

        server.stop();

        //test override-web.xml value is used
        wac.setOverrideDescriptor(overrideWebXml.getAbsolutePath());
        server.start();
        assertEquals("/three", wac.getContextPath());

        server.stop();

        //test that explicitly set context path is used instead
        wac.setContextPath("/foo");
        server.start();
        assertEquals("/foo", wac.getContextPath());
    }

    @Test
    public void testConfigurationClassesFromDefault()
    {
        Configurations.cleanKnown();
        String[] knownAndEnabled = Configurations.getKnown().stream()
            .filter(Configuration::isEnabledByDefault)
            .map(c -> c.getClass().getName())
            .toArray(String[]::new);

        Server server = newServer();

        //test if no classnames set, its the defaults
        WebAppContext wac = new WebAppContext();
        assertThat(wac.getConfigurations().stream()
                .map(c -> c.getClass().getName())
                .collect(Collectors.toList()),
            Matchers.containsInAnyOrder(knownAndEnabled));
        String[] classNames = wac.getConfigurationClasses();
        assertNotNull(classNames);

        // test if no classname set, and none from server its the defaults
        wac.setServer(server);
        assertTrue(Arrays.equals(classNames, wac.getConfigurationClasses()));
    }

    @Test
    public void testConfigurationOrder()
    {
        Configurations.cleanKnown();
        WebAppContext wac = new WebAppContext();
        wac.setServer(new Server());
        List<String> actualConfigurations = wac.getConfigurations().stream().map(c -> c.getClass().getName()).collect(Collectors.toList());
        List<String> expectedConfigurations = new ArrayList<>();

        JmxConfiguration jmx = new JmxConfiguration();
        if (jmx.isAvailable()) // depending on JVM runtime, this might not be available when this test is run
        {
            expectedConfigurations.add("org.eclipse.jetty.webapp.JmxConfiguration");
        }
        expectedConfigurations.add("org.eclipse.jetty.ee10.webapp.WebInfConfiguration");
        expectedConfigurations.add("org.eclipse.jetty.ee10.webapp.WebXmlConfiguration");
        expectedConfigurations.add("org.eclipse.jetty.ee10.webapp.MetaInfConfiguration");
        expectedConfigurations.add("org.eclipse.jetty.ee10.webapp.FragmentConfiguration");
        expectedConfigurations.add("org.eclipse.jetty.ee10.webapp.WebAppConfiguration");
        expectedConfigurations.add("org.eclipse.jetty.ee10.webapp.JettyWebXmlConfiguration");

        assertThat(actualConfigurations, Matchers.contains(expectedConfigurations.toArray()));
    }

    @Test
    public void testConfigurationInstances()
    {
        Configurations.cleanKnown();
        Configuration[] configs = {new WebInfConfiguration()};
        WebAppContext wac = new WebAppContext();
        wac.setConfigurations(configs);
        assertThat(wac.getConfigurations(), Matchers.contains(configs));

        //test that explicit config instances override any from server
        String[] classNames = {"x.y.z"};
        Server server = newServer();
        server.setAttribute(Configuration.ATTR, classNames);
        wac.setServer(server);
        assertThat(wac.getConfigurations(), Matchers.contains(configs));
    }

    @Test
    public void testRealPathDoesNotExist() throws Exception
    {
        Server server = newServer();
        WebAppContext context = new WebAppContext(".", "/");
        server.setHandler(context);
        server.start();

        ServletContext ctx = context.getServletContext();
        assertNotNull(ctx.getRealPath("/doesnotexist"));
        assertNotNull(ctx.getRealPath("/doesnotexist/"));
    }

    /**
     * tests that the servlet context white list works
     *
     * @throws Exception on test failure
     */
    @Test
    public void testContextWhiteList() throws Exception
    {
        Server server = newServer();
        Handler.Collection handlers = new Handler.Collection();
        WebAppContext contextA = new WebAppContext(".", "/A");

        contextA.addServlet(ServletA.class, "/s");
        handlers.addHandler(contextA);
        WebAppContext contextB = new WebAppContext(".", "/B");

        contextB.addServlet(ServletB.class, "/s");
        contextB.setContextWhiteList("/doesnotexist", "/B/s");
        handlers.addHandler(contextB);

        server.setHandler(handlers);
        server.start();

        // context A should be able to get both A and B servlet contexts
        assertNotNull(contextA.getServletHandler().getServletContext().getContext("/A/s"));
        assertNotNull(contextA.getServletHandler().getServletContext().getContext("/B/s"));

        // context B has a contextWhiteList set and should only be able to get ones that are approved
        assertNull(contextB.getServletHandler().getServletContext().getContext("/A/s"));
        assertNotNull(contextB.getServletHandler().getServletContext().getContext("/B/s"));
    }

    @Test
    public void testAlias() throws Exception
    {
        Path tempDir = workDir.getEmptyPathDir().resolve("dir");
        FS.ensureEmpty(tempDir);

        Path webinf = tempDir.resolve("WEB-INF");
        FS.ensureEmpty(webinf);

        Path classes = tempDir.resolve("classes");
        FS.ensureEmpty(classes);

        Path someClass = classes.resolve("SomeClass.class");
        FS.touch(someClass);

        WebAppContext context = new WebAppContext();
        context.setBaseResource(tempDir);

        context.setResourceAlias("/WEB-INF/classes/", "/classes/");

        assertTrue(Resource.newResource(context.getServletContext().getResource("/WEB-INF/classes/SomeClass.class")).exists());
        assertTrue(Resource.newResource(context.getServletContext().getResource("/classes/SomeClass.class")).exists());
    }

    @Test
    public void testIsProtected()
    {
        WebAppContext context = new WebAppContext();

        assertTrue(context.isProtectedTarget("/web-inf/lib/foo.jar"));
        assertTrue(context.isProtectedTarget("/meta-inf/readme.txt"));
        assertFalse(context.isProtectedTarget("/something-else/web-inf"));
    }

    @Disabled //TODO
    @Test
    public void testProtectedTarget() throws Exception
    {
        Server server = newServer();

        Handler.Collection handlers = new Handler.Collection();
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        WebAppContext context = new WebAppContext();
        Path testWebapp = MavenTestingUtils.getProjectDirPath("src/test/webapp");
        context.setBaseResource(testWebapp);
        context.setContextPath("/");
        server.setHandler(handlers);
        handlers.addHandler(contexts);
        contexts.addHandler(context);

        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.RFC3986);

        server.start();

        assertThat(HttpTester.parseResponse(connector.getResponse("GET /test.xml HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(), is(HttpStatus.OK_200));
        assertThat(HttpTester.parseResponse(connector.getResponse("GET /%2e/%2e/test.xml HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(), is(HttpStatus.OK_200));
        assertThat(HttpTester.parseResponse(connector.getResponse("GET /%u002e/%u002e/test.xml HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(), is(HttpStatus.OK_200));
        assertThat(HttpTester.parseResponse(connector.getResponse("GET /foo/%2e%2e/test.xml HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(), is(HttpStatus.OK_200));
        assertThat(HttpTester.parseResponse(connector.getResponse("GET /foo/%u002e%u002e/test.xml HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(), is(HttpStatus.OK_200));

        assertThat(HttpTester.parseResponse(connector.getResponse("GET /WEB-INF HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(), is(HttpStatus.NOT_FOUND_404));
        assertThat(HttpTester.parseResponse(connector.getResponse("GET /WEB-INF/ HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(), is(HttpStatus.NOT_FOUND_404));
        assertThat(HttpTester.parseResponse(connector.getResponse("GET /WEB-INF/test.xml HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(), is(HttpStatus.NOT_FOUND_404));
        assertThat(HttpTester.parseResponse(connector.getResponse("GET /web-inf/test.xml HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(), is(HttpStatus.NOT_FOUND_404));
        assertThat(HttpTester.parseResponse(connector.getResponse("GET /%2e/WEB-INF/test.xml HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(), is(HttpStatus.NOT_FOUND_404));
        assertThat(HttpTester.parseResponse(connector.getResponse("GET /%u002e/WEB-INF/test.xml HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(), is(HttpStatus.NOT_FOUND_404));
        assertThat(HttpTester.parseResponse(connector.getResponse("GET /%2e/%2e/WEB-INF/test.xml HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(), is(HttpStatus.NOT_FOUND_404));
        assertThat(HttpTester.parseResponse(connector.getResponse("GET /%u002e/%u002e/WEB-INF/test.xml HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(), is(HttpStatus.NOT_FOUND_404));
        assertThat(HttpTester.parseResponse(connector.getResponse("GET /foo/%2e%2e/WEB-INF/test.xml HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(), is(HttpStatus.NOT_FOUND_404));
        assertThat(HttpTester.parseResponse(connector.getResponse("GET /foo/%u002e%u002e/WEB-INF/test.xml HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(), is(HttpStatus.NOT_FOUND_404));
        assertThat(HttpTester.parseResponse(connector.getResponse("GET /%2E/WEB-INF/test.xml HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(), is(HttpStatus.NOT_FOUND_404));
        assertThat(HttpTester.parseResponse(connector.getResponse("GET /%u002E/WEB-INF/test.xml HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(), is(HttpStatus.NOT_FOUND_404));
        assertThat(HttpTester.parseResponse(connector.getResponse("GET //WEB-INF/test.xml HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(), is(HttpStatus.NOT_FOUND_404));
        assertThat(HttpTester.parseResponse(connector.getResponse("GET /WEB-INF%2ftest.xml HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(), is(HttpStatus.NOT_FOUND_404));
    }
        
    @ParameterizedTest
    @ValueSource(strings = {
        "/WEB-INF",
        "/WEB-INF/",
        "/WEB-INF/test.xml",
        "/web-inf/test.xml",
        "/%2e/WEB-INF/test.xml",
        "/%2e/%2e/WEB-INF/test.xml",
        "/foo/%2e%2e/WEB-INF/test.xml",
        "/%2E/WEB-INF/test.xml",
        "//WEB-INF/test.xml",
        "/WEB-INF%2ftest.xml",
        "/.%00/WEB-INF/test.xml",
        "/WEB-INF%00/test.xml"
    })
    
    @Disabled //TODO
    @Test
    public void testProtectedTargetFailure(String path) throws Exception
    {
        Server server = newServer();

        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.LEGACY);

        Handler.Collection handlers = new Handler.Collection();
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        WebAppContext context = new WebAppContext();
        Path testWebapp = MavenTestingUtils.getProjectDirPath("src/test/webapp");
        context.setBaseResource(testWebapp);
        context.setContextPath("/");
        server.setHandler(handlers);
        handlers.addHandler(contexts);
        contexts.addHandler(context);

        server.start();

        assertThat(HttpTester.parseResponse(connector.getResponse("GET " + path + " HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(),
            Matchers.anyOf(is(HttpStatus.NOT_FOUND_404), is(HttpStatus.BAD_REQUEST_400)));
    }

    @Disabled //TODO
    @Test
    public void testNullPath() throws Exception
    {
        Server server = newServer();

        Handler.Collection handlers = new Handler.Collection();
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        WebAppContext context = new WebAppContext();
        Path testWebapp = MavenTestingUtils.getProjectDirPath("src/test/webapp");
        context.setBaseResource(testWebapp);
        context.setContextPath("/");
        server.setHandler(handlers);
        handlers.addHandler(contexts);
        contexts.addHandler(context);

        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);

        server.start();

        String rawResponse = connector.getResponse("GET http://localhost:8080 HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat("Response OK", response.getStatus(), is(HttpStatus.OK_200));
    }

    @Test
    public void testNullSessionAndSecurityHandler() throws Exception
    {
        Server server = newServer();

        Handler.Collection handlers = new Handler.Collection();
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        WebAppContext context = new WebAppContext(null, null, null, null, null, new ErrorPageErrorHandler(),
            ServletContextHandler.NO_SESSIONS | ServletContextHandler.NO_SECURITY);
        context.setContextPath("/");

        Path testWebapp = MavenTestingUtils.getProjectDirPath("src/test/webapp");
        context.setBaseResource(testWebapp);
        server.setHandler(handlers);
        handlers.addHandler(contexts);
        contexts.addHandler(context);

        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);

        server.start();
        assertTrue(context.isAvailable());
    }

    static class ServletA extends GenericServlet
    {
        @Override
        public void service(ServletRequest req, ServletResponse res)
        {
            this.getServletContext().getContext("/A/s");
        }
    }

    static class ServletB extends GenericServlet
    {
        @Override
        public void service(ServletRequest req, ServletResponse res)
        {
            this.getServletContext().getContext("/B/s");
        }
    }

    @Test
    public void testBaseResourceAbsolutePath() throws Exception
    {
        Server server = newServer();

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");

        Path warPath = MavenTestingUtils.getTestResourcePathFile("wars/dump.war");
        warPath = warPath.toAbsolutePath();
        assertTrue(warPath.isAbsolute(), "Path should be absolute: " + warPath);
        // Use String reference to war
        // On Unix / Linux this should have no issue.
        // On Windows with fully qualified paths such as "E:\mybase\webapps\dump.war" the
        // resolution of the Resource can trigger various URI issues with the "E:" portion of the provided String.
        context.setBaseResource(warPath);

        server.setHandler(context);
        server.start();

        assertTrue(context.isAvailable(), "WebAppContext should be available");
    }

    public static Stream<Arguments> extraClasspathGlob()
    {
        List<Arguments> references = new ArrayList<>();

        Path extLibs = MavenTestingUtils.getTestResourcePathDir("ext");
        extLibs = extLibs.toAbsolutePath();

        // Absolute reference with trailing slash and glob
        references.add(Arguments.of("absolute extLibs with glob", extLibs.toString() + File.separator + "*"));

        // Establish a relative extraClassPath reference
        String relativeExtLibsDir = MavenTestingUtils.getBasePath().relativize(extLibs).toString();

        // This will be in the String form similar to "src/test/resources/ext/*" (with trailing slash and glob)
        references.add(Arguments.of("relative extLibs with glob", relativeExtLibsDir + File.separator + "*"));

        return references.stream();
    }

    /**
     * Test using WebAppContext.setExtraClassPath(String) with a reference to a glob
     */
    @ParameterizedTest
    @MethodSource("extraClasspathGlob")
    public void testExtraClasspathGlob(String description, String extraClasspathGlobReference) throws Exception
    {
        Server server = newServer();

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        Path warPath = MavenTestingUtils.getTestResourcePathFile("wars/dump.war");
        context.setBaseResource(warPath);
        context.setExtraClasspath(extraClasspathGlobReference);

        server.setHandler(context);
        server.start();

        // Should not have failed the start of the WebAppContext
        assertTrue(context.isAvailable(), "WebAppContext should be available");

        // Test WebAppClassLoader contents for expected jars
        ClassLoader contextClassLoader = context.getClassLoader();
        assertThat(contextClassLoader, instanceOf(WebAppClassLoader.class));
        WebAppClassLoader webAppClassLoader = (WebAppClassLoader)contextClassLoader;
        Path extLibsDir = MavenTestingUtils.getTestResourcePathDir("ext");
        extLibsDir = extLibsDir.toAbsolutePath();
        List<URI> expectedUris;
        try (Stream<Path> s = Files.list(extLibsDir))
        {
            expectedUris = s
                .filter(Files::isRegularFile)
                .filter((path) -> path.getFileName().toString().endsWith(".jar"))
                .map(Path::toUri)
                .map(Resource::toJarFileUri)
                .collect(Collectors.toList());
        }
        List<URI> actualURIs = new ArrayList<>();
        for (URL url : webAppClassLoader.getURLs())
        {
            actualURIs.add(url.toURI());
        }
        assertThat("[" + description + "] WebAppClassLoader.urls.length", actualURIs.size(), is(expectedUris.size()));

        assertThat(actualURIs, contains(expectedUris.toArray()));
    }

    public static Stream<Arguments> extraClasspathDir()
    {
        List<Arguments> references = new ArrayList<>();

        Path extLibs = MavenTestingUtils.getTestResourcePathDir("ext");
        extLibs = extLibs.toAbsolutePath();

        // Absolute reference with trailing slash
        references.add(Arguments.of(extLibs.toString() + File.separator));

        // Absolute reference without trailing slash
        references.add(Arguments.of(extLibs.toString()));

        // Establish a relative extraClassPath reference
        String relativeExtLibsDir = MavenTestingUtils.getBasePath().relativize(extLibs).toString();

        // This will be in the String form similar to "src/test/resources/ext/" (with trailing slash)
        references.add(Arguments.of(relativeExtLibsDir + File.separator));

        // This will be in the String form similar to "src/test/resources/ext/" (without trailing slash)
        references.add(Arguments.of(relativeExtLibsDir));

        return references.stream();
    }

    /**
     * Test using WebAppContext.setExtraClassPath(String) with a reference to a directory
     */
    @ParameterizedTest
    @MethodSource("extraClasspathDir")
    public void testExtraClasspathDir(String extraClassPathReference) throws Exception
    {
        Server server = newServer();

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        Path warPath = MavenTestingUtils.getTestResourcePathFile("wars/dump.war");
        context.setBaseResource(warPath);

        context.setExtraClasspath(extraClassPathReference);

        server.setHandler(context);
        server.start();

        // Should not have failed the start of the WebAppContext
        assertTrue(context.isAvailable(), "WebAppContext should be available");

        // Test WebAppClassLoader contents for expected directory reference
        ClassLoader contextClassLoader = context.getClassLoader();
        assertThat(contextClassLoader, instanceOf(WebAppClassLoader.class));
        WebAppClassLoader webAppClassLoader = (WebAppClassLoader)contextClassLoader;
        URL[] urls = webAppClassLoader.getURLs();
        assertThat("URLs", urls.length, is(1));
        Path extLibs = MavenTestingUtils.getTestResourcePathDir("ext");
        extLibs = extLibs.toAbsolutePath();
        assertThat("URL[0]", urls[0].toURI(), is(extLibs.toUri()));
    }
}
