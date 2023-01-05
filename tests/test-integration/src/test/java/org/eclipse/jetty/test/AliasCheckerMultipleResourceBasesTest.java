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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class AliasCheckerMultipleResourceBasesTest
{
    private Server _server;
    private ServerConnector _connector;
    private HttpClient _client;
    private ContextHandler _context;
    private Path _webRootPath;
    private Path _altDir1Symlink;
    private Path _altDir2Symlink;

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
        _context = new ContextHandler();

        _context.setContextPath("/");
        _context.setBaseResourceAsPath(_webRootPath);
        _server.setHandler(_context);
        _context.clearAliasChecks();

        _client = new HttpClient();
        _client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        Files.delete(_altDir1Symlink);
        Files.delete(_altDir2Symlink);

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
        Handler.Collection collection = new Handler.Collection();
        collection.addHandler(newResourceHandler(_altDir1Symlink));
        collection.addHandler(newResourceHandler(_altDir2Symlink));
        _context.setHandler(collection);
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
            new SymlinkAllowedResourceAliasChecker(_context, toResource(_altDir1Symlink)),
            new SymlinkAllowedResourceAliasChecker(_context, toResource(_altDir2Symlink))
        );

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
