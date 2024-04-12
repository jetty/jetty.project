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

package org.eclipse.jetty.ee9.webapp;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.servlet.GenericServlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.eclipse.jetty.ee.WebAppClassLoading;
import org.eclipse.jetty.ee9.nested.ContextHandler;
import org.eclipse.jetty.ee9.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
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
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.FileSystemPool;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
@ExtendWith(WorkDirExtension.class)
public class WebAppContextTest
{
    public static final Logger LOG = LoggerFactory.getLogger(WebAppContextTest.class);
    private final List<Object> lifeCycles = new ArrayList<>();

    @BeforeEach
    public void beforeEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @AfterEach
    public void tearDown()
    {
        lifeCycles.forEach(LifeCycle::stop);
        Configurations.cleanKnown();
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    /**
     * Create a webapp as a war on the fly.
     *
     * @param tempDir the directory into which the war will be generated
     * @param name the name of the war
     * @return the Path of the generated war
     *
     * @throws Exception if the war cannot be created
     */
    private Path createWar(Path tempDir, String name) throws Exception
    {
        // Create war on the fly
        Path testWebappDir = MavenTestingUtils.getTargetPath("test-classes/webapp");
        assertTrue(Files.exists(testWebappDir));
        Path warFile = tempDir.resolve(name);

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI uri = URI.create("jar:" + warFile.toUri().toASCIIString());
        // Use ZipFS so that we can create paths that are just "/"
        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            Path root = zipfs.getPath("/");
            IO.copyDir(testWebappDir, root);
        }

        return warFile;
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
        File webXml = MavenTestingUtils.getTargetFile("test-classes/web-with-default-context-path.xml");
        File webXmlEmptyPath = MavenTestingUtils.getTargetFile("test-classes/web-with-empty-default-context-path.xml");
        File webDefaultXml = MavenTestingUtils.getTargetFile("test-classes/web-default-with-default-context-path.xml");
        File overrideWebXml = MavenTestingUtils.getTargetFile("test-classes/override-web-with-default-context-path.xml");
        assertNotNull(webXml);
        assertNotNull(webDefaultXml);
        assertNotNull(overrideWebXml);
        assertNotNull(webXmlEmptyPath);

        WebAppContext wac = new WebAppContext();
        wac.setResourceBase(MavenTestingUtils.getTargetTestingDir().getAbsolutePath());
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
    public void nestedContextHandlerTest() throws Exception
    {
        Server server = newServer();
        File webXml = MavenTestingUtils.getTargetFile("test-classes/web-with-default-context-path.xml");
        File webXmlEmptyPath = MavenTestingUtils.getTargetFile("test-classes/web-with-empty-default-context-path.xml");
        File webDefaultXml = MavenTestingUtils.getTargetFile("test-classes/web-default-with-default-context-path.xml");
        File overrideWebXml = MavenTestingUtils.getTargetFile("test-classes/override-web-with-default-context-path.xml");
        assertNotNull(webXml);
        assertNotNull(webDefaultXml);
        assertNotNull(overrideWebXml);
        assertNotNull(webXmlEmptyPath);

        WebAppContext wac = new WebAppContext();
        wac.setResourceBase(MavenTestingUtils.getTargetTestingDir().getAbsolutePath());

        // Put WebAppContext inside another nested ContextHandler.
        ContextHandler contextHandler = new ContextHandler("/", wac);
        server.setHandler(contextHandler);

        // All components should be started even the webapp context CoreContextHandler.
        assertNotNull(contextHandler.getServer());
        assertNotNull(contextHandler.getCoreContextHandler().getServer());
        assertNotNull(wac.getServer());
        assertNotNull(wac.getCoreContextHandler().getServer());

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
            expectedConfigurations.add("org.eclipse.jetty.ee9.webapp.JmxConfiguration");
        }
        expectedConfigurations.add("org.eclipse.jetty.ee9.webapp.WebInfConfiguration");
        expectedConfigurations.add("org.eclipse.jetty.ee9.webapp.WebXmlConfiguration");
        expectedConfigurations.add("org.eclipse.jetty.ee9.webapp.MetaInfConfiguration");
        expectedConfigurations.add("org.eclipse.jetty.ee9.webapp.FragmentConfiguration");
        expectedConfigurations.add("org.eclipse.jetty.ee9.webapp.JaasConfiguration");
        expectedConfigurations.add("org.eclipse.jetty.ee9.webapp.WebAppConfiguration");
        expectedConfigurations.add("org.eclipse.jetty.ee9.webapp.JettyWebXmlConfiguration");

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
        server.setAttribute(Configurations.class.getName(), classNames);
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
        assertNull(ctx.getRealPath("/doesnotexist"));
        assertNull(ctx.getRealPath("/doesnotexist/"));
    }

