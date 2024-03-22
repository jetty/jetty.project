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

package org.eclipse.jetty.ee10.webapp;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
import org.eclipse.jetty.server.handler.DefaultHandler;
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
import static org.hamcrest.Matchers.either;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated()
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
        wac.setBaseResourceAsPath(MavenTestingUtils.getTargetTestingDir().getAbsoluteFile().toPath());
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
        expectedConfigurations.add("org.eclipse.jetty.ee10.webapp.JaasConfiguration");
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
        server.setAttribute(Configurations.SERVER_DEFAULT_ATTR, classNames);
        wac.setServer(server);
        assertThat(wac.getConfigurations(), Matchers.contains(configs));
    }

    @Test
    public void testRealPath() throws Exception
    {
        Server server = newServer();
        WebAppContext context = new WebAppContext(".", "/");
        server.setHandler(context);
        server.start();

        ServletContext ctx = context.getServletContext();
        assertNotNull(ctx.getRealPath("/"));
        assertNull(ctx.getRealPath("/doesnotexist"));
        assertNull(ctx.getRealPath("/doesnotexist/"));
    }

    /**
     * tests that the servlet context white list works
     *
     * @throws Exception on test failure
     */
    @Disabled // Reenabled when cross context dispatch is implemented.
    @Test
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
        context.setBaseResourceAsPath(tempDir);

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

    @ParameterizedTest
    @ValueSource(strings = {
        "/test.xml",
        "/%2e/%2e/test.xml",
        "/%u002e/%u002e/test.xml",
        "/foo/%2e%2e/test.xml",
        "/foo/%u002e%u002e/test.xml"
    })
    public void testUnProtectedTarget(String target) throws Exception
    {
        Server server = newServer();

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        WebAppContext context = new WebAppContext();
        Path testWebapp = MavenTestingUtils.getProjectDirPath("src/test/webapp");
        context.setBaseResourceAsPath(testWebapp);
        context.setContextPath("/");
        server.setHandler(contexts);
        contexts.addHandler(context);

        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.UNSAFE);

        server.start();

        assertThat(HttpTester.parseResponse(connector.getResponse("GET " + target + " HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(), is(HttpStatus.OK_200));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/WEB-INF",
        "/WEB-INF/",
        "/WEB-INF%2F",
        "/WEB-INF/test.xml",
        "/web-inf/test.xml",
        "/%2e/WEB-INF/test.xml",
        "/%u002e/WEB-INF/test.xml",
        "/%2e/%2e/WEB-INF/test.xml",
        "/%u002e/%u002e/WEB-INF/test.xml",
        "/foo/%2e%2e/WEB-INF/test.xml",
        "/foo/%u002e%u002e/WEB-INF/test.xml",
        "/%2E/WEB-INF/test.xml",
        "/%u002E/WEB-INF/test.xml",
        "//WEB-INF/test.xml",
        "/WEB-INF%2Ftest.xml",
        "/WEB-INF%u002Ftest.xml",
        "/WEB-INF%2ftest.xml"
    })
    public void testProtectedTarget(String target) throws Exception
    {
        Server server = newServer();

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        WebAppContext context = new WebAppContext();
        Path testWebapp = MavenTestingUtils.getProjectDirPath("src/test/webapp");
        context.setBaseResourceAsPath(testWebapp);
        context.setContextPath("/");
        server.setHandler(contexts);
        contexts.addHandler(context);

        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.RFC3986);

        server.start();

        assertThat(HttpTester.parseResponse(connector.getResponse("GET " + target + " HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(),
            either(is(HttpStatus.NOT_FOUND_404)).or(is(HttpStatus.BAD_REQUEST_400)));
    }
        
    @ParameterizedTest
    @ValueSource(strings = {
        "/.%00/WEB-INF/test.xml",
        "/WEB-INF%00/test.xml",
        "/WEB-INF%u0000/test.xml"
    })
    public void testProtectedTargetFailure(String path) throws Exception
    {
        Server server = newServer();

        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.LEGACY);

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        WebAppContext context = new WebAppContext();
        Path testWebapp = MavenTestingUtils.getProjectDirPath("src/test/webapp");
        context.setBaseResourceAsPath(testWebapp);
        context.setContextPath("/");
        server.setHandler(contexts);
        contexts.addHandler(context);

        server.start();

        assertThat(HttpTester.parseResponse(connector.getResponse("GET " + path + " HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n")).getStatus(),
            Matchers.anyOf(is(HttpStatus.BAD_REQUEST_400)));
    }

    @Test
    public void testNullPath() throws Exception
    {
        Server server = newServer();

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        WebAppContext context = new WebAppContext();
        Path testWebapp = MavenTestingUtils.getProjectDirPath("src/test/webapp");
        context.setBaseResourceAsPath(testWebapp);
        context.setContextPath("/");
        server.setHandler(contexts);
        contexts.addHandler(context);

        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);

        server.start();

        String rawResponse = connector.getResponse("GET http://localhost:8080 HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    @Test
    public void testNullSessionAndSecurityHandler() throws Exception
    {
        Server server = newServer();

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        WebAppContext context = new WebAppContext(null, null, null, null, new ErrorPageErrorHandler(),
            ServletContextHandler.NO_SESSIONS | ServletContextHandler.NO_SECURITY);
        context.setContextPath("/");

        Path testWebapp = MavenTestingUtils.getProjectDirPath("src/test/webapp");
        context.setBaseResourceAsPath(testWebapp);
        server.setHandler(contexts);
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

        // TODO this is not testing what it looks like.  The war should be set with
        //      setWar (or converted to a jar:file before calling setBaseResource)
        //      However the war is currently a javax war, so it needs to be converted.
        new Throwable("fixme").printStackTrace();
        Path warPath = MavenTestingUtils.getTestResourcePathFile("wars/dump.war");
        warPath = warPath.toAbsolutePath();
        assertTrue(warPath.isAbsolute(), "Path should be absolute: " + warPath);
        // Use String reference to war
        // On Unix / Linux this should have no issue.
        // On Windows with fully qualified paths such as "E:\mybase\webapps\dump.war" the
        // resolution of the Resource can trigger various URI issues with the "E:" portion of the provided String.
        context.setBaseResourceAsPath(warPath);

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
            context.getResourceFactory().newResource(MavenTestingUtils.getTestResourcePath("wars/layer0/")),
            context.getResourceFactory().newResource(MavenTestingUtils.getTestResourcePath("wars/layer1/"))));
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
            context.getResourceFactory().newResource(MavenTestingUtils.getTestResourcePath("wars/layer0/")),
            context.getResourceFactory().newResource(MavenTestingUtils.getTestResourcePath("wars/layer1/"))));
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
            context.getResourceFactory().newResource(MavenTestingUtils.getTestResourcePath("wars/layer0/")),
            context.getResourceFactory().newResource(MavenTestingUtils.getTestResourcePath("wars/layer1/")),
            context.getResourceFactory().newResource(MavenTestingUtils.getTestResourcePath("wars/with_dirs/"))
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

    public static Stream<Arguments> resourceTests()
    {
        return Stream.of(
            Arguments.of("/test.txt", "/test.txt"),
            Arguments.of("/WEB-INF/web.xml", "/WEB-INF/web.xml"),
            Arguments.of("/WEB-INF/", "/WEB-INF/"),
            Arguments.of("/WEB-INF", "/WEB-INF/")
            // TODO the following assertion fails because of a bug in the JDK (see JDK-8311079 and MountedPathResourceTest.testJarFileResourceAccessBackSlash())
            // Arguments.of("/nested-reserved-!#\\\\$%&()*+,:=?@[]-meta-inf-resource.txt", "/nested-reserved-!#\\\\$%&()*+,:=?@[]-meta-inf-resource.txt")
        );
    }

    @ParameterizedTest
    @MethodSource("resourceTests")
    public void testGetResource(String resource, String expected) throws Exception
    {
        Server server = newServer();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);

        WebAppContext context = new WebAppContext(MavenTestingUtils.getBasePath().resolve("src/test/webapp-with-resources").toString(), "/");
        server.setHandler(context);
        server.start();

        ServletContext servletContext = context.getServletContext();

        URL url = servletContext.getResource(resource);
        assertThat(url.toString(), endsWith(expected));

        HttpTester.Response response1 = HttpTester.parseResponse(connector.getResponse("""
            GET /resource?r=%s HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """.formatted(resource)));

        assertThat(response1.getStatus(), is(HttpStatus.OK_200));
        assertThat(response1.getContent(), containsString("url=" + url));
    }

    @Test
    public void testGetResourcePaths() throws Exception
    {
        Server server = newServer();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);

        WebAppContext context = new WebAppContext(MavenTestingUtils.getBasePath().resolve("src/test/webapp-with-resources").toString(), "/");
        server.setHandler(context);
        server.start();

        ServletContext servletContext = context.getServletContext();

        Set<String> resourcePaths = servletContext.getResourcePaths("/");
        String[] expected = {
            "/WEB-INF/",
            "/nested-reserved-!#\\\\$%&()*+,:=?@[]-meta-inf-resource.txt",
            "/test.txt"
        };
        assertThat(resourcePaths.size(), is(expected.length));
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
            GET /resources HTTP/1.1\r
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
        context.setBaseResourceAsPath(warPath);
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
                .filter(FileID::isJavaArchive)
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
        context.setBaseResourceAsPath(warPath);

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

    @Test
    public void testRestartWebApp(WorkDir workDir) throws Exception
    {
        Server server = newServer();

        // Create war
        Path tempDir = workDir.getEmptyPathDir();
        Path testWebappDir = MavenPaths.projectBase().resolve("src/test/webapp");
        assertTrue(Files.exists(testWebappDir));
        Path warFile = tempDir.resolve("demo.war");

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI uri = URI.create("jar:" + warFile.toUri().toASCIIString());
        // Use ZipFS so that we can create paths that are just "/"
        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            Path root = zipfs.getPath("/");
            IO.copyDir(testWebappDir, root);
        }

        // Create WebAppContext
        WebAppContext context = new WebAppContext();
        ResourceFactory resourceFactory = context.getResourceFactory();
        Resource warResource = resourceFactory.newResource(warFile);
        context.setContextPath("/");
        context.setWarResource(warResource);
        context.setExtractWAR(true);

        server.setHandler(context);
        server.start();

        // Should not have failed the start of the WebAppContext
        assertTrue(context.isAvailable(), "WebAppContext should be available");

        // Test WebAppClassLoader contents for expected directory reference
        List<String> actualRefs = getWebAppClassLoaderUrlRefs(context);
        String[] expectedRefs = new String[]{
            "/webapp/WEB-INF/classes/",
            "/webapp/WEB-INF/lib/acme.jar!/",
            "/webapp/WEB-INF/lib/alpha.jar!/",
            "/webapp/WEB-INF/lib/omega.jar!/"
        };

        assertThat("URLs (sub) refs", actualRefs, containsInAnyOrder(expectedRefs));

        // Simulate a reload
        LOG.info("Stopping Initial Context");
        context.stop();
        LOG.info("Stopped Initial Context - waiting 2 seconds");
        Thread.sleep(2000);
        LOG.info("Touch War File: {}", warFile);
        touch(warFile);
        LOG.info("ReStarting Context");
        context.start();

        actualRefs = getWebAppClassLoaderUrlRefs(context);
        expectedRefs = new String[]{
            "/webapp/WEB-INF/classes/",
            "/webapp/WEB-INF/lib/acme.jar!/",
            "/webapp/WEB-INF/lib/alpha.jar!/",
            "/webapp/WEB-INF/lib/omega.jar!/"
        };
        assertThat("URLs (sub) refs", actualRefs, containsInAnyOrder(expectedRefs));
    }

    private void touch(Path path) throws IOException
    {
        FileTime now = FileTime.fromMillis(System.currentTimeMillis());
        Files.setLastModifiedTime(path, now);
    }

    private List<String> getWebAppClassLoaderUrlRefs(WebAppContext context)
    {
        ClassLoader contextClassLoader = context.getClassLoader();
        assertThat(contextClassLoader, instanceOf(WebAppClassLoader.class));
        WebAppClassLoader webAppClassLoader = (WebAppClassLoader)contextClassLoader;
        String webappTempDir = context.getTempDirectory().toString();
        List<String> actualRefs = new ArrayList<>();
        URL[] urls = webAppClassLoader.getURLs();
        for (URL url: urls)
        {
            String ref = url.toExternalForm();
            int idx = ref.indexOf(webappTempDir);
            // strip temp directory from URL (to make test easier to write)
            if (idx >= 0)
                ref = ref.substring(idx + webappTempDir.length());
            actualRefs.add(ref);
        }
        return actualRefs;
    }

    @Test
    public void testSetServerPropagation()
    {
        Server server = new Server();
        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        DefaultHandler handler = new DefaultHandler();
        server.setHandler(new Handler.Sequence(context, handler));

        assertThat(handler.getServer(), sameInstance(server));
    }
}
