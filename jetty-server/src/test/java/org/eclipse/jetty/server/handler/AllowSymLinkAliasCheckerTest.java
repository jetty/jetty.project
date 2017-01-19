//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.handler;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeNoException;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.resource.PathResource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class AllowSymLinkAliasCheckerTest
{
    @Parameterized.Parameters(name = "{0}")
    public static List<Object[]> params()
    {
        List<Object[]> data = new ArrayList<>();

        String dirs[] = {"/testdir/", "/testdirlnk/", "/testdirprefixlnk/", "/testdirsuffixlnk/",
                "/testdirwraplnk/"};

        for (String dirname : dirs)
        {
            data.add(new Object[]{dirname, 200, "text/html", "Directory: " + dirname});
            data.add(new Object[]{dirname + "testfile.txt", 200, "text/plain", "Hello TestFile"});
            data.add(new Object[]{dirname + "testfilelnk.txt", 200, "text/plain", "Hello TestFile"});
            data.add(new Object[]{dirname + "testfileprefixlnk.txt", 200, "text/plain", "Hello TestFile"});
        }

        return data;
    }

    private Server server;
    private LocalConnector localConnector;
    private Path rootPath;

    @Before
    public void setup() throws Exception
    {
        setupRoot();
        setupServer();
    }

    @After
    public void teardown() throws Exception
    {
        server.stop();
    }

    private void setupRoot() throws IOException
    {
        rootPath = MavenTestingUtils.getTargetTestingPath(AllowSymLinkAliasCheckerTest.class.getSimpleName());
        FS.ensureEmpty(rootPath);

        Path testdir = rootPath.resolve("testdir");
        FS.ensureDirExists(testdir);

        try
        {
            // If we used testdir (Path) from above, these symlinks
            // would point to an absolute path.

            // Create a relative symlink testdirlnk -> testdir
            Files.createSymbolicLink(rootPath.resolve("testdirlnk"), new File("testdir").toPath());
            // Create a relative symlink testdirprefixlnk -> ./testdir
            Files.createSymbolicLink(rootPath.resolve("testdirprefixlnk"), new File("./testdir").toPath());
            // Create a relative symlink testdirsuffixlnk -> testdir/
            Files.createSymbolicLink(rootPath.resolve("testdirsuffixlnk"), new File("testdir/").toPath());
            // Create a relative symlink testdirwraplnk -> ./testdir/
            Files.createSymbolicLink(rootPath.resolve("testdirwraplnk"), new File("./testdir/").toPath());
        }
        catch (UnsupportedOperationException | FileSystemException e)
        {
            // If unable to create symlink, no point testing the rest.
            // This is the path that Microsoft Windows takes.
            assumeNoException(e);
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
            assumeNoException(e);
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
            assumeNoException(e);
        }
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
        fileResourceContext.addAliasCheck(new AllowSymLinkAliasChecker());

        server.setHandler(fileResourceContext);
        server.start();
    }

    @Parameterized.Parameter(0)
    public String requestURI;
    @Parameterized.Parameter(1)
    public int expectedResponseStatus;
    @Parameterized.Parameter(2)
    public String expectedResponseContentType;
    @Parameterized.Parameter(3)
    public String expectedResponseContentContains;

    public AllowSymLinkAliasCheckerTest()
    {
    }

    @Test(timeout = 5000)
    public void testAccess() throws Exception
    {
        HttpTester.Request request = HttpTester.newRequest();

        request.setMethod("GET");
        request.setHeader("Host", "tester");
        request.setURI(requestURI);

        String responseString = localConnector.getResponse(BufferUtil.toString(request.generate()));
        assertThat("Response status code", responseString, startsWith("HTTP/1.1 " + expectedResponseStatus + " "));
        assertThat("Response Content-Type", responseString, containsString("\nContent-Type: " + expectedResponseContentType));
        assertThat("Response", responseString, containsString(expectedResponseContentContains));
    }
}
