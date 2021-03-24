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

package org.eclipse.jetty.deploy;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.deploy.providers.WebAppProvider;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DeploymentTempDirTest
{
    private static final Logger LOG = Log.getLogger(DeploymentTempDirTest.class);

    private final WebAppProvider webAppProvider = new WebAppProvider();
    private final ContextHandlerCollection contexts = new ContextHandlerCollection();
    private final Path testDir = MavenTestingUtils.getTargetTestingPath(DeploymentTempDirTest.class.getSimpleName());
    private final Path tmpDir = testDir.resolve("tmpDir");
    private final Path webapps = testDir.resolve("webapps");
    private final Server server = new Server();

    @BeforeEach
    public void setup() throws Exception
    {
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        FS.ensureEmpty(testDir);
        FS.ensureEmpty(tmpDir);
        FS.ensureEmpty(webapps);

        webAppProvider.setMonitoredDirName(webapps.toString());
        webAppProvider.setScanInterval(0);
        DeploymentManager deploymentManager = new DeploymentManager();
        deploymentManager.addAppProvider(webAppProvider);
        server.addBean(deploymentManager);

        HandlerCollection handlerCollection = new HandlerCollection();
        handlerCollection.addHandler(contexts);
        handlerCollection.addHandler(new DefaultHandler());
        deploymentManager.setContexts(contexts);
        server.setHandler(handlerCollection);
    }

    @AfterEach
    public void stop() throws Exception
    {
        server.stop();
    }

    @Test
    public void testTmpDirectory() throws Exception
    {
        Path warPath = MavenTestingUtils.getTestResourcePath("webapps/foo-webapp-1.war");
        String deploymentXml = "<Configure class=\"org.eclipse.jetty.webapp.WebAppContext\">\n" +
            "<Set name=\"war\">" + warPath + "</Set>\n" +
            "<Set name=\"tempDirectory\">" + tmpDir + "</Set>\n" +
            "<Set name=\"persistTempDirectory\">false</Set>\n" +
            "</Configure>";

        server.start();

        // Add the webapp xml which will will be detected after scan.
        createNewFile(webapps, "foo-webapp.xml", deploymentXml);
        webAppProvider.scan();
        webAppProvider.scan();
        WebAppContext webAppContext = getWebAppContext();
        assertThat(webAppContext.getTempDirectory(), is(tmpDir.toFile()));

        // Add a known file to the temp directory, this file will be deleted when stopping as persistTempDirectory is false.
        String content = UUID.randomUUID().toString();
        createNewFile(webAppContext.getTempDirectory().toPath(), "myFile.txt", content);

        // Touch the webapp and rescan to reload the WebAppContext.
        long newModifiedTime = System.currentTimeMillis() + 1000;
        assertTrue(webapps.resolve("foo-webapp.xml").toFile().setLastModified(newModifiedTime));
        webAppProvider.scan();
        webAppProvider.scan();

        // The second WebAppContext should be using the same temp directory but the file will have been deleted.
        WebAppContext webAppContext2 = getWebAppContext();
        assertNotSame(webAppContext, webAppContext2);
        File tmpDir2 = webAppContext2.getTempDirectory();
        assertThat(tmpDir2, is(tmpDir.toFile()));

        // The temp directory has been cleared.
        assertTrue(tmpDir2.exists());
        assertThat(length(tmpDir2.listFiles()), is(0));
    }

    @Test
    public void testPersistentTmpDirectory() throws Exception
    {
        Path warPath = MavenTestingUtils.getTestResourcePath("webapps/foo-webapp-1.war");
        String deploymentXml = "<Configure class=\"org.eclipse.jetty.webapp.WebAppContext\">\n" +
            "<Set name=\"war\">" + warPath + "</Set>\n" +
            "<Set name=\"tempDirectory\">" + tmpDir + "</Set>\n" +
            "<Set name=\"persistTempDirectory\">true</Set>\n" +
            "</Configure>";

        server.start();

        // Add the webapp xml which will will be detected after scan.
        createNewFile(webapps, "foo-webapp.xml", deploymentXml);
        webAppProvider.scan();
        webAppProvider.scan();
        WebAppContext webAppContext1 = getWebAppContext();
        assertThat(webAppContext1.getTempDirectory(), is(tmpDir.toFile()));

        // Add a known file to the temp directory, this file will be preserved after stop as persistTempDirectory is true.
        String content = UUID.randomUUID().toString();
        createNewFile(webAppContext1.getTempDirectory().toPath(), "myFile.txt", content);

        // Touch the webapp and rescan to reload the WebAppContext.
        long newModifiedTime = System.currentTimeMillis() + 1000;
        assertTrue(webapps.resolve("foo-webapp.xml").toFile().setLastModified(newModifiedTime));
        webAppProvider.scan();
        webAppProvider.scan();

        // The second WebAppContext should be using the same temp directory and file will not have been deleted.
        WebAppContext webAppContext2 = getWebAppContext();
        assertNotSame(webAppContext1, webAppContext2);
        assertThat(webAppContext2.getTempDirectory(), is(tmpDir.toFile()));

        // Test file is still in the temp directory.
        String contentAfterReload = getContent(webAppContext2.getTempDirectory().toPath().resolve("myFile.txt"));
        assertThat(contentAfterReload, is(content));
    }

    public int length(File[] files)
    {
        if (files == null)
            throw new IllegalStateException();
        return files.length;
    }

    public WebAppContext getWebAppContext()
    {
        Handler[] handlers = contexts.getHandlers();
        assertThat(handlers.length, is(1));
        return Arrays.stream(contexts.getHandlers())
            .filter(h -> h instanceof WebAppContext)
            .map(h -> (WebAppContext)h)
            .findFirst()
            .orElseThrow(IllegalStateException::new);
    }

    public void createNewFile(Path directory, String filename, String content) throws IOException
    {
        File file = directory.resolve(filename).toFile();
        try (FileWriter writer = new FileWriter(file))
        {
            writer.write(content);
        }
    }

    public String getContent(Path filePath) throws IOException
    {
        return IO.toString(new FileReader(filePath.toFile()));
    }

    public static class WaitScannerListener implements Scanner.BulkListener
    {
        CompletableFuture<Void> future = new CompletableFuture<>();

        public void reset()
        {
            future = new CompletableFuture<>();
        }

        @Override
        public void filesChanged(List<String> filenames)
        {
            future.complete(null);
        }
    }
}
