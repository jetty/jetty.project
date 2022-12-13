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

package org.eclipse.jetty.server.handler;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SymlinkAllowedResourceAliasChecker;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.resource.PathResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentest4j.TestAbortedException;

import static java.time.Duration.ofSeconds;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

public class AllowSymLinkAliasCheckerTest
{
    public static Stream<Arguments> params()
    {
        List<Arguments> data = new ArrayList<>();

        String[] dirs = {
            "/workDir/", "/testdirlnk/", "/testdirprefixlnk/", "/testdirsuffixlnk/",
            "/testdirwraplnk/"
        };

        for (String dirname : dirs)
        {
            data.add(Arguments.of(dirname, 200, "text/html", "Directory: " + dirname));
            data.add(Arguments.of(dirname + "testfile.txt", 200, "text/plain", "Hello TestFile"));
            data.add(Arguments.of(dirname + "testfilelnk.txt", 200, "text/plain", "Hello TestFile"));
            data.add(Arguments.of(dirname + "testfileprefixlnk.txt", 200, "text/plain", "Hello TestFile"));
        }

        return data.stream();
    }

    private Server server;
    private LocalConnector localConnector;
    private Path rootPath;

    @BeforeEach
    public void setup() throws Exception
    {
        setupRoot();
        setupServer();
    }

    @AfterEach
    public void teardown() throws Exception
    {
        if (server != null)
        {
            server.stop();
        }
    }

    private void setupRoot() throws IOException
    {
        rootPath = MavenTestingUtils.getTargetTestingPath(AllowSymLinkAliasCheckerTest.class.getSimpleName());
        FS.ensureEmpty(rootPath);

        Path testdir = rootPath.resolve("workDir");
        FS.ensureDirExists(testdir);

        try
        {
            // If we used workDir (Path) from above, these symlinks
            // would point to an absolute path.

            // Create a relative symlink testdirlnk -> workDir
            Files.createSymbolicLink(rootPath.resolve("testdirlnk"), new File("workDir").toPath());
            // Create a relative symlink testdirprefixlnk -> ./workDir
            Files.createSymbolicLink(rootPath.resolve("testdirprefixlnk"), new File("./workDir").toPath());
            // Create a relative symlink testdirsuffixlnk -> workDir/
            Files.createSymbolicLink(rootPath.resolve("testdirsuffixlnk"), new File("workDir/").toPath());
            // Create a relative symlink testdirwraplnk -> ./workDir/
            Files.createSymbolicLink(rootPath.resolve("testdirwraplnk"), new File("./workDir/").toPath());
        }
        catch (UnsupportedOperationException | FileSystemException e)
        {
            // If unable to create symlink, no point testing the rest.
            // This is the path that Microsoft Windows takes.
            abortNotSupported(e);
        }

        Path testfileTxt = testdir.resolve("testfile.txt");
        Files.createFile(testfileTxt);
        try (OutputStream out = Files.newOutputStream(testfileTxt))
        {
            out.write("Hello TestFile".getBytes(StandardCharsets.UTF_8));
        }

        try
        {
            Path testfileTxtLnk = testdir.resolve("testfilelnk.txt");
            // Create a relative symlink testfilelnk.txt -> testfile.txt
            // If we used testfileTxt (Path) from above, this symlink
            // would point to an absolute path.
            Files.createSymbolicLink(testfileTxtLnk, new File("testfile.txt").toPath());
        }
        catch (UnsupportedOperationException | FileSystemException e)
        {
            // If unable to create symlink, no point testing the rest.
            // This is the path that Microsoft Windows takes.
            abortNotSupported(e);
        }

        try
        {
            Path testfilePrefixTxtLnk = testdir.resolve("testfileprefixlnk.txt");
            // Create a relative symlink testfileprefixlnk.txt -> ./testfile.txt
            // If we used testfileTxt (Path) from above, this symlink
            // would point to an absolute path.
            Files.createSymbolicLink(testfilePrefixTxtLnk, new File("./testfile.txt").toPath());
        }
        catch (UnsupportedOperationException | FileSystemException e)
        {
            // If unable to create symlink, no point testing the rest.
            // This is the path that Microsoft Windows takes.
            abortNotSupported(e);
        }
    }

    private void abortNotSupported(Throwable t)
    {
        if (t == null)
            return;
        throw new TestAbortedException("Unsupported Behavior", t);
    }

    private void setupServer() throws Exception
    {
        // Setup server
        server = new Server();
        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        ResourceHandler fileResourceHandler = new ResourceHandler();
        fileResourceHandler.setDirectoriesListed(true);
        fileResourceHandler.setWelcomeFiles(new String[]{"index.html"});
        fileResourceHandler.setEtags(true);

        ContextHandler fileResourceContext = new ContextHandler();
        fileResourceContext.setContextPath("/");
        fileResourceContext.setAllowNullPathInfo(true);
        fileResourceContext.setHandler(fileResourceHandler);
        fileResourceContext.setBaseResource(new PathResource(rootPath));

        fileResourceContext.clearAliasChecks();
        fileResourceContext.addAliasCheck(new SymlinkAllowedResourceAliasChecker(fileResourceContext));

        server.setHandler(fileResourceContext);
        server.start();
    }

    @ParameterizedTest
    @MethodSource("params")
    public void testAccess(String requestURI, int expectedResponseStatus, String expectedResponseContentType, String expectedResponseContentContains) throws Exception
    {
        HttpTester.Request request = HttpTester.newRequest();

        request.setMethod("GET");
        request.setHeader("Host", "tester");
        request.setURI(requestURI);

        assertTimeoutPreemptively(ofSeconds(5), () ->
        {
            String responseString = localConnector.getResponse(BufferUtil.toString(request.generate()));
            assertThat("Response status code", responseString, startsWith("HTTP/1.1 " + expectedResponseStatus + " "));
            assertThat("Response Content-Type", responseString, containsString("\nContent-Type: " + expectedResponseContentType));
            assertThat("Response", responseString, containsString(expectedResponseContentContains));
        });
    }
}