    /**
     * tests that the servlet context white list works
     *
     * @throws Exception on test failure
     */
    @Test
    @Disabled // No cross context dispatch
    public void testContextWhiteList() throws Exception
    {
        Server server = newServer();
        Handler.Sequence handlers = new Handler.Sequence();
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
    public void testAlias(WorkDir workDir) throws Exception
    {
        Path tempDir = workDir.getEmptyPathDir();
        FS.ensureEmpty(tempDir);

        Path webinf = tempDir.resolve("WEB-INF");
        FS.ensureEmpty(webinf);

        Path classes = tempDir.resolve("classes");
        FS.ensureEmpty(classes);

        Path someClass = classes.resolve("SomeClass.class");
        FS.touch(someClass);

        WebAppContext context = new WebAppContext();
        context.setBaseResource(context.getResourceFactory().newResource(tempDir));

        context.setResourceAlias("/WEB-INF/classes/", "/classes/");

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            assertTrue(resourceFactory.newResource(context.getServletContext().getResource("/WEB-INF/classes/SomeClass.class")).exists());
            assertTrue(resourceFactory.newResource(context.getServletContext().getResource("/classes/SomeClass.class")).exists());
        }
    }

    @Test
    public void testIsProtected()
    {
        WebAppContext context = new WebAppContext();

        assertTrue(context.isProtectedTarget("/web-inf/lib/foo.jar"));
        assertTrue(context.isProtectedTarget("/meta-inf/readme.txt"));
        assertFalse(context.isProtectedTarget("/something-else/web-inf"));
    }

    @Test
    public void testProtectedTarget() throws Exception
    {
        Server server = newServer();

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        WebAppContext context = new WebAppContext();
        Path testWebapp = MavenTestingUtils.getTargetPath("test-classes/webapp");
        context.setBaseResource(context.getResourceFactory().newResource(testWebapp));
        context.setContextPath("/");

        contexts.addHandler(context);

        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.UNSAFE);

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
    public void testProtectedTargetFailure(String path) throws Exception
    {
        Server server = newServer();

        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.LEGACY);

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        WebAppContext context = new WebAppContext();
        Path testWebapp = MavenTestingUtils.getTargetPath("test-classes/webapp");
        context.setBaseResource(context.getResourceFactory().newResource(testWebapp));
        context.setContextPath("/");
        contexts.addHandler(context);

        server.start();

