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
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.AllowedResourceAliasChecker;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AllowSymLinkAliasChecker;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.PathResource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class AliasCheckerSymlinkTest
{
    private static Server _server;
    private static ServerConnector _connector;
    private static HttpClient _client;
    private static ServletContextHandler _context;

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
        Path fileInWebroot = webRootPath.resolve("file");

        // Create symlink file that targets inside the webroot directory.
        Path symlinkFile = webRootPath.resolve("symlinkFile");
        delete(symlinkFile);
        Files.createSymbolicLink(symlinkFile, fileInWebroot).toFile().deleteOnExit();

        // Create symlink file that targets outside the webroot directory.
        Path symlinkExternalFile = webRootPath.resolve("symlinkExternalFile");
        delete(symlinkExternalFile);
        Files.createSymbolicLink(symlinkExternalFile, getResource("file")).toFile().deleteOnExit();

        // Symlink to a directory inside of the webroot.
        Path symlinkDir = webRootPath.resolve("symlinkDir");
        delete(symlinkDir);
        Files.createSymbolicLink(symlinkDir, webRootPath.resolve("documents")).toFile().deleteOnExit();

        // Symlink to a directory parent of the webroot.
        Path symlinkParentDir = webRootPath.resolve("symlinkParentDir");
        delete(symlinkParentDir);
        Files.createSymbolicLink(symlinkParentDir, webRootPath.resolve("..")).toFile().deleteOnExit();

        // Symlink to a directory outside of the webroot.
        Path symlinkSiblingDir = webRootPath.resolve("symlinkSiblingDir");
        delete(symlinkSiblingDir);
        Files.createSymbolicLink(symlinkSiblingDir, webRootPath.resolve("../sibling")).toFile().deleteOnExit();

        // Symlink to the WEB-INF directory.
        Path webInfSymlink = webRootPath.resolve("webInfSymlink");
        delete(webInfSymlink);
        Files.createSymbolicLink(webInfSymlink, webRootPath.resolve("WEB-INF")).toFile().deleteOnExit();

        // Create and start Server and Client.
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);
        _context = new ServletContextHandler();
        _context.setContextPath("/");
        _context.setBaseResource(new PathResource(webRootPath));
        _context.setWelcomeFiles(new String[]{"index.html"});
        _context.setProtectedTargets(new String[]{"/web-inf", "/meta-inf"});
        _context.getMimeTypes().addMimeMapping("txt", "text/plain;charset=utf-8");
        _server.setHandler(_context);
        _context.addServlet(DefaultServlet.class, "/");
        _server.start();

        _client = new HttpClient();
        _client.start();
    }

    @AfterAll
    public static void afterAll() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    public static Stream<Arguments> testCases()
    {
        AllowedResourceAliasChecker allowedResource = new AllowedResourceAliasChecker(_context);
        AllowedResourceAliasChecker allowedResourceSymlinks = new AllowedResourceAliasChecker(_context, true);
        AllowSymLinkAliasChecker allowSymlinks = new AllowSymLinkAliasChecker();
        ContextHandler.ApproveAliases approveAliases = new ContextHandler.ApproveAliases();

        return Stream.of(
                // AllowedResourceAliasChecker that does not check the target of symlinks.
                Arguments.of(allowedResource, "/symlinkFile", HttpStatus.OK_200, "This file is inside webroot."),
                Arguments.of(allowedResource, "/symlinkExternalFile", HttpStatus.OK_200, "This file is outside webroot."),
                Arguments.of(allowedResource, "/symlinkDir/file", HttpStatus.OK_200, "This file is inside webroot/documents."),
                Arguments.of(allowedResource, "/symlinkParentDir/webroot/file", HttpStatus.OK_200, "This file is inside webroot."),
                Arguments.of(allowedResource, "/symlinkParentDir/webroot/WEB-INF/web.xml", HttpStatus.OK_200, "This is the web.xml file."),
                Arguments.of(allowedResource, "/symlinkSiblingDir/file", HttpStatus.OK_200, "This file is inside a sibling dir to webroot."),
                Arguments.of(allowedResource, "/webInfSymlink/web.xml", HttpStatus.OK_200, "This is the web.xml file."),

                // AllowedResourceAliasChecker that checks the target of symlinks.
                Arguments.of(allowedResourceSymlinks, "/symlinkFile", HttpStatus.OK_200, "This file is inside webroot."),
                Arguments.of(allowedResourceSymlinks, "/symlinkExternalFile", HttpStatus.NOT_FOUND_404, null),
                Arguments.of(allowedResourceSymlinks, "/symlinkDir/file", HttpStatus.OK_200, "This file is inside webroot/documents."),
                Arguments.of(allowedResourceSymlinks, "/symlinkParentDir/webroot/file", HttpStatus.OK_200, "This file is inside webroot."),
                Arguments.of(allowedResourceSymlinks, "/symlinkParentDir/webroot/WEB-INF/web.xml", HttpStatus.NOT_FOUND_404, null),
                Arguments.of(allowedResourceSymlinks, "/symlinkSiblingDir/file", HttpStatus.NOT_FOUND_404, null),
                Arguments.of(allowedResourceSymlinks, "/webInfSymlink/web.xml", HttpStatus.NOT_FOUND_404, null),

                // The AllowSymLinkAliasChecker.
                Arguments.of(allowSymlinks, "/symlinkFile", HttpStatus.OK_200, "This file is inside webroot."),
                Arguments.of(allowSymlinks, "/symlinkExternalFile", HttpStatus.OK_200, "This file is outside webroot."),
                Arguments.of(allowSymlinks, "/symlinkDir/file", HttpStatus.OK_200, "This file is inside webroot/documents."),
                Arguments.of(allowSymlinks, "/symlinkParentDir/webroot/file", HttpStatus.OK_200, "This file is inside webroot."),
                Arguments.of(allowSymlinks, "/symlinkParentDir/webroot/WEB-INF/web.xml", HttpStatus.OK_200, "This is the web.xml file."),
                Arguments.of(allowSymlinks, "/symlinkSiblingDir/file", HttpStatus.OK_200, "This file is inside a sibling dir to webroot."),
                Arguments.of(allowSymlinks, "/webInfSymlink/web.xml", HttpStatus.OK_200, "This is the web.xml file."),

                // The ApproveAliases (approves everything regardless).
                Arguments.of(approveAliases, "/symlinkFile", HttpStatus.OK_200, "This file is inside webroot."),
                Arguments.of(approveAliases, "/symlinkExternalFile", HttpStatus.OK_200, "This file is outside webroot."),
                Arguments.of(approveAliases, "/symlinkDir/file", HttpStatus.OK_200, "This file is inside webroot/documents."),
                Arguments.of(approveAliases, "/symlinkParentDir/webroot/file", HttpStatus.OK_200, "This file is inside webroot."),
                Arguments.of(approveAliases, "/symlinkParentDir/webroot/WEB-INF/web.xml", HttpStatus.OK_200, "This is the web.xml file."),
                Arguments.of(approveAliases, "/symlinkSiblingDir/file", HttpStatus.OK_200, "This file is inside a sibling dir to webroot."),
                Arguments.of(approveAliases, "/webInfSymlink/web.xml", HttpStatus.OK_200, "This is the web.xml file."),

                // No alias checker (any symlink should be an alias).
                Arguments.of(null, "/symlinkFile", HttpStatus.NOT_FOUND_404, null),
                Arguments.of(null, "/symlinkExternalFile", HttpStatus.NOT_FOUND_404, null),
                Arguments.of(null, "/symlinkDir/file", HttpStatus.NOT_FOUND_404, null),
                Arguments.of(null, "/symlinkParentDir/webroot/file", HttpStatus.NOT_FOUND_404, null),
                Arguments.of(null, "/symlinkParentDir/webroot/WEB-INF/web.xml", HttpStatus.NOT_FOUND_404, null),
                Arguments.of(null, "/symlinkSiblingDir/file", HttpStatus.NOT_FOUND_404, null),
                Arguments.of(null, "/webInfSymlink/web.xml", HttpStatus.NOT_FOUND_404, null)
        );
    }

    @ParameterizedTest
    @MethodSource("testCases")
    public void test(ContextHandler.AliasCheck aliasChecker, String path, int httpStatus, String responseContent) throws Exception
    {
        setAliasChecker(aliasChecker);
        URI uri = URI.create("http://localhost:" + _connector.getLocalPort() + path);
        ContentResponse response = _client.GET(uri);
        assertThat(response.getStatus(), is(httpStatus));
        if (responseContent != null)
            assertThat(response.getContentAsString(), is(responseContent));
    }
}
