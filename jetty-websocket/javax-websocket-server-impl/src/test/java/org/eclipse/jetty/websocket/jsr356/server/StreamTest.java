//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.server;

import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.is;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCode;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.ContainerProvider;
import javax.websocket.EndpointConfig;
import javax.websocket.HandshakeResponse;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.test.LeakTrackingBufferPoolRule;
import org.eclipse.jetty.websocket.common.util.Sha1Sum;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

public class StreamTest
{
    private static final Logger LOG = Log.getLogger(StreamTest.class);

    @Rule
    public LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule("Test");

    private static File outputDir;
    private static Server server;
    private static URI serverUri;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);

        ServerContainer wsContainer = WebSocketServerContainerInitializer.configureContext(context);

        // Prepare Server Side Output directory for uploaded files
        outputDir = MavenTestingUtils.getTargetTestingDir(StreamTest.class.getName());
        FS.ensureEmpty(outputDir);

        // Create Server Endpoint with output directory configuration
        ServerEndpointConfig config = ServerEndpointConfig.Builder.create(UploadSocket.class,"/upload/{filename}")
                .configurator(new ServerUploadConfigurator(outputDir)).build();
        wsContainer.addEndpoint(config);

        server.start();

        String host = connector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = connector.getLocalPort();
        serverUri = new URI(String.format("ws://%s:%d/",host,port));
        if (LOG.isDebugEnabled())
            LOG.debug("Server started on {}",serverUri);
    }

    @AfterClass
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
        upload("largest.jpg");
    }

    private void upload(String filename) throws Exception
    {
        File inputFile = MavenTestingUtils.getTestResourceFile("data/" + filename);

        WebSocketContainer client = ContainerProvider.getWebSocketContainer();
        ClientSocket socket = new ClientSocket();
        URI uri = serverUri.resolve("/upload/" + filename);
        client.connectToServer(socket,uri);
        socket.uploadFile(inputFile);
        socket.awaitClose();

        File sha1File = MavenTestingUtils.getTestResourceFile("data/" + filename + ".sha");
        assertFileUpload(new File(outputDir,filename),sha1File);
    }

    /**
     * Verify that the file sha1sum matches the previously calculated sha1sum
     * 
     * @param file
     *            the file to validate
     * @param shaFile
     *            the sha1sum file to verify against
     */
    private void assertFileUpload(File file, File sha1File) throws IOException, NoSuchAlgorithmException
    {
        Assert.assertThat("Path should exist: " + file,file.exists(),is(true));
        Assert.assertThat("Path should not be a directory:" + file,file.isDirectory(),is(false));

        String expectedSha1 = Sha1Sum.loadSha1(sha1File);
        String actualSha1 = Sha1Sum.calculate(file);

        Assert.assertThat("SHA1Sum of content: " + file,expectedSha1,equalToIgnoringCase(actualSha1));
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
            Assert.assertThat("Wait for ClientSocket close success",closeLatch.await(5,TimeUnit.SECONDS),is(true));
        }

        @OnError
        public void onError(Throwable t)
        {
            t.printStackTrace(System.err);
        }

        public void uploadFile(File inputFile) throws IOException
        {
            try (OutputStream out = session.getBasicRemote().getSendStream(); FileInputStream in = new FileInputStream(inputFile))
            {
                IO.copy(in,out);
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
            sec.getUserProperties().put(OUTPUT_DIR,this.outputDir);
            super.modifyHandshake(sec,request,response);
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
            File outputFile = new File(outputDir,filename);
            CloseCode closeCode = CloseCodes.NORMAL_CLOSURE;
            String closeReason = "";
            try (FileOutputStream out = new FileOutputStream(outputFile))
            {
                IO.copy(stream,out);
                if (outputFile.exists())
                {
                    closeReason = String.format("Received %,d bytes",outputFile.length());
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
                session.close(new CloseReason(closeCode,closeReason));
            }
        }

        @OnError
        public void onError(Throwable t)
        {
            t.printStackTrace(System.err);
        }
    }
}
