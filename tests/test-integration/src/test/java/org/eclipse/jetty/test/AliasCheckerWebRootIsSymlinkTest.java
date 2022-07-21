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

package org.eclipse.jetty.test;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SymlinkAllowedResourceAliasChecker;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.PathResource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class AliasCheckerWebRootIsSymlinkTest
{
    private static Server _server;
    private static ServerConnector _connector;
    private static HttpClient _client;
    private static ServletContextHandler _context;
    private static Path _webrootSymlink;

    private static Path getResource(String path) throws Exception
    {
        URL url = AliasCheckerWebRootIsSymlinkTest.class.getClassLoader().getResource(path);
        assertNotNull(url);
        return new File(url.toURI()).toPath();
    }

    private static void delete(Path path)
    {
        IO.delete(path.toFile());
    }

    private static void setAliasChecker(ContextHandler.AliasCheck aliasChecker)
    {
        _context.clearAliasChecks();
        if (aliasChecker != null)
            _context.addAliasCheck(aliasChecker);
    }

    @BeforeAll
    public static void beforeAll() throws Exception
    {
        Path webRootPath = getResource("webroot");

        // External symlink to webroot.
        _webrootSymlink = webRootPath.resolve("../webrootSymlink");
        delete(_webrootSymlink);
        Files.createSymbolicLink(_webrootSymlink, webRootPath).toFile().deleteOnExit();

        // Create and start Server and Client.
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);
        _context = new ServletContextHandler();

        _context.setContextPath("/");
        _context.setBaseResource(new PathResource(_webrootSymlink));
        _context.setWelcomeFiles(new String[]{"index.html"});
        _context.getMimeTypes().addMimeMapping("txt", "text/plain;charset=utf-8");
        _server.setHandler(_context);
        _context.addServlet(DefaultServlet.class, "/");
        _context.clearAliasChecks();

        _client = new HttpClient();
        _client.start();
    }

    @AfterAll
    public static void afterAll() throws Exception
    {
        // Try to delete all files now so that the symlinks do not confuse other tests.
        Files.delete(_webrootSymlink);

        _client.stop();
        _server.stop();
    }

    @Test
    public void test() throws Exception
    {
        // WEB-INF is a protected target, and enable symlink alias checker.
        _context.setProtectedTargets(new String[]{"/WEB-INF"});
        setAliasChecker(new SymlinkAllowedResourceAliasChecker(_context));

        CompletableFuture<InputStream> resource = new CompletableFuture<>();
        _context.addEventListener(new ServletContextListener()
        {
            @Override
            public void contextInitialized(ServletContextEvent sce)
            {
                try
                {
                    // Getting resource with API should allow you to bypass security constraints.
                    resource.complete(sce.getServletContext().getResourceAsStream("/WEB-INF/web.xml"));
                }
                catch (Throwable e)
                {
                    throw new RuntimeException(e);
                }
            }
        });
        _server.start();
        assertThat(_context.getBaseResource().isAlias(), equalTo(false));

        // We can access web.xml with ServletContext.getResource().
        InputStream webXml = resource.get(5, TimeUnit.SECONDS);
        assertNotNull(webXml);
        String content = IO.toString(webXml);
        assertThat(content, equalTo("This is the web.xml file."));

        // Can access normal files in the webroot dir.
        URI uri = URI.create("http://localhost:" + _connector.getLocalPort() + "/file");
        ContentResponse response = _client.GET(uri);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), is("This file is inside webroot."));

        // Cannot access web.xml with an external request.
        uri = URI.create("http://localhost:" + _connector.getLocalPort() + "/WEB-INF/web.xml");
        response = _client.GET(uri);
        assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));
        assertThat(response.getContentAsString(), not(containsString("This file is inside webroot.")));
    }
}
