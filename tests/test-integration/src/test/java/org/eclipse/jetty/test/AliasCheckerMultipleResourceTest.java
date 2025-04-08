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

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SymlinkAllowedResourceAliasChecker;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class AliasCheckerMultipleResourceTest extends AliasCheckerTestBase
{
    private final List<Path> _toDelete = new ArrayList<>();
    private Server _server;
    private ServerConnector _connector;
    private HttpClient _client;
    private ContextHandler _context;

    public void start(List<Path> baseResources, Consumer<Resource> configurator) throws Exception
    {
        Resource combinedResourceBase = ResourceFactory.combine(
            baseResources.stream()
            .map(AliasCheckerTestBase::toResource)
            .toList()
        );
        start(combinedResourceBase, configurator);
    }

    public void start(Path baseResource, Consumer<Resource> configurator) throws Exception
    {
        start(toResource(baseResource), configurator);
    }

    public void start(Resource baseResource, Consumer<Resource> configurator) throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);
        _context = new ContextHandler();

        _context.setContextPath("/");
        _context.setBaseResource(baseResource);
        _server.setHandler(_context);
        _context.clearAliasChecks();
        if (configurator != null)
            configurator.accept(baseResource);
        _server.start();

        _client = new HttpClient();
        _client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        for (Path path : _toDelete)
        {
            delete(path);
        }
        _toDelete.clear();

        if (_client != null)
            _client.stop();
        if (_server != null)
            _server.stop();
    }

    @Test
    public void testMultipleResourceBases() throws Exception
    {
        Path webRootPath = getResource("webroot");
        System.err.println(webRootPath);

        // Create a sibling to webroot containing a symlink to file1.
        Path baseResource = webRootPath.resolve("../altDir1").toAbsolutePath();
        delete(baseResource);
        _toDelete.add(baseResource);
        Files.createDirectory(baseResource);
        createSymbolicLink(baseResource.resolve("file1"), getResource("file1"));

        // Create another sibling to webroot containing a symlink to file2.
        Path baseResource2 = webRootPath.resolve("../altDir2").toAbsolutePath();
        delete(baseResource2);
        _toDelete.add(baseResource2);
        Files.createDirectory(baseResource2);
        createSymbolicLink(baseResource2.resolve("file2"), getResource("file2"));
        start(webRootPath, r ->
        {
            Handler.Sequence handlers = new Handler.Sequence();
            handlers.addHandler(newResourceHandler(baseResource));
            handlers.addHandler(newResourceHandler(baseResource2));
            _context.setHandler(handlers);
        });

        // With no alias checkers we cannot access file 1.
        URI uri = URI.create("http://localhost:" + _connector.getLocalPort() + "/file1");
        ContentResponse response = _client.GET(uri);
        assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));

        // With no alias checkers we cannot access file 2.
        uri = URI.create("http://localhost:" + _connector.getLocalPort() + "/file2");
        response = _client.GET(uri);
        assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));

        // Set alias checkers to allow content under these alternative resource bases.
        setAliasCheckers(_context,
            new SymlinkAllowedResourceAliasChecker(_context, toResource(baseResource)),
            new SymlinkAllowedResourceAliasChecker(_context, toResource(baseResource2))
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

    @Test
    public void testCombinedResources() throws Exception
    {
        List<Path> resources = new ArrayList<>();

        // First resource base will have a symlink to file1.
        Path baseResource = getResource("multipleResourceTest/webroot1");
        _toDelete.add(baseResource);
        Files.createDirectories(baseResource);
        createSymbolicLink(
            baseResource.resolve("foo/symlink/file1Symlink"),
            getResource("file1"));
        resources.add(baseResource);

        // This resource base contains a foo directory containing just file2.
        resources.add(getResource("multipleResourceTest/webroot2"));

        // This resource base contains just file3 and does not contain a directory named foo.
        resources.add(getResource("multipleResourceTest/webroot3"));

        start(resources, r -> _context.setHandler(newResourceHandler(r)));
        URI uri = URI.create("http://localhost:" + _connector.getLocalPort());

        // We cannot access the symlinked file1 without an alias checker.
        ContentResponse response = _client.GET(uri.resolve("/foo/symlink/file1Symlink"));
        assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));

        // After setting the alias checker we can access the symlinked file1.
        setAliasCheckers(_context, new SymlinkAllowedResourceAliasChecker(_context));
        response = _client.GET(uri.resolve("/foo/symlink/file1Symlink"));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), is("file 1 contents"));
    }
}
