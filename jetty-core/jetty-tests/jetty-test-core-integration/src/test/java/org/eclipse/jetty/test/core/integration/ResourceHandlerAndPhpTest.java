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

package org.eclipse.jetty.test.core.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.ResourceService;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.PathMappingsHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.handler.TryPathsHandler;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ResourceHandlerAndPhpTest
{
    private static final Logger LOG = LoggerFactory.getLogger(ResourceHandlerAndPhpTest.class);
    private static final String ATTR_ORIGINAL_PATH = "test.originalPath";
    private static final String ATTR_ORIGINAL_QUERY = "test.originalQuery";

    private static Server server;
    private static LocalConnector localConnector;

    @BeforeAll
    public static void setup(WorkDir workDir) throws Exception
    {
        // Build docroot
        Path docroot = workDir.getEmptyPathDir();

        Files.writeString(docroot.resolve("index.php"), "This is /index.php");
        Path cssDir = docroot.resolve("css");
        Files.createDirectory(cssDir);
        Files.writeString(cssDir.resolve("main.css"), "This is the /css/main.css");
        Path wpAdminDir = docroot.resolve("wp-admin");
        Files.createDirectory(wpAdminDir);
        Files.writeString(wpAdminDir.resolve("index.php"), "This is the /wp-admin/index.php");

        // Setup Handler Tree
        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/");
        contextHandler.setBaseResource(ResourceFactory.of(contextHandler).newResource(docroot));

        TryPathsHandler tryPathsHandler = new TryPathsHandler();
        tryPathsHandler.setOriginalPathAttribute(ATTR_ORIGINAL_PATH);
        tryPathsHandler.setOriginalQueryAttribute(ATTR_ORIGINAL_QUERY);
        tryPathsHandler.setPaths(List.of("/maintenance.txt", "$path", "$pathindex.php", "/index.php?p=$path"));
        contextHandler.setHandler(tryPathsHandler);

        ResourceHandler resourceHandler = new ResourceHandler();
        // configure ResourceHandler to skip attempts at directories it cannot serve
        resourceHandler.setDirectoryBehavior(ResourceService.DirectoryBehavior.SKIP);

        PhpHandler phpHandler = new PhpHandler();
        phpHandler.addVirtualPath("/messaging/");

        PathMappingsHandler pathMappingsHandler = new PathMappingsHandler();
        pathMappingsHandler.addMapping(new ServletPathSpec("/"), resourceHandler);
        pathMappingsHandler.addMapping(new ServletPathSpec("*.php"), phpHandler);

        tryPathsHandler.setHandler(pathMappingsHandler);

        server = new Server();
        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);
        ContextHandlerCollection contextHandlerCollection = new ContextHandlerCollection();
        contextHandlerCollection.setHandlers(contextHandler);
        server.setHandler(contextHandlerCollection);
        server.start();
    }

    @AfterAll
    public static void teardown()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testRequestNotExists() throws Exception
    {
        HttpTester.Response response = issueRequest(
            """
                GET /not-exists HTTP/1.1
                Host: local
                Connection: close
                                
                """
        );
        assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
    }

    @Test
    public void testRequestRoot() throws Exception
    {
        HttpTester.Response response = issueRequest(
            """
                GET / HTTP/1.1
                Host: local
                Connection: close
                                
                """
        );
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertThat(response.get("X-Php"), is("/index.php"));
    }

    @Test
    public void testRequestMainCss() throws Exception
    {
        HttpTester.Response response = issueRequest(
            """
                GET /css/main.css?v=1.23 HTTP/1.1
                Host: local
                Connection: close
                                
                """
        );
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertThat(response.getContent(), is("This is the /css/main.css"));
    }

    @Test
    public void testRequestWpAdmin() throws Exception
    {
        HttpTester.Response response = issueRequest(
            """
                GET /wp-admin/ HTTP/1.1
                Host: local
                Connection: close
                                
                """
        );
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertThat(response.get("X-Php"), is("/wp-admin/index.php"));
        assertThat(response.getContent(), containsString("originalPath=[/wp-admin/]"));
    }

    @Test
    public void testRequestMessaging() throws Exception
    {
        HttpTester.Response response = issueRequest(
            """
                GET /messaging/ HTTP/1.1
                Host: local
                Connection: close
                                
                """
        );
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertThat(response.get("X-Php"), is("/messaging/index.php"));
        assertThat(response.getContent(), containsString("originalPath=[/messaging/]"));
    }

    private HttpTester.Response issueRequest(String rawRequest) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Raw Request: " + rawRequest);

        String rawResponse = localConnector.getResponse(rawRequest);
        if (LOG.isDebugEnabled())
            LOG.debug("Raw Response: " + rawResponse);

        return HttpTester.parseResponse(rawResponse);
    }

    private static class PhpHandler extends Handler.Abstract
    {
        private Resource resourceBase;
        private List<String> virtualPaths = new ArrayList<>();

        public void addVirtualPath(String path)
        {
            virtualPaths.add(path);
        }

        @Override
        protected void doStart()
        {
            if (resourceBase == null)
            {
                Context context = ContextHandler.getCurrentContext();
                if (context != null)
                    resourceBase = context.getBaseResource();
            }

            if (resourceBase == null)
                throw new IllegalStateException("Cannot have a null resourceBase");
        }

        private boolean canServeRequest(String path)
        {
            if (virtualPaths.contains(path))
                return true;

            Resource resolvedInBase = resourceBase.resolve(path);
            return Resources.exists(resolvedInBase);
        }

        @Override
        public Request.Processor handle(Request request)
        {
            final String originalPath = (String)request.getAttribute(ATTR_ORIGINAL_PATH);
            // mimic the PHP behavior.
            if (!canServeRequest(originalPath))
            {
                // cannot serve it, have this "php" handler return a 404
                return new Handler.Processor()
                {
                    @Override
                    public void process(Request request, Response response, Callback callback)
                    {
                        Response.writeError(request, response, callback, HttpStatus.NOT_FOUND_404);
                    }
                };
            }

            return new Handler.Processor()
            {
                @Override
                public void process(Request request, Response response, Callback callback)
                {
                    response.setStatus(HttpStatus.OK_200);
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");
                    response.getHeaders().put("X-Php", request.getHttpURI().getPathQuery());
                    String originalQuery = (String)request.getAttribute(ATTR_ORIGINAL_QUERY);
                    String message = """
                        PHP:
                        pathInContext=[%s]
                        httpUri.query=[%s]
                        originalPath=[%s]
                        originalQuery=[%s]
                        """
                        .formatted(
                            Request.getPathInContext(request),
                            request.getHttpURI().getQuery(),
                            originalPath,
                            originalQuery
                        );
                    Content.Sink.write(response, true, message, callback);
                }
            };
        }
    }
}
