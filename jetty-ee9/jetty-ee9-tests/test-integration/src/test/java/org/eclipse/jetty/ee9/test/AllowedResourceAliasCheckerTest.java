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

package org.eclipse.jetty.ee9.test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.ee9.servlet.DefaultServlet;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.AllowedResourceAliasChecker;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AllowedResourceAliasCheckerTest
{
    private static Server _server;
    private static ServerConnector _connector;
    private static HttpClient _client;
    private static ServletContextHandler _context;
    private static File _baseDir;

    private static Path getResourceDir() throws Exception
    {
        URL url = AllowedResourceAliasCheckerTest.class.getClassLoader().getResource(".");
        assertNotNull(url);
        return new File(url.toURI()).toPath();
    }

    public void start() throws Exception
    {
        _server.start();
        _client.start();
    }

    @BeforeAll
    public static void beforeAll() throws Exception
    {
        _client = new HttpClient();
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        _context = new ServletContextHandler();
        _context.setContextPath("/");
        _context.addServlet(DefaultServlet.class, "/");
        _server.setHandler(_context);

        _baseDir = getResourceDir().resolve("baseDir").toFile();
        _baseDir.deleteOnExit();
        assertFalse(_baseDir.exists());
        _context.setResourceBase(_baseDir.getAbsolutePath());
    }

    @AfterAll
    public static void afterAll() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @AfterEach
    public void afterEach()
    {
        IO.delete(_baseDir);
    }

    public void createBaseDir() throws IOException
    {
        assertFalse(_baseDir.exists());
        assertTrue(_baseDir.mkdir());

        // Create a file in the baseDir.
        File file = _baseDir.toPath().resolve("file.txt").toFile();
        file.deleteOnExit();
        assertTrue(file.createNewFile());
        try (FileWriter fileWriter = new FileWriter(file))
        {
            fileWriter.write("this is a file in the baseDir");
        }

        // Create a symlink to that file.
        // Symlink to a directory inside of the webroot.
        File symlink = _baseDir.toPath().resolve("symlink").toFile();
        symlink.deleteOnExit();
        Files.createSymbolicLink(symlink.toPath(), file.toPath());
        assertTrue(symlink.exists());

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