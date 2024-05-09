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

package org.eclipse.jetty.test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.AliasCheck;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SymlinkAllowedResourceAliasChecker;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AliasCheckerMultipleResourceBasesTest
{
    private Server _server;
    private ServerConnector _connector;
    private HttpClient _client;
    private ContextHandler _context;
    private Path _baseResource1;
    private Path _baseResource2;

    private static Path getResource(String path) throws Exception
    {
        URL url = AliasCheckerMultipleResourceBasesTest.class.getClassLoader().getResource(path);
        assertNotNull(url);
        return new File(url.toURI()).toPath();
    }

    private static void delete(Path path)
    {
        if (path != null)
            IO.delete(path.toFile());
    }

    private void setAliasCheckers(AliasCheck... aliasChecks)
    {
        _context.clearAliasChecks();
        if (aliasChecks != null)
        {
            for (AliasCheck aliasCheck : aliasChecks)
            {
                _context.addAliasCheck(aliasCheck);
            }
        }
    }

    @BeforeEach
    public void before() throws Exception
    {
        Path webRootPath = getResource("webroot");

        _baseResource1 = webRootPath.resolve("../altDir1").toAbsolutePath();
        delete(_baseResource1);
        Files.createDirectory(_baseResource1);
        Path file1Symlink = _baseResource1.resolve("file1");
        Files.createSymbolicLink(file1Symlink, getResource("file1")).toFile().deleteOnExit();

        _baseResource2 = webRootPath.resolve("../altDir2").toAbsolutePath();
        delete(_baseResource2);
        Files.createDirectory(_baseResource2);
        Path file2Symlink = _baseResource2.resolve("file2");
        delete(file2Symlink);
        Files.createSymbolicLink(file2Symlink, getResource("file2")).toFile().deleteOnExit();

        // Create and start Server and Client.
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);
        _context = new ContextHandler();

        _context.setContextPath("/");
        _context.setBaseResourceAsPath(webRootPath);
        _server.setHandler(_context);
        _context.clearAliasChecks();

        _client = new HttpClient();
        _client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        delete(_baseResource1);
        delete(_baseResource2);

        _client.stop();
        _server.stop();
    }

    private ResourceHandler newResourceHandler(Path resourceBase)
    {
        Resource resource = toResource(resourceBase);
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setBaseResource(resource);
        return resourceHandler;
    }

    private Resource toResource(Path path)
    {
        return ResourceFactory.root().newResource(path);
    }

    @Test
    public void test() throws Exception
    {
        Handler.Sequence handlers = new Handler.Sequence();
        handlers.addHandler(newResourceHandler(_baseResource1));
        handlers.addHandler(newResourceHandler(_baseResource2));
        _context.setHandler(handlers);
        _server.start();

        // With no alias checkers we cannot access file 1.
        URI uri = URI.create("http://localhost:" + _connector.getLocalPort() + "/file1");
        ContentResponse response = _client.GET(uri);
        assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));

        // With no alias checkers we cannot access file 2.
        uri = URI.create("http://localhost:" + _connector.getLocalPort() + "/file2");
        response = _client.GET(uri);
        assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));

        // Set alias checkers to allow content under these alternative resource bases.
        setAliasCheckers(
            new SymlinkAllowedResourceAliasChecker(_context, toResource(_baseResource1)),
            new SymlinkAllowedResourceAliasChecker(_context, toResource(_baseResource2)));

        // Now we have set alias checkers we can access file 1.
        uri = URI.create("http://localhost:" + _connector.getLocalPort() + "/file1");
        response = _client.GET(uri);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), is("file 1 contents"));

        // Now we have set alias checkers we can access file 2.
        uri = URI.create("http://localhost:" + _connector.getLocalPort() + "/file2");
        response = _client.GET(uri);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), is("file 2 contents"));
    }
}
