//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.AllowedResourceAliasChecker;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.PathResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class AllowedResourceAliasCheckerTest
{
    private Server _server;
    private ServerConnector _connector;
    private HttpClient _client;
    private ServletContextHandler _context;
    private Path _baseDir;

    public void start() throws Exception
    {
        _server.start();
        _client.start();
    }

    @BeforeEach
    public void prepare(WorkDir workDir)
    {
        _client = new HttpClient();
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        _context = new ServletContextHandler();
        _context.setContextPath("/");
        _context.addServlet(DefaultServlet.class, "/");
        _server.setHandler(_context);

        _baseDir = workDir.getEmptyPathDir().resolve("baseDir");
        _context.setBaseResource(new PathResource(_baseDir));
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(_client);
        LifeCycle.stop(_server);
    }

    public void createBaseDir() throws IOException
    {
        FS.ensureDirExists(_baseDir);

        // Create a file in the baseDir.
        Path file = _baseDir.resolve("file.txt");
        try (OutputStream outputStream = Files.newOutputStream(file))
        {
            outputStream.write("this is a file in the baseDir".getBytes(StandardCharsets.UTF_8));
        }

        boolean symlinkSupported;
        try
        {
            // Create a symlink to that file.
            // Symlink to a directory inside the webroot.
            Path symlink = _baseDir.resolve("symlink");
            Files.createSymbolicLink(symlink, file);
            symlinkSupported = true;
        }
        catch (UnsupportedOperationException | FileSystemException e)
        {
            symlinkSupported = false;
        }

        assumeTrue(symlinkSupported, "Symlink not supported");
    }

    @Test
    public void testCreateBaseDirBeforeStart() throws Exception
    {
        _context.clearAliasChecks();
        _context.addAliasCheck(new AllowedResourceAliasChecker(_context));
        createBaseDir();
        start();
        assertThat(_context.getAliasChecks().size(), equalTo(1));

        URI uri = URI.create("http://localhost:" + _connector.getLocalPort() + "/symlink");
        ContentResponse response = _client.GET(uri);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), is("this is a file in the baseDir"));
    }

    @Test
    public void testCreateBaseDirAfterStart() throws Exception
    {
        _context.clearAliasChecks();
        _context.addAliasCheck(new AllowedResourceAliasChecker(_context));
        start();
        createBaseDir();
        assertThat(_context.getAliasChecks().size(), equalTo(1));

        URI uri = URI.create("http://localhost:" + _connector.getLocalPort() + "/symlink");
        ContentResponse response = _client.GET(uri);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), is("this is a file in the baseDir"));
    }
}
