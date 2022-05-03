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
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.ee9.servlet.DefaultServlet;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.AllowedResourceAliasChecker;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SymlinkAllowedResourceAliasChecker;
import org.eclipse.jetty.server.handler.AllowSymLinkAliasChecker;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.IO;
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

    private static Path _symlinkFile;
    private static Path _symlinkExternalFile;
    private static Path _symlinkDir;
    private static Path _symlinkParentDir;
    private static Path _symlinkSiblingDir;
    private static Path _webInfSymlink;
    private static Path _webrootSymlink;

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
        _symlinkFile = webRootPath.resolve("symlinkFile");
        delete(_symlinkFile);
        Files.createSymbolicLink(_symlinkFile, fileInWebroot).toFile().deleteOnExit();

        // Create symlink file that targets outside the webroot directory.
        _symlinkExternalFile = webRootPath.resolve("symlinkExternalFile");
        delete(_symlinkExternalFile);
        Files.createSymbolicLink(_symlinkExternalFile, getResource("file")).toFile().deleteOnExit();

        // Symlink to a directory inside of the webroot.
        _symlinkDir = webRootPath.resolve("symlinkDir");
        delete(_symlinkDir);
        Files.createSymbolicLink(_symlinkDir, webRootPath.resolve("documents")).toFile().deleteOnExit();

        // Symlink to a directory parent of the webroot.
        _symlinkParentDir = webRootPath.resolve("symlinkParentDir");
        delete(_symlinkParentDir);
        Files.createSymbolicLink(_symlinkParentDir, webRootPath.resolve("..")).toFile().deleteOnExit();

        // Symlink to a directory outside of the webroot.
        _symlinkSiblingDir = webRootPath.resolve("symlinkSiblingDir");
        delete(_symlinkSiblingDir);
        Files.createSymbolicLink(_symlinkSiblingDir, webRootPath.resolve("../sibling")).toFile().deleteOnExit();

        // Symlink to the WEB-INF directory.
        _webInfSymlink = webRootPath.resolve("webInfSymlink");
        delete(_webInfSymlink);
        Files.createSymbolicLink(_webInfSymlink, webRootPath.resolve("WEB-INF")).toFile().deleteOnExit();

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
        _context.setResourceBase(webRootPath);
        _context.setWelcomeFiles(new String[]{"index.html"});
        _context.setProtectedTargets(new String[]{"/WEB-INF", "/META-INF"});
        _context.getMimeTypes().addMimeMapping("txt", "text/plain;charset=utf-8");
        _server.setHandler(_context);
        _context.addServlet(DefaultServlet.class, "/");
        _context.clearAliasChecks();
        _server.start();

        _client = new HttpClient();
        _client.start();
    }

    @AfterAll
    public static void afterAll() throws Exception
    {
        // Try to delete all files now so that the symlinks do not confuse other tests.
        Files.delete(_symlinkFile);
        Files.delete(_symlinkExternalFile);
        Files.delete(_symlinkDir);
        Files.delete(_symlinkParentDir);
        Files.delete(_symlinkSiblingDir);
        Files.delete(_webInfSymlink);
        Files.delete(_webrootSymlink);

        _client.stop();
        _server.stop();
    }

    public static Stream<Arguments> testCases()
    {
        AllowedResourceAliasChecker allowedResource = new AllowedResourceAliasChecker(_context);
        SymlinkAllowedResourceAliasChecker symlinkAllowedResource = new SymlinkAllowedResourceAliasChecker(_context);
        AllowSymLinkAliasChecker allowSymlinks = new AllowSymLinkAliasChecker();
        ContextHandler.ApproveAliases approveAliases = new ContextHandler.ApproveAliases();

        return Stream.of(
                // AllowedResourceAliasChecker that checks the target of symlinks.
                Arguments.of(allowedResource, "/symlinkFile", HttpStatus.OK_200, "This file is inside webroot."),
                Arguments.of(allowedResource, "/symlinkExternalFile", HttpStatus.NOT_FOUND_404, null),
                Arguments.of(allowedResource, "/symlinkDir/file", HttpStatus.OK_200, "This file is inside webroot/documents."),
                Arguments.of(allowedResource, "/symlinkParentDir/webroot/file", HttpStatus.OK_200, "This file is inside webroot."),
                Arguments.of(allowedResource, "/symlinkParentDir/webroot/WEB-INF/web.xml", HttpStatus.NOT_FOUND_404, null),
                Arguments.of(allowedResource, "/symlinkSiblingDir/file", HttpStatus.NOT_FOUND_404, null),
                Arguments.of(allowedResource, "/webInfSymlink/web.xml", HttpStatus.NOT_FOUND_404, null),

                // SymlinkAllowedResourceAliasChecker that does not check the target of symlinks, but only approves files obtained through a symlink.
                Arguments.of(symlinkAllowedResource, "/symlinkFile", HttpStatus.OK_200, "This file is inside webroot."),
                Arguments.of(symlinkAllowedResource, "/symlinkExternalFile", HttpStatus.OK_200, "This file is outside webroot."),
                Arguments.of(symlinkAllowedResource, "/symlinkDir/file", HttpStatus.OK_200, "This file is inside webroot/documents."),
                Arguments.of(symlinkAllowedResource, "/symlinkParentDir/webroot/file", HttpStatus.OK_200, "This file is inside webroot."),
                Arguments.of(symlinkAllowedResource, "/symlinkParentDir/webroot/WEB-INF/web.xml", HttpStatus.OK_200, "This is the web.xml file."),
                Arguments.of(symlinkAllowedResource, "/symlinkSiblingDir/file", HttpStatus.OK_200, "This file is inside a sibling dir to webroot."),
                Arguments.of(symlinkAllowedResource, "/webInfSymlink/web.xml", HttpStatus.OK_200, "This is the web.xml file."),

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
