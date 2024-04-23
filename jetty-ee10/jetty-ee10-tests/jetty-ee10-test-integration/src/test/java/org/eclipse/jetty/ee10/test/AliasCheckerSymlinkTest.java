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

package org.eclipse.jetty.ee10.test;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.ee10.servlet.DefaultServlet;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.AliasCheck;
import org.eclipse.jetty.server.AllowedResourceAliasChecker;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SymlinkAllowedResourceAliasChecker;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.HotSwapHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class AliasCheckerSymlinkTest
{
    private static Server _server;
    private static ServerConnector _connector;
    private static HttpClient _client;
    private static HotSwapHandler _hotSwapHandler;
    private static ServletContextHandler _context1;
    private static ServletContextHandler _context2;

    private static final List<Path> _createdFiles = new ArrayList<>();

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

    private static void setAliasChecker(ContextHandler contextHandler, AliasCheck aliasChecker) throws Exception
    {
        _hotSwapHandler.setHandler(contextHandler);
        contextHandler.clearAliasChecks();
        if (aliasChecker != null)
            contextHandler.addAliasCheck(aliasChecker);
    }

    private static void createSymbolicLink(Path symlinkFile, Path target) throws IOException
    {
        delete(symlinkFile);
        _createdFiles.add(symlinkFile);
        Files.createSymbolicLink(symlinkFile, target).toFile().deleteOnExit();
    }

    @BeforeAll
    public static void beforeAll() throws Exception
    {
        Path webRootPath = getResource("webroot");
        Path combinedPath = getResource("combined");

        // Create symlink file that targets inside the webroot directory.
        createSymbolicLink(
            webRootPath.resolve("symlinkFile"),
            webRootPath.resolve("file"));

        // Create symlink file that targets outside the webroot directory.
        createSymbolicLink(
            webRootPath.resolve("symlinkExternalFile"),
            getResource("file"));

        // Symlink to a directory inside the webroot.
        createSymbolicLink(
            webRootPath.resolve("symlinkDir"),
            webRootPath.resolve("documents"));

        // Symlink to a directory parent of the webroot.
        createSymbolicLink(
            webRootPath.resolve("symlinkParentDir"),
            webRootPath.resolve(".."));

        // Symlink to a directory outside the webroot.
        createSymbolicLink(
            webRootPath.resolve("symlinkSiblingDir"),
            webRootPath.resolve("../sibling"));

        // Symlink to the WEB-INF directory.
        createSymbolicLink(
            webRootPath.resolve("webInfSymlink"),
            webRootPath.resolve("WEB-INF"));

        // Symlink file from the combined resource dir to the webroot.
        createSymbolicLink(
            combinedPath.resolve("combinedSymlinkFile"),
            webRootPath.resolve("file"));

        // Symlink file from the combined resource dir to the webroot WEB-INF.
        createSymbolicLink(
            combinedPath.resolve("combinedWebInfSymlink"),
            webRootPath.resolve("WEB-INF"));

        // Symlink file from the combined resource dir to outside the webroot.
        createSymbolicLink(
            combinedPath.resolve("externalCombinedSymlinkFile"),
            webRootPath.resolve("../sibling"));


        // Create and start Server and Client.
        _server = new Server();
        _server.setDynamic(true);
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);
        _hotSwapHandler = new HotSwapHandler();
        _server.setHandler(_hotSwapHandler);

        // Standard tests.
        _context1 = new ServletContextHandler();
        _context1.setContextPath("/");
        _context1.setBaseResourceAsPath(webRootPath);
        _context1.setWelcomeFiles(new String[]{"index.html"});
        _context1.setProtectedTargets(new String[]{"/WEB-INF", "/META-INF"});
        _context1.getMimeTypes().addMimeMapping("txt", "text/plain;charset=utf-8");
        _context1.addServlet(DefaultServlet.class, "/");
        _context1.clearAliasChecks();

        // CombinedResource tests.
        ResourceFactory resourceFactory = ResourceFactory.of(_server);
        Resource resource = ResourceFactory.combine(
            resourceFactory.newResource(webRootPath),
            resourceFactory.newResource(getResource("combined")));
        _context2 = new ServletContextHandler();
        _context2.setContextPath("/");
        _context2.setBaseResource(resource);
        _context2.setWelcomeFiles(new String[]{"index.html"});
        _context2.setProtectedTargets(new String[]{"/WEB-INF", "/META-INF"});
        _context2.getMimeTypes().addMimeMapping("txt", "text/plain;charset=utf-8");
        _context2.addServlet(DefaultServlet.class, "/");
        _context2.clearAliasChecks();

        _server.start();
        _client = new HttpClient();
        _client.start();
    }

    @AfterAll
    public static void afterAll() throws Exception
    {
        // Try to delete all files now so that the symlinks do not confuse other tests.
        for (Path p : _createdFiles)
        {
            try
            {
                Files.delete(p);
            }
            catch (Throwable t)
            {
                // Ignored.
            }
        }
        _createdFiles.clear();

        _client.stop();
        _server.stop();
    }

    private static class ApproveAliases implements AliasCheck
    {
        @Override
        public boolean checkAlias(String pathInContext, Resource resource)
        {
            return true;
        }
    }

    public static Stream<Arguments> testCases()
    {
        return testCases(_context1);
    }

    public static Stream<Arguments> testCases(ContextHandler context)
    {
        AllowedResourceAliasChecker allowedResource = new AllowedResourceAliasChecker(context);
        SymlinkAllowedResourceAliasChecker symlinkAllowedResource = new SymlinkAllowedResourceAliasChecker(context);
        ApproveAliases approveAliases = new ApproveAliases();

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
                Arguments.of(null, "/webInfSymlink/web.xml", HttpStatus.NOT_FOUND_404, null),

                // We should only be able to list contents of a symlinked directory if the alias checker is installed.
                Arguments.of(null, "/symlinkDir", HttpStatus.NOT_FOUND_404, null),
                Arguments.of(allowedResource, "/symlinkDir", HttpStatus.OK_200, null)
            );
    }

    public static Stream<Arguments> combinedResourceTestCases()
    {
        AllowedResourceAliasChecker allowedResource = new AllowedResourceAliasChecker(_context2);
        SymlinkAllowedResourceAliasChecker symlinkAllowedResource = new SymlinkAllowedResourceAliasChecker(_context2);

        Stream<Arguments> combinedResourceTests = Stream.of(
            Arguments.of(allowedResource, "/file", HttpStatus.OK_200, "This file is inside webroot."),
            Arguments.of(allowedResource, "/combinedFile", HttpStatus.OK_200, "This is a file in the combined resource dir."),
            Arguments.of(allowedResource, "/WEB-INF/file", HttpStatus.NOT_FOUND_404, null),
            Arguments.of(allowedResource, "/files", HttpStatus.OK_200, "Directory: /files/|/files/file1|/files/file2"),
            Arguments.of(allowedResource, "/files/file1", HttpStatus.OK_200, "file1 from combined dir"),
            Arguments.of(allowedResource, "/files/file2", HttpStatus.OK_200, "file1 from webroot"),

            Arguments.of(allowedResource, "/combinedSymlinkFile", HttpStatus.OK_200, "This file is inside webroot."),
            Arguments.of(allowedResource, "/externalCombinedSymlinkFile/file", HttpStatus.NOT_FOUND_404, null),
            Arguments.of(allowedResource, "/combinedWebInfSymlink/web.xml", HttpStatus.NOT_FOUND_404, null),

            Arguments.of(symlinkAllowedResource, "/combinedSymlinkFile", HttpStatus.OK_200, "This file is inside webroot."),
            Arguments.of(symlinkAllowedResource, "/externalCombinedSymlinkFile/file", HttpStatus.OK_200, "This file is inside a sibling dir to webroot."),
            Arguments.of(symlinkAllowedResource, "/combinedWebInfSymlink/web.xml", HttpStatus.OK_200, "This is the web.xml file.")
        );
        return Stream.concat(testCases(_context2), combinedResourceTests);
    }

    @ParameterizedTest
    @MethodSource("testCases")
    public void test(AliasCheck aliasChecker, String path, int httpStatus, String responseContent) throws Exception
    {
        setAliasChecker(_context1, aliasChecker);
        URI uri = URI.create("http://localhost:" + _connector.getLocalPort() + path);
        ContentResponse response = _client.GET(uri);
        assertThat(response.getStatus(), is(httpStatus));
        if (responseContent != null)
            assertThat(response.getContentAsString(), is(responseContent));
    }

    @ParameterizedTest
    @MethodSource("combinedResourceTestCases")
    public void testCombinedResource(AliasCheck aliasChecker, String path, int httpStatus, String responseContent) throws Exception
    {
        setAliasChecker(_context2, aliasChecker);
        URI uri = URI.create("http://localhost:" + _connector.getLocalPort() + path);
        ContentResponse response = _client.GET(uri);
        assertThat(response.getStatus(), is(httpStatus));

        if (responseContent != null)
        {
            if (responseContent.contains("|"))
            {
                for (String s : responseContent.split("\\|"))
                {
                    assertThat("Could not find " + s, response.getContentAsString(), containsString(s));
                }
            }
            else
            {
                assertThat(response.getContentAsString(), equalTo(responseContent));
            }
        }
    }
}