        assertThat(HttpTester.parseResponse(connector.getResponse("GET " + path + " HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(),
            Matchers.anyOf(is(HttpStatus.NOT_FOUND_404), is(HttpStatus.BAD_REQUEST_400)));
    }

    @Test
    public void testNullPath() throws Exception
    {
        Server server = newServer();

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        WebAppContext context = new WebAppContext();
        Path testWebapp = MavenPaths.findTestResourceDir("webapp");
        Resource testWebappResource = context.getResourceFactory().newResource(testWebapp);
        assertTrue(Resources.isReadableDirectory(testWebappResource));
        context.setBaseResource(testWebappResource);
        context.setContextPath("/");

        contexts.addHandler(context);

        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);

        server.start();

        String rawResponse = connector.getResponse("""
            GET http://localhost:8080 HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            \r
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat("Response OK", response.getStatus(), is(HttpStatus.OK_200));
    }

    @Test
    public void testNullSessionAndSecurityHandler() throws Exception
    {
        Server server = newServer();

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        WebAppContext context = new WebAppContext(null, null, null, null, null, new ErrorPageErrorHandler(),
            ServletContextHandler.NO_SESSIONS | ServletContextHandler.NO_SECURITY);
        context.setContextPath("/");
        Path testWebapp = MavenTestingUtils.getTargetPath("test-classes/webapp");
        context.setBaseResource(context.getResourceFactory().newResource(testWebapp));
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
    public void testBaseResourceAbsolutePath(WorkDir workDir) throws Exception
    {
        Server server = newServer();

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");

        Path warPath = createWar(workDir.getEmptyPathDir(), "test.war");
        warPath = warPath.toAbsolutePath();
        assertTrue(warPath.isAbsolute(), "Path should be absolute: " + warPath);
        // Use String reference to war
        // On Unix / Linux this should have no issue.
        // On Windows with fully qualified paths such as "E:\mybase\webapps\test.war" the
        // resolution of the Resource can trigger various URI issues with the "E:" portion of the provided String.
        context.setBaseResourceAsString(warPath.toString());

        server.setHandler(context);
        server.start();

        assertTrue(context.isAvailable(), "WebAppContext should be available");
    }

    @Test
    public void testGetResourceFromCollection() throws Exception
    {
        Server server = newServer();

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        context.setBaseResource(ResourceFactory.combine(
            context.getResourceFactory().newResource(MavenPaths.findTestResourceDir("wars/layer0/")),
            context.getResourceFactory().newResource(MavenPaths.findTestResourceDir("wars/layer1/"))));
        server.setHandler(context);
        server.start();

        ServletContext servletContext = context.getServletContext();
        assertThat(servletContext.getResource("/WEB-INF/zero.xml"), notNullValue());
        assertThat(servletContext.getResource("/WEB-INF/one.xml"), notNullValue());
    }

    @Test
    public void testGetResourcePathsFromCollection() throws Exception
    {
        Server server = newServer();

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        context.setBaseResource(ResourceFactory.combine(
            context.getResourceFactory().newResource(MavenPaths.findTestResourceDir("wars/layer0/")),
            context.getResourceFactory().newResource(MavenPaths.findTestResourceDir("wars/layer1/"))));
        server.setHandler(context);
        server.start();

        ServletContext servletContext = context.getServletContext();
        assertThat(servletContext.getResourcePaths("/WEB-INF/"), containsInAnyOrder("/WEB-INF/zero.xml", "/WEB-INF/one.xml"));
    }

    @Test
    public void testGetResourcePathsWithDirsFromCollection() throws Exception
    {
        Server server = newServer();

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        context.setBaseResource(ResourceFactory.combine(
            context.getResourceFactory().newResource(MavenPaths.findTestResourceDir("wars/layer0/")),
            context.getResourceFactory().newResource(MavenPaths.findTestResourceDir("wars/layer1/")),
            context.getResourceFactory().newResource(MavenPaths.findTestResourceDir("wars/with_dirs/"))
        ));
        server.setHandler(context);
        server.start();

        ServletContext servletContext = context.getServletContext();
        Set<String> results = servletContext.getResourcePaths("/WEB-INF/");
        String[] expected = {
            "/WEB-INF/zero.xml",
            "/WEB-INF/one.xml",
            "/WEB-INF/bar/",
            "/WEB-INF/foo/"
        };
        assertThat(results, containsInAnyOrder(expected));
    }

    @Test
    @Disabled("There is extra decoding of the nested-reserved paths that is getting in the way")
    public void testGetResourcePaths() throws Exception
    {
        Server server = newServer();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);

        Path warRoot = MavenPaths.findTestResourceDir("webapp-with-resources");
        assertTrue(Files.isDirectory(warRoot), "Unable to find directory: " + warRoot);
        WebAppContext context = new WebAppContext();
        Resource warResource = context.getResourceFactory().newResource(warRoot);
        context.setWarResource(warResource);
        context.setContextPath("/");
        server.setHandler(context);
        server.start();

        ServletContext servletContext = context.getServletContext();

        Set<String> resourcePaths = servletContext.getResourcePaths("/");
        String[] expected = {
            "/WEB-INF/",
            "/nested-reserved-!#\\\\$%&()*+,:=?@[]-meta-inf-resource.txt",
            };
        assertThat(resourcePaths.size(), is(2));
        assertThat(resourcePaths, containsInAnyOrder(expected));

        String realPath = servletContext.getRealPath("/");
        assertThat(realPath, notNullValue());
        assertThat(servletContext.getRealPath("/WEB-INF/"), endsWith("/WEB-INF/"));
        // TODO the following assertion fails because of a bug in the JDK (see JDK-8311079 and MountedPathResourceTest.testJarFileResourceAccessBackSlash())
        //assertThat(servletContext.getRealPath(resourcePaths.get(1)), endsWith("/nested-reserved-!#\\\\$%&()*+,:=?@[]-meta-inf-resource.txt"));

        assertThat(servletContext.getResource("/WEB-INF/"), notNullValue());
        // TODO the following assertion fails because of a bug in the JDK (see JDK-8311079 and MountedPathResourceTest.testJarFileResourceAccessBackSlash())
        //assertThat(servletContext.getResource("/nested-reserved-!#\\\\$%&()*+,:=?@[]-meta-inf-resource.txt"), notNullValue());

        HttpTester.Response response1 = HttpTester.parseResponse(connector.getResponse("""
            GET /resource HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """));

        assertThat(response1.getStatus(), is(HttpStatus.OK_200));
        assertThat(response1.getContent(), containsString("/WEB-INF"));
        assertThat(response1.getContent(), containsString("/WEB-INF/lib"));
        assertThat(response1.getContent(), containsString("/WEB-INF/lib/odd-resource.jar"));
        assertThat(response1.getContent(), containsString("/nested-reserved-!#\\\\$%&()*+,:=?@[]-meta-inf-resource.txt"));

        HttpTester.Response response2 = HttpTester.parseResponse(connector.getResponse("""
            GET /real HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """));

        assertThat(response2.getStatus(), is(HttpStatus.OK_200));
        assertThat(response2.getContent(), containsString("/WEB-INF"));
        assertThat(response2.getContent(), containsString("/WEB-INF/lib"));
        assertThat(response2.getContent(), containsString("/WEB-INF/lib/odd-resource.jar"));
        // TODO the following assertion fails because of a bug in the JDK (see JDK-8311079 and MountedPathResourceTest.testJarFileResourceAccessBackSlash())
        //assertThat(response2.getContent(), containsString("/nested-reserved-!#\\\\$%&()*+,:=?@[]-meta-inf-resource.txt"));
    }

    public static Stream<Arguments> extraClasspathGlob()
    {
        List<Arguments> references = new ArrayList<>();

        Path extLibs = MavenTestingUtils.getTargetPath("test-classes/ext");
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
    @ParameterizedTest(name = "{0}")
    @MethodSource("extraClasspathGlob")
    public void testExtraClasspathGlob(String description, String extraClasspathGlobReference) throws Exception
    {
        Path testPath = MavenPaths.targetTestDir("testExtraClasspathGlob");
        FS.ensureDirExists(testPath);
        FS.ensureEmpty(testPath);

        Server server = newServer();

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        Path warPath = createWar(testPath, "test.war");
        context.setBaseResource(context.getResourceFactory().newResource(warPath));
        context.setExtraClasspath(extraClasspathGlobReference);

        server.setHandler(context);
        server.start();

        // Should not have failed the start of the WebAppContext
        assertTrue(context.isAvailable(), "WebAppContext should be available");

        // Test WebAppClassLoader contents for expected jars
        ClassLoader contextClassLoader = context.getClassLoader();
        assertThat(contextClassLoader, instanceOf(WebAppClassLoader.class));
        WebAppClassLoader webAppClassLoader = (WebAppClassLoader)contextClassLoader;
        Path extLibsDir = MavenTestingUtils.getTargetPath("test-classes/ext");
        extLibsDir = extLibsDir.toAbsolutePath();

        List<URI> expectedUris;
        try (Stream<Path> s = Files.list(extLibsDir))
        {
            expectedUris = s
                .filter(Files::isRegularFile)
                .filter(FileID::isLibArchive)
                .sorted(Comparator.naturalOrder())
                .map(Path::toUri)
                .map(URIUtil::toJarFileUri)
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

        Path extLibs = MavenPaths.findTestResourceDir("ext");
        extLibs = extLibs.toAbsolutePath();

        // Absolute reference with trailing slash
        references.add(Arguments.of(extLibs.toString() + File.separator));

        // Absolute reference without trailing slash
        references.add(Arguments.of(extLibs.toString()));

        // Establish a relative extraClassPath reference
        String relativeExtLibsDir = MavenTestingUtils.getBasePath().relativize(extLibs).toString();

        // This will be in the String form similar to "src/test/resources/ext/" (with trailing slash)
        references.add(Arguments.of(relativeExtLibsDir + File.separator));

        // This will be in the String form similar to "src/test/resources/ext" (without trailing slash)
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
        Path testPath = MavenPaths.targetTestDir("testExtraClasspathDir");
        FS.ensureDirExists(testPath);
        FS.ensureEmpty(testPath);

        Server server = newServer();

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        Path warPath = createWar(testPath, "test.war");
        context.setBaseResource(context.getResourceFactory().newResource(warPath));

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
        Path extLibs = MavenPaths.findTestResourceDir("ext");
        extLibs = extLibs.toAbsolutePath();
        assertThat("URL[0]", urls[0].toURI(), is(extLibs.toUri()));
    }

    @Test
    public void testAddServerClasses() throws Exception
    {
        Server server = newServer();

        String testPattern = "org.eclipse.jetty.ee9.webapp.test.";

        WebAppContext.addServerClasses(server, testPattern);

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        Path testPath = MavenPaths.targetTestDir("testAddServerClasses");
        FS.ensureDirExists(testPath);
        FS.ensureEmpty(testPath);
        Path warPath = createWar(testPath, "test.war");
        context.setBaseResource(context.getResourceFactory().newResource(warPath));

        server.setHandler(context);
        server.start();

        List<String> serverClasses = List.of(context.getServerClasses());
        assertThat("Should have environment specific test pattern", serverClasses, hasItem(testPattern));
        assertThat("Should have pattern from defaults", serverClasses, hasItem("org.eclipse.jetty."));
        assertThat("Should have pattern from JaasConfiguration", serverClasses, hasItem("-org.eclipse.jetty.security.jaas."));
        for (String defaultServerClass: WebAppClassLoading.DEFAULT_HIDDEN_CLASSES)
            assertThat("Should have default patterns", serverClasses, hasItem(defaultServerClass));
    }

    @Test
    public void testAddSystemClasses() throws Exception
    {
        Server server = newServer();

        String testPattern = "org.eclipse.jetty.ee9.webapp.test.";

        WebAppContext.addSystemClasses(server, testPattern);

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        Path testPath = MavenPaths.targetTestDir("testAddServerClasses");
        FS.ensureDirExists(testPath);
        FS.ensureEmpty(testPath);
        Path warPath = createWar(testPath, "test.war");
        context.setBaseResource(context.getResourceFactory().newResource(warPath));

        server.setHandler(context);
        server.start();

        List<String> systemClasses = List.of(context.getSystemClasses());
        assertThat("Should have environment specific test pattern", systemClasses, hasItem(testPattern));
        assertThat("Should have pattern from defaults", systemClasses, hasItem("javax."));
        assertThat("Should have pattern from defaults", systemClasses, hasItem("jakarta."));
        assertThat("Should have pattern from JaasConfiguration", systemClasses, hasItem("org.eclipse.jetty.security.jaas."));
        for (String defaultSystemClass : WebAppClassLoading.DEFAULT_PROTECTED_CLASSES)
        {
            assertThat("Should have default patterns", systemClasses, hasItem(defaultSystemClass));
        }
    }
}
