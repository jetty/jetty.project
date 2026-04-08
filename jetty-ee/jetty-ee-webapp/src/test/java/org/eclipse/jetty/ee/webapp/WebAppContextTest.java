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

package org.eclipse.jetty.ee.webapp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.GenericServlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.ee.webapp.WebAppClassLoader;
import org.eclipse.jetty.ee.webapp.WebAppClassLoading;
import org.eclipse.jetty.ee.servlet.DefaultServlet;
import org.eclipse.jetty.ee.servlet.Dispatcher;
import org.eclipse.jetty.ee.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.ee.servlet.ServletChannel;
import org.eclipse.jetty.ee.servlet.ServletContextHandler;
import org.eclipse.jetty.ee.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.logging.StacklessLogging;
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
import org.eclipse.jetty.xml.XmlParser;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.either;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    /**
     * Create a webapp as a war on the fly.
     *
     * @param tempDir the directory into which the war will be generated
     * @param name the name of the war
     * @return the Path of the generated war
     *
     * @throws Exception if the war could not be created
     */
    private Path createWar(Path tempDir, String name) throws Exception
    {
        // Create war on the fly
        Path testWebappDir = MavenPaths.projectBase().resolve("src/test/webapp");
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

    @Test
    public void testTryWithResourceOnServletInputStreamWrapperDoesNotThrowSelfSuppressionNotPermitted() throws Exception
    {
        WebAppContext contextHandler = new WebAppContext();
        contextHandler.setBaseResource(ResourceFactory.root().newResource("."));
        var servlet = new HttpServlet()
        {
            final AtomicInteger readCounter = new AtomicInteger();
            final AtomicReference<Throwable> failureRef = new AtomicReference<>();

            @Override
            protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                InputStream servletInputStream = request.getInputStream();
                try (InputStream inputStream = new InputStream()
                {
                    @Override
                    public int read() throws IOException
                    {
                        return servletInputStream.read();
                    }

                    @Override
                    public void close() throws IOException
                    {
                        // There is no need to delegate the close().
                        servletInputStream.read();
                    }
                })
                {
                    while (true)
                    {
                        int read = inputStream.read();
                        if (read == -1)
                            break;
                        readCounter.incrementAndGet();
                    }
                }
                catch (Throwable x)
                {
                    failureRef.set(x);
                }
            }
        };
        contextHandler.addServlet(servlet, "/*");
        Server server = new Server();
        server.setHandler(contextHandler);

        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        server.start();

        try (StacklessLogging stackless = new StacklessLogging(ServletChannel.class))
        {
            String rawRequest = """
                POST / HTTP/1.1\r
                Host: test\r
                Connection: close\r
                Content-Length: 10\r
                \r
                01234""";

            LocalConnector.LocalEndPoint endPoint = connector.connect();
            endPoint.addInputAndExecute(rawRequest);
            await().atMost(5, TimeUnit.SECONDS).until(() -> servlet.readCounter.get() == 5);
            endPoint.close(new ArithmeticException());
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(servlet.failureRef.get(), instanceOf(ArithmeticException.class)));
        }
    }

    @Test
    public void testProtectedTargetErrorPage() throws Exception
    {
        WebAppContext contextHandler = new WebAppContext();
        contextHandler.setContextPath("/foo");
        contextHandler.setBaseResourceAsPath(Path.of("/tmp"));
        ServletHolder defaultHolder = new ServletHolder(new DefaultServlet());
        defaultHolder.setDisplayName("default");

        contextHandler.addServlet(defaultHolder, "/");
        contextHandler.addServlet(new OkServlet(), "/*");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");
        contextHandler.addServlet(GlobalErrorDumpServlet.class, "/global/*");
        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        errorPageErrorHandler.addErrorPage(404, "/error/TestException");
        errorPageErrorHandler.addErrorPage(ErrorPageErrorHandler.GLOBAL_ERROR_PAGE, "/global/TestException");
        contextHandler.setErrorHandler(errorPageErrorHandler);
        Server server = new Server();
        server.setHandler(contextHandler);

        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        server.start();

        try (StacklessLogging stackless = new StacklessLogging(ServletChannel.class))
        {
            String rawRequest = """
                GET /foo/WEB-INF/classes/this/does/not/exist HTTP/1.1\r
                Host: test\r
                Connection: close\r
                \r
                """;

            String rawResponse = connector.getResponse(rawRequest);

            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat(response.getStatus(), is(404));
            assertThat(response.getValuesList("ERRORDUMPSERVLET"), contains("ERRORDUMPSERVLET"));
            String content = response.getContent();
            assertThat(content, containsString("ERROR_REQUEST_URI: /foo/WEB-INF/classes/this/does/not/exist"));
            assertThat(content, containsString("getRequestURI()=[/foo/error/TestException]"));
            assertThat(content, containsString("DISPATCH: ERROR"));
            assertThat(content, not(containsString("GLOBALERRORDUMPSERVLET")));
        }
    }

    @Test
    public void testDefaultContextPath() throws Exception
    {
        Server server = newServer();
        Path webXml = MavenPaths.findTestResourceFile("web-with-default-context-path.xml");
        Path webXmlEmptyPath = MavenPaths.findTestResourceFile("web-with-empty-default-context-path.xml");
        Path webDefaultXml = MavenPaths.findTestResourceFile("web-default-with-default-context-path.xml");
        Path overrideWebXml = MavenPaths.findTestResourceFile("override-web-with-default-context-path.xml");

        WebAppContext wac = new WebAppContext();
        wac.setBaseResourceAsPath(MavenPaths.targetTests());
        server.setHandler(wac);

        //test that an empty default-context-path defaults to root
        wac.setDescriptor(webXmlEmptyPath.toString());
        server.start();
        assertEquals("/", wac.getContextPath());

        server.stop();

        //test web-default.xml value is used
        wac.setDescriptor(null);
        wac.setDefaultsDescriptor(webDefaultXml.toString());
        server.start();
        assertEquals("/one", wac.getContextPath());

        server.stop();

        //test web.xml value is used
        wac.setDescriptor(webXml.toString());
        server.start();
        assertEquals("/two", wac.getContextPath());

        server.stop();

        //test override-web.xml value is used
        wac.setOverrideDescriptor(overrideWebXml.toString());
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
        assertArrayEquals(classNames, wac.getConfigurationClasses());
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
        expectedConfigurations.add("org.eclipse.jetty.ee.webapp.WebInfConfiguration");
        expectedConfigurations.add("org.eclipse.jetty.ee.webapp.WebXmlConfiguration");
        expectedConfigurations.add("org.eclipse.jetty.ee.webapp.MetaInfConfiguration");
        expectedConfigurations.add("org.eclipse.jetty.ee.webapp.FragmentConfiguration");
        expectedConfigurations.add("org.eclipse.jetty.ee.webapp.JaasConfiguration");
        expectedConfigurations.add("org.eclipse.jetty.ee.webapp.WebAppConfiguration");
        expectedConfigurations.add("org.eclipse.jetty.ee.webapp.JettyWebXmlConfiguration");

        assertThat(actualConfigurations, Matchers.contains(expectedConfigurations.toArray()));
    }

    @Test
    public void testConfigurationInstances()
    {
        Configurations.cleanKnown();
        Configuration[] configs = {new WebInfConfiguration()};
        WebAppContext wac = new WebAppContext();
        wac.setConfigurations(configs);
        assertThat(wac.getConfigurations(), contains(configs));

        //test that explicit config instances override any from server
        String[] classNames = {"x.y.z"};
        Server server = newServer();
        server.setAttribute(Configurations.SERVER_DEFAULT_ATTR, classNames);
        wac.setServer(server);
        assertThat(wac.getConfigurations(), contains(configs));
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
    @Test
    public void testContextWhiteList() throws Exception
    {
        Server server = newServer();
        Handler.Sequence handlers = new Handler.Sequence();
        WebAppContext contextA = new WebAppContext(".", "/A");
        contextA.addServlet(ServletA.class, "/s");
        contextA.setCrossContextDispatchSupported(true);
        handlers.addHandler(contextA);

        WebAppContext contextB = new WebAppContext(".", "/B");
        contextB.addServlet(ServletB.class, "/s");
        contextB.setContextWhiteList("/doesnotexist", "/B/s");
        contextB.setCrossContextDispatchSupported(true);
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
        Path testWebapp = MavenPaths.projectBase().resolve("src/test/webapp");
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
        Path testWebapp = MavenPaths.projectBase().resolve("src/test/webapp");
        context.setBaseResourceAsPath(testWebapp);
        context.setContextPath("/");
        server.setHandler(contexts);
        contexts.addHandler(context);

        server.start();

        assertThat(HttpTester.parseResponse(connector.getResponse(
                """
                    GET %s HTTP/1.1\r
                    Host: localhost:8080\r
                    Connection: close\r
                    \r
                    """.formatted(path))).getStatus(),
            Matchers.anyOf(is(HttpStatus.BAD_REQUEST_400)));
    }

    @Test
    public void testNullPath() throws Exception
    {
        Server server = newServer();

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        WebAppContext context = new WebAppContext();
        Path testWebapp = MavenPaths.projectBase().resolve("src/test/webapp");
        context.setBaseResourceAsPath(testWebapp);
        context.setContextPath("/");
        server.setHandler(contexts);
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

        Path testWebapp = MavenPaths.projectBase().resolve("src/test/webapp");
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

    public static class ErrorDumpServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            if (request.getDispatcherType() != DispatcherType.ERROR && request.getDispatcherType() != DispatcherType.ASYNC)
                throw new IllegalStateException("Bad Dispatcher Type " + request.getDispatcherType());

            response.setHeader("ERRORDUMPSERVLET", "ERRORDUMPSERVLET");

            PrintWriter writer = response.getWriter();
            writer.println("DISPATCH: " + request.getDispatcherType().name());
            writer.println("ERROR_PAGE: " + request.getPathInfo());
            writer.println(request.getAttribute(Dispatcher.ERROR_STATUS_CODE));
            writer.println(request.getAttribute(Dispatcher.ERROR_MESSAGE));
            writer.println("ERROR_MESSAGE: " + request.getAttribute(Dispatcher.ERROR_MESSAGE));
            writer.println("ERROR_CODE: " + request.getAttribute(Dispatcher.ERROR_STATUS_CODE));
            writer.println("ERROR_EXCEPTION: " + request.getAttribute(Dispatcher.ERROR_EXCEPTION));
            writer.println("ERROR_EXCEPTION_TYPE: " + request.getAttribute(Dispatcher.ERROR_EXCEPTION_TYPE));
            writer.println("ERROR_SERVLET: " + request.getAttribute(Dispatcher.ERROR_SERVLET_NAME));
            writer.println("ERROR_REQUEST_URI: " + request.getAttribute(Dispatcher.ERROR_REQUEST_URI));

            writer.printf("getRequestURI()=%s%n", valueOf(request.getRequestURI()));
            writer.printf("getRequestURL()=%s%n", valueOf(request.getRequestURL()));
            writer.printf("getQueryString()=%s%n", valueOf(request.getQueryString()));
            Map<String, String[]> params = request.getParameterMap();
            writer.printf("getParameterMap().size=%d%n", params.size());
            for (Map.Entry<String, String[]> entry : params.entrySet())
            {
                String value = null;
                if (entry.getValue() != null)
                {
                    value = String.join(", ", entry.getValue());
                }
                writer.printf("getParameterMap()[%s]=%s%n", entry.getKey(), valueOf(value));
            }
        }

        protected String valueOf(Object obj)
        {
            if (obj == null)
                return "null";
            return valueOf(obj.toString());
        }

        protected String valueOf(String str)
        {
            if (str == null)
                return "null";
            return String.format("[%s]", str);
        }
    }

    public static class GlobalErrorDumpServlet extends ErrorDumpServlet
    {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            if (request.getDispatcherType() != DispatcherType.ERROR && request.getDispatcherType() != DispatcherType.ASYNC)
                throw new IllegalStateException("Bad Dispatcher Type " + request.getDispatcherType());

            response.setHeader("GLOBALERRORDUMPSERVLET", "GLOBALERRORDUMPSERVLET");
            PrintWriter writer = response.getWriter();
            writer.println("GLOBAL DISPATCH: " + request.getDispatcherType().name());
            writer.println("GLOBAL ERROR_PAGE: " + request.getPathInfo());
            writer.println("GLOBAL ERROR_MESSAGE: " + request.getAttribute(Dispatcher.ERROR_MESSAGE));
            writer.println("GLOBAL ERROR_CODE: " + request.getAttribute(Dispatcher.ERROR_STATUS_CODE));
            writer.println("GLOBAL ERROR_EXCEPTION: " + request.getAttribute(Dispatcher.ERROR_EXCEPTION));
            writer.println("GLOBAL ERROR_EXCEPTION_TYPE: " + request.getAttribute(Dispatcher.ERROR_EXCEPTION_TYPE));
            writer.println("GLOBAL ERROR_SERVLET: " + request.getAttribute(Dispatcher.ERROR_SERVLET_NAME));
            writer.println("GLOBAL ERROR_REQUEST_URI: " + request.getAttribute(Dispatcher.ERROR_REQUEST_URI));

            writer.printf("getRequestURI()=%s%n", valueOf(request.getRequestURI()));
            writer.printf("getRequestURL()=%s%n", valueOf(request.getRequestURL()));
            writer.printf("getQueryString()=%s%n", valueOf(request.getQueryString()));
            Map<String, String[]> params = request.getParameterMap();
            writer.printf("getParameterMap().size=%d%n", params.size());
            for (Map.Entry<String, String[]> entry : params.entrySet())
            {
                String value = null;
                if (entry.getValue() != null)
                {
                    value = String.join(", ", entry.getValue());
                }
                writer.printf("getParameterMap()[%s]=%s%n", entry.getKey(), valueOf(value));
            }
        }
    }

    @Test
    public void testJarFileBaseResource(WorkDir workDir) throws Exception
    {
        //create a war that we can use as the resource base
        Path warPath = createWar(workDir.getEmptyPathDir(), "test.war");
        warPath = warPath.toAbsolutePath();
        URI warURI =  URIUtil.toJarFileUri(warPath.toUri());

        Server server = null;
        WebAppContext context = null;

        server = newServer();

        context = new WebAppContext();
        context.setContextPath("/");
        context.setBaseResourceAsString(warURI.toString());
        assertNotNull(context.getBaseResource());
        server.setHandler(context);
        server.start();

        server.stop();
        assertNotNull(context.getBaseResource());

        //Cause the non-lifecycle ResourceFactory in the context
        //to be closed, thus closing the jar:file Resource that
        //was created by the setBaseResourceAsString() method
        server.destroy();
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
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

        Path warRoot = MavenPaths.projectBase().resolve("src/test/webapp-with-resources");
        WebAppContext context = new WebAppContext();
        context.setBaseResourceAsPath(warRoot);
        context.setContextPath("/");
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

        Path extLibs = MavenPaths.findTestResourceDir("ext");
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
        Path testPath = MavenPaths.targetTestDir("testExtraClasspathGlob");
        FS.ensureDirExists(testPath);
        FS.ensureEmpty(testPath);

        Server server = newServer();

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        Path warPath = createWar(testPath, "test.war");
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
        Path extLibsDir = MavenPaths.findTestResourceDir("ext");
        extLibsDir = extLibsDir.toAbsolutePath();
        List<URI> expectedUris;
        try (Stream<Path> s = Files.list(extLibsDir))
        {
            expectedUris = s
                .filter(Files::isRegularFile)
                .filter(FileID::isJavaArchive)
                .sorted(Comparator.naturalOrder())
                .map(Path::toUri)
                .collect(Collectors.toList());
        }

        List<URI> actualURIs = Stream.of(webAppClassLoader.getURLs())
            .map(WebAppContextTest::toURI)
            .filter(notInTempDirectory(context))
            .toList();

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
        Path testPath = MavenPaths.targetTestDir("testExtraClasspathDir");
        FS.ensureDirExists(testPath);
        FS.ensureEmpty(testPath);

        Server server = newServer();

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        Path warPath = createWar(testPath, "test.war");
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
        List<URI> urls = Stream.of(webAppClassLoader.getURLs())
            .map(WebAppContextTest::toURI)
            .filter(notInTempDirectory(context))
            .toList();
        assertThat("URLs", urls.size(), is(1));
        Path extLibs = MavenPaths.findTestResourceDir("ext");
        extLibs = extLibs.toAbsolutePath();
        assertThat("URL[0]", urls.get(0), is(extLibs.toUri()));
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
        Path servletTempDir = tempDir.resolve("tmp");
        Files.createDirectory(servletTempDir);
        File servletTempDirFile = servletTempDir.toFile();
        ResourceFactory resourceFactory = context.getResourceFactory();
        Resource warResource = resourceFactory.newResource(warFile);
        context.setContextPath("/");
        context.setWarResource(warResource);
        context.setExtractWAR(true);
        context.setTempDirectory(servletTempDir.toFile());
        assertThat(context.getTempDirectory(), is(servletTempDirFile));

        server.setHandler(context);
        server.start();

        // Should not have failed the start of the WebAppContext
        assertTrue(context.isAvailable(), "WebAppContext should be available");

        // Test WebAppClassLoader contents for expected directory reference
        List<String> actualRefs = getWebAppClassLoaderUrlRefs(context);
        String[] expectedRefs = new String[]{
            "/webapp/WEB-INF/classes/",
            "/webapp/WEB-INF/lib/acme.jar",
            "/webapp/WEB-INF/lib/alpha.jar",
            "/webapp/WEB-INF/lib/omega.jar"
        };

        assertThat("URLs (sub) refs", actualRefs, containsInAnyOrder(expectedRefs));

        // Simulate a reload
        LOG.info("Stopping Initial Context");
        context.stop();
        LOG.info("Stopped Initial Context - waiting 2 seconds");
        assertThat(context.getTempDirectory(), is(servletTempDirFile));
        //the TEMPDIR should be a persistent attribute
        assertNotNull(context.getAttribute(ServletContext.TEMPDIR));
        //as the TEMPDIR is a persistent attribute, it should exist even afer a stop
        assertNotNull(context.getServletContext().getAttribute(ServletContext.TEMPDIR));

        Thread.sleep(2000);
        LOG.info("Touch War File: {}", warFile);
        touch(warFile);
        LOG.info("ReStarting Context");
        context.start();
        assertThat(context.getTempDirectory(), is(servletTempDirFile));
        assertNotNull(context.getServletContext().getAttribute(ServletContext.TEMPDIR));
        assertNotNull(context.getAttribute(ServletContext.TEMPDIR));

        actualRefs = getWebAppClassLoaderUrlRefs(context);
        expectedRefs = new String[]{
            "/webapp/WEB-INF/classes/",
            "/webapp/WEB-INF/lib/acme.jar",
            "/webapp/WEB-INF/lib/alpha.jar",
            "/webapp/WEB-INF/lib/omega.jar"
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

    @Test
    public void testAddHiddenClasses() throws Exception
    {
        Server server = newServer();

        String testPattern = "org.eclipse.jetty.ee.webapp.test.";

        WebAppClassLoading.addHiddenClasses(server, testPattern);

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");

        Path testPath = MavenPaths.targetTestDir("testAddServerClasses");
        FS.ensureDirExists(testPath);
        FS.ensureEmpty(testPath);
        Path warPath = createWar(testPath, "test.war");
        context.setBaseResource(context.getResourceFactory().newResource(warPath));

        // Check context specific
        context.getHiddenClassMatcher().add("org.context.specific.");

        server.setHandler(context);
        server.start();

        List<String> hiddenClasses = List.of(context.getHiddenClasses());
        assertThat("Should have environment specific test pattern", hiddenClasses, hasItem(testPattern));
        assertThat("Should have pattern from defaults", hiddenClasses, hasItem("org.eclipse.jetty."));
        assertThat("Should have pattern from JaasConfiguration", hiddenClasses, hasItem("-org.eclipse.jetty.security.jaas."));
        for (String defaultServerClass: WebAppClassLoading.DEFAULT_HIDDEN_CLASSES)
            assertThat("Should have default patterns", hiddenClasses, hasItem(defaultServerClass));

        assertThat("context API", hiddenClasses, hasItem("org.context.specific."));
    }

    @Test
    public void testAddProtectedClasses() throws Exception
    {
        Server server = newServer();

        String testPattern = "org.eclipse.jetty.ee.webapp.test.";

        WebAppClassLoading.addProtectedClasses(server, testPattern);

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        Path testPath = MavenPaths.targetTestDir("testAddServerClasses");
        FS.ensureDirExists(testPath);
        FS.ensureEmpty(testPath);
        Path warPath = createWar(testPath, "test.war");
        context.setBaseResource(context.getResourceFactory().newResource(warPath));

        // Check context specific
        context.getProtectedClassMatcher().add("org.context.specific.");

        server.setHandler(context);
        server.start();

        List<String> protectedClasses = List.of(context.getProtectedClasses());
        assertThat("Should have environment specific test pattern", protectedClasses, hasItem(testPattern));
        assertThat("Should have pattern from defaults", protectedClasses, hasItem("javax."));
        assertThat("Should have pattern from defaults", protectedClasses, hasItem("jakarta."));
        assertThat("Should have pattern from JaasConfiguration", protectedClasses, hasItem("org.eclipse.jetty.security.jaas."));
        for (String defaultSystemClass: WebAppClassLoading.DEFAULT_PROTECTED_CLASSES)
            assertThat("Should have default patterns", protectedClasses, hasItem(defaultSystemClass));

        assertThat("context API", protectedClasses, hasItem("org.context.specific."));
    }

    @Test
    public void testAmbiguousPaths() throws Exception
    {
        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        Path testPath = MavenPaths.targetTestDir("testAmbiguousPaths");
        FS.ensureEmpty(testPath);
        context.setBaseResource(context.getResourceFactory().newResource(testPath));

        Server server = newServer();
        server.setHandler(context);
        server.start();

        HttpClient client = new HttpClient();
        client.start();

        try
        {
            String baseURI = "http://%s".formatted(server.getURI().getAuthority());

            for (String ambiguous : new String[] {"/foo%2Fbar", "/foo//bar", "/foo/..;/bar", "/foo/%2e%2e;param/bar"})
            {
                ContentResponse response = client.GET(baseURI + ambiguous);
                assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus(), new ResponseDetails(response));
            }
        }
        finally
        {
            LifeCycle.stop(client);
        }
    }

    protected static class ResponseDetails implements Supplier<String>
    {
        private final ContentResponse response;

        public ResponseDetails(ContentResponse response)
        {
            this.response = response;
        }

        @Override
        public String get()
        {
            StringBuilder ret = new StringBuilder();
            ret.append(response.toString()).append(System.lineSeparator());
            ret.append(response.getHeaders().toString()).append(System.lineSeparator());
            ret.append(response.getContentAsString()).append(System.lineSeparator());
            return ret.toString();
        }
    }

    @Test
    public void testCustomXmlParser(WorkDir workDir) throws Exception
    {
        Server server = newServer();

        MyXmlParser xmlParser = new MyXmlParser();
        WebAppContext context = new WebAppContext();
        context.getMetaData().setXmlParser(xmlParser);
        context.setBaseResourceAsPath(workDir.getEmptyPathDir());

        server.setHandler(context);
        server.start();

        assertTrue(context.isStarted());
        assertTrue(context.isAvailable());
        XmlParser startedXmlParser = context.getMetaData().getXmlParser();
        assertSame(startedXmlParser, xmlParser);
    }

    public static class MyXmlParser extends XmlParser
    {
    }

    public static class OkServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
        {
            resp.setStatus(200);
        }
    }

    private static URI toURI(URL url)
    {
        try
        {
            return url.toURI();
        }
        catch (URISyntaxException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static Predicate<URI> notInTempDirectory(WebAppContext context)
    {
        String tempDirUri = context.getTempDirectory().toURI().toASCIIString();
        return (uri) ->
        {
            // we don't want to see entries that are in the webapp temp dir
            // Such as <temp-dir>/WEB-INF/classes
            //      or <temp-dir>/WEB-INF/lib/*.jar
            return !uri.toASCIIString().startsWith(tempDirUri);
        };
    }
}
