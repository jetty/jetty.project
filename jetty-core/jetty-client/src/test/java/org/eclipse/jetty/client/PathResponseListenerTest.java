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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PathResponseListenerTest
{
    private Server server;
    private ServerConnector connector;
    private Path resourceDir;

    @BeforeEach
    public void startServer() throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceDir = MavenTestingUtils.getTargetTestingPath(PathResponseListenerTest.class.getSimpleName());
        FS.ensureEmpty(resourceDir);
        resourceHandler.setBaseResource(ResourceFactory.of(server).newResource(resourceDir));
        resourceHandler.setDirAllowed(false);
        connector = new ServerConnector(server);
        server.addConnector(connector);
        server.setHandler(resourceHandler);
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        FS.ensureEmpty(resourceDir);
        LifeCycle.stop(server);
    }

    private Path createServerFile(int length) throws IOException
    {
        Path path = Files.createTempFile(resourceDir, "file-", ".bin");
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.WRITE))
        {
            ByteBuffer buffer = ByteBuffer.allocate(length);
            ThreadLocalRandom.current().nextBytes(buffer.array());
            channel.write(buffer);
        }
        return path;
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1024, 1048576})
    public void testFileDownload(int length) throws Exception
    {
        try (HttpClient client = new HttpClient())
        {
            client.start();

            Path serverPath = createServerFile(length);
            URI uri = URI.create("http://localhost:" + connector.getLocalPort() + "/" + serverPath.getFileName());

            Path clientPath = Files.createTempFile(resourceDir, "saved-", ".bin");
            PathResponseListener listener = new PathResponseListener(clientPath, true);
            client.newRequest(uri).send(listener);
            var pathResponse = listener.get(5, TimeUnit.SECONDS);

            assertTrue(Files.exists(pathResponse.path()));
            assertArrayEquals(Files.readAllBytes(serverPath), Files.readAllBytes(clientPath));

            // Do it again with overwrite=false.
            Files.delete(clientPath);
            listener = new PathResponseListener(clientPath, false);
            client.newRequest(uri).send(listener);
            pathResponse = listener.get(5, TimeUnit.SECONDS);

            assertTrue(Files.exists(pathResponse.path()));
            assertArrayEquals(Files.readAllBytes(serverPath), Files.readAllBytes(clientPath));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1024, 1048576})
    public void testFileDownloadWithCompletable(int length) throws Exception
    {
        try (HttpClient client = new HttpClient())
        {
            client.start();

            Path serverPath = createServerFile(length);
            URI uri = URI.create("http://localhost:" + connector.getLocalPort() + "/" + serverPath.getFileName());

            Path clientPath = Files.createTempFile(resourceDir, "saved-", ".bin");
            var pathResponse = PathResponseListener.write(client.newRequest(uri), clientPath, true)
                .get(5, TimeUnit.SECONDS);

            assertTrue(Files.exists(pathResponse.path()));
            assertArrayEquals(Files.readAllBytes(serverPath), Files.readAllBytes(clientPath));
        }
    }
}
