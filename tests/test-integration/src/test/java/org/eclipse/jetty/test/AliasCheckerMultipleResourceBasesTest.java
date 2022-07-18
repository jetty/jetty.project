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
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SymlinkAllowedResourceAliasChecker;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class AliasCheckerMultipleResourceBasesTest
{
    private static Server _server;
    private static ServerConnector _connector;
    private static HttpClient _client;
    private static ServletContextHandler _context;
    private static Path _webRootPath;
    private static Path _altDir1Symlink;
    private static Path _altDir2Symlink;

    private static Path getResource(String path) throws Exception
    {
        URL url = AliasCheckerMultipleResourceBasesTest.class.getClassLoader().getResource(path);
        assertNotNull(url);
        return new File(url.toURI()).toPath();
    }

    private static void delete(Path path)
    {
        IO.delete(path.toFile());
    }

    private static void setAliasCheckers(ContextHandler.AliasCheck... aliasChecks)
    {
        _context.clearAliasChecks();
        if (aliasChecks != null)
        {
            for (ContextHandler.AliasCheck aliasCheck : aliasChecks)
            {
                _context.addAliasCheck(aliasCheck);
            }
        }
    }

    @BeforeAll
    public static void beforeAll() throws Exception
    {
        _webRootPath = getResource("webroot");

        _altDir1Symlink = _webRootPath.resolve("../altDir1Symlink");
        delete(_altDir1Symlink);
        Path altDir1 = _webRootPath.resolve("../altDir1").toAbsolutePath();
        Files.createSymbolicLink(_altDir1Symlink, altDir1).toFile().deleteOnExit();

        _altDir2Symlink = _webRootPath.resolve("../altDir2Symlink");
        delete(_altDir2Symlink);
        Path altDir2 = _webRootPath.resolve("../altDir2").toAbsolutePath();
        Files.createSymbolicLink(_altDir2Symlink, altDir2).toFile().deleteOnExit();

        // Create and start Server and Client.
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);
        _context = new ServletContextHandler();

        _context.setContextPath("/");
        _context.setBaseResource(new PathResource(_webRootPath));
        _context.setWelcomeFiles(new String[]{"index.html"});
        _context.getMimeTypes().addMimeMapping("txt", "text/plain;charset=utf-8");
        _server.setHandler(_context);
        _context.clearAliasChecks();

        _client = new HttpClient();
        _client.start();
    }

    @AfterAll
    public static void afterAll() throws Exception
    {
        Files.delete(_altDir1Symlink);
        Files.delete(_altDir2Symlink);

        _client.stop();
        _server.stop();
    }

    @Test
    public void test() throws Exception
    {
        System.err.println(_webRootPath.toAbsolutePath());

        ServletHolder servletHolder;
        servletHolder = _context.addServlet(DefaultServlet.class, "/defaultServlet1/*");
        servletHolder.setInitParameter("resourceBase", _altDir1Symlink.toString());
        servletHolder.setInitParameter("pathInfoOnly", "true");
        servletHolder = _context.addServlet(DefaultServlet.class, "/defaultServlet2/*");
        servletHolder.setInitParameter("resourceBase", _altDir2Symlink.toString());
        servletHolder.setInitParameter("pathInfoOnly", "true");

        setAliasCheckers(
            new SymlinkAllowedResourceAliasChecker(_context, Resource.newResource(_altDir1Symlink)),
            new SymlinkAllowedResourceAliasChecker(_context, Resource.newResource(_altDir2Symlink))
        );

        _server.start();

        // Can access file 1 only through default servlet 1.
        URI uri = URI.create("http://localhost:" + _connector.getLocalPort() + "/defaultServlet1/file1");
        ContentResponse response = _client.GET(uri);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), is("file 1 contents"));

        // File 2 cannot be found with default servlet 1.
        uri = URI.create("http://localhost:" + _connector.getLocalPort() + "/defaultServlet1/file2");
        response = _client.GET(uri);
        assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));

        // Can access file 2 only through default servlet 2.
        uri = URI.create("http://localhost:" + _connector.getLocalPort() + "/defaultServlet2/file2");
        response = _client.GET(uri);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), is("file 2 contents"));

        // File 1 cannot be found with default servlet 2.
        uri = URI.create("http://localhost:" + _connector.getLocalPort() + "/defaultServlet2/file1");
        response = _client.GET(uri);
        assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));
    }
}
