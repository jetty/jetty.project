//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.File;
import java.io.FileInputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.AllowedResourceAliasChecker;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.PathResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class AliasCheckerSymlinkTest
{
    private static String _fileContents;
    private static Path _webRootPath;
    private Server _server;
    private HttpClient _client;

    private static Path getResource(String path) throws Exception
    {
        URL url = AliasCheckerSymlinkTest.class.getClassLoader().getResource(path);
        assertNotNull(url);
        return new File(url.toURI()).toPath();
    }

    private static void delete(Path path)
    {
        IO.delete(path.toFile());
    }

    @BeforeAll
    public static void setup() throws Exception
    {
        _webRootPath = getResource("webroot");
        Path fileInWebroot = _webRootPath.resolve("file");
        _fileContents = IO.toString(new FileInputStream(fileInWebroot.toFile()));

        // Create symlink file that targets inside the webroot directory.
        Path symlinkFile = _webRootPath.resolve("symlinkFile");
        delete(symlinkFile);
        Files.createSymbolicLink(symlinkFile, fileInWebroot).toFile().deleteOnExit();

        // Create symlink file that targets outside the webroot directory.
        Path symlinkExternalFile = _webRootPath.resolve("symlinkExternalFile");
        delete(symlinkExternalFile);
        Files.createSymbolicLink(symlinkExternalFile, getResource("message.txt")).toFile().deleteOnExit();

        // Symlink to a directory inside of the webroot.
        Path simlinkDir = _webRootPath.resolve("simlinkDir");
        delete(simlinkDir);
        Files.createSymbolicLink(simlinkDir, _webRootPath.resolve("documents")).toFile().deleteOnExit();

        // Symlink to a directory parent of the webroot.
        Path symlinkParentDir = _webRootPath.resolve("symlinkParentDir");
        delete(symlinkParentDir);
        Files.createSymbolicLink(symlinkParentDir, _webRootPath.resolve("..")).toFile().deleteOnExit();

        // Symlink to a directory outside of the webroot.
        Path symlinkSiblingDir = _webRootPath.resolve("symlinkSiblingDir");
        delete(symlinkSiblingDir);
        Files.createSymbolicLink(symlinkSiblingDir, _webRootPath.resolve("../sibling")).toFile().deleteOnExit();

        // Symlink to the WEB-INF directory.
        Path webInfSymlink = _webRootPath.resolve("webInfSymlink");
        delete(webInfSymlink);
        Files.createSymbolicLink(webInfSymlink, _webRootPath.resolve("WEB-INF")).toFile().deleteOnExit();
    }

    @BeforeEach
    public void before() throws Exception
    {
        // TODO: don't use 8080 explicitly
        _server = new Server(8080);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.setBaseResource(new PathResource(_webRootPath));
        context.setWelcomeFiles(new String[]{"index.html"});
        context.setProtectedTargets(new String[]{"/web-inf", "/meta-inf"});
        context.getMimeTypes().addMimeMapping("txt", "text/plain;charset=utf-8");

        _server.setHandler(context);
        context.addServlet(DefaultServlet.class, "/");
        _server.start();

        _client = new HttpClient();
        _client.start();

        context.addAliasCheck(new AllowedResourceAliasChecker(context));
    }

    @AfterEach
    public void after() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    // todo : no alias checker, symlink alias checker, AllowedResourceAliasChecker (not following symlinks), AllowedResourceAliasChecker (following symlinks)
    @Test
    public void symlinkToInsideWebroot() throws Exception
    {
        ContentResponse response = _client.GET("http://localhost:8080/symlinkFile");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), is(_fileContents));
    }

    @Test
    public void symlinkToOutsideWebroot() throws Exception
    {
        ContentResponse response = _client.GET("http://localhost:8080/symlinkExternalFile");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), is(_fileContents));
    }

    @Test
    public void symlinkToDirectoryInsideWebroot() throws Exception
    {
        ContentResponse response = _client.GET("http://localhost:8080/simlinkDir/file");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), is(_fileContents));
    }

    @Test
    public void symlinkToParentDirectory() throws Exception
    {
        ContentResponse response = _client.GET("http://localhost:8080/symlinkParentDir/webroot/file");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), is(_fileContents));

        response = _client.GET("http://localhost:8080/symlinkParentDir/webroot/WEB-INF/web.xml");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), is("should not be able to access this file."));
    }

    @Test
    public void symlinkToSiblingDirectory() throws Exception
    {
        ContentResponse response = _client.GET("http://localhost:8080/symlinkSiblingDir/file");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), is(_fileContents));

        // TODO Test .. or %2e%2e up from symlinked directory "http://localhost:8080/symlinkExternalDir/%2e%2e/webroot/file"
//        ContentResponse response = _client.GET("http://localhost:8080/symlinkSiblingDir/%2e%2e/webroot/WEB-INF/web.xml");
//        assertThat(response.getStatus(), is(HttpStatus.OK_200));
//        assertThat(response.getContentAsString(), is("should not be able to access this file."));
    }

    @Test
    public void symlinkToProtectedDirectoryInsideWebroot() throws Exception
    {
        ContentResponse response = _client.GET("http://localhost:8080/webInfSymlink/web.xml");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), is("should not be able to access this file."));
    }
}
