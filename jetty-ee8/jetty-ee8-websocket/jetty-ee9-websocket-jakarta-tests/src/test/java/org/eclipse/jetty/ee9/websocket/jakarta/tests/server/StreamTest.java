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

package org.eclipse.jetty.ee9.websocket.jakarta.tests.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.CloseReason.CloseCode;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.ee9.websocket.jakarta.client.JakartaWebSocketClientContainerProvider;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.LocalServer;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.Sha1Sum;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTimeout;

public class StreamTest
{
    private static final Logger LOG = LoggerFactory.getLogger(StreamTest.class);

    private static File outputDir;
    private static LocalServer server;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new LocalServer();
        server.start();
        ServerContainer container = server.getServerContainer();

        // Prepare Server Side Output directory for uploaded files
        outputDir = MavenTestingUtils.getTargetTestingDir(StreamTest.class.getName());
        FS.ensureEmpty(outputDir);

        // Create Server Endpoint with output directory configuration
        ServerEndpointConfig config = ServerEndpointConfig.Builder.create(UploadSocket.class, "/upload/{filename}")
            .configurator(new ServerUploadConfigurator(outputDir)).build();
        container.addEndpoint(config);
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testUploadSmall() throws Exception
    {
        upload("small.png");
    }

    @Test
    public void testUploadMedium() throws Exception
    {
        upload("medium.png");
    }

    @Test
    public void testUploadLarger() throws Exception
    {
        upload("larger.png");
    }

    @Test
    public void testUploadLargest() throws Exception
    {
        assertTimeout(Duration.ofMillis(60000), () -> upload("largest.jpg"));
    }

    private void upload(String filename) throws Exception
    {
        File inputFile = MavenTestingUtils.getTestResourceFile("data/" + filename);

        WebSocketContainer client = ContainerProvider.getWebSocketContainer();
        try
        {
            ClientSocket socket = new ClientSocket();
            URI uri = server.getWsUri().resolve("/upload/" + filename);
            client.connectToServer(socket, uri);
            socket.uploadFile(inputFile);
            socket.awaitClose();

            File sha1File = MavenTestingUtils.getTestResourceFile("data/" + filename + ".sha");
            assertFileUpload(new File(outputDir, filename), sha1File);
        }
        finally
        {
            JakartaWebSocketClientContainerProvider.stop(client);
        }
    }

    /**
     * Verify that the file sha1sum matches the previously calculated sha1sum
     *
     * @param file the file to validate
     * @param sha1File the sha1sum file to verify against
     */
    private void assertFileUpload(File file, File sha1File) throws IOException, NoSuchAlgorithmException
    {
        assertThat("Path should exist: " + file, file.exists(), is(true));
        assertThat("Path should not be a directory:" + file, file.isDirectory(), is(false));

        String expectedSha1 = Sha1Sum.loadSha1(sha1File);
        String actualSha1 = Sha1Sum.calculate(file);

        assertThat("SHA1Sum of content: " + file, actualSha1, equalToIgnoringCase(expectedSha1));
    }

    @ClientEndpoint
    public static class ClientSocket
    {
        private Session session;
        private CountDownLatch closeLatch = new CountDownLatch(1);

        @OnOpen
        public void onOpen(Session session)
        {
            this.session = session;
        }

        public void close() throws IOException
        {
            this.session.close();
        }

        @OnClose
        public void onClose(CloseReason close)
        {
            closeLatch.countDown();
        }

        public void awaitClose() throws InterruptedException
        {
            assertThat("Wait for ClientSocket close success", closeLatch.await(5, TimeUnit.SECONDS), is(true));
        }

        @OnError
        public void onError(Throwable t)
        {
            t.printStackTrace(System.err);
        }

        public void uploadFile(File inputFile) throws IOException
        {
            try (FileInputStream in = new FileInputStream(inputFile);
                 OutputStream out = session.getBasicRemote().getSendStream())
            {
                IO.copy(in, out);
            }
        }
    }

    public static class ServerUploadConfigurator extends ServerEndpointConfig.Configurator
    {
        public static final String OUTPUT_DIR = "outputDir";
        private final File outputDir;

        public ServerUploadConfigurator(File outputDir)
        {
            this.outputDir = outputDir;
        }

        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response)
        {
            sec.getUserProperties().put(OUTPUT_DIR, this.outputDir);
            super.modifyHandshake(sec, request, response);
        }
    }

    @ServerEndpoint("/upload/{filename}")
    public static class UploadSocket
    {
        private File outputDir;
        private Session session;

        @OnOpen
        public void onOpen(Session session, EndpointConfig config)
        {
            this.session = session;
            // not setting max message here, as streaming handling
            // should allow any sized message.
            this.outputDir = (File)config.getUserProperties().get(ServerUploadConfigurator.OUTPUT_DIR);
        }

        @OnMessage
        public void onMessage(InputStream stream, @PathParam("filename") String filename) throws IOException
        {
            File outputFile = new File(outputDir, filename);
            CloseCode closeCode = CloseCodes.NORMAL_CLOSURE;
            String closeReason = "";
            try (FileOutputStream out = new FileOutputStream(outputFile))
            {
                IO.copy(stream, out);
                if (outputFile.exists())
                {
                    closeReason = String.format("Received %,d bytes", outputFile.length());
                    if (LOG.isDebugEnabled())
                        LOG.debug(closeReason);
                }
                else
                {
                    LOG.warn("Uploaded file does not exist: " + outputFile);
                }
            }
            catch (IOException e)
            {
                e.printStackTrace(System.err);
                closeReason = "Error writing file";
                closeCode = CloseCodes.UNEXPECTED_CONDITION;
            }
            finally
            {
                session.close(new CloseReason(closeCode, closeReason));
            }
        }

        @OnError
        public void onError(Throwable t)
        {
            t.printStackTrace(System.err);
        }
    }
}
