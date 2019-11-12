//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ExtendWith(WorkDirExtension.class)
public class PersistentFileUploadDownloadTest
{
    public static final Logger LOG = Log.getLogger(PersistentFileUploadDownloadTest.class);
    public WorkDir workDir;
    
    private Server server;
    private HttpClient client;
    
    @BeforeEach
    public void startServerAndClient() throws Exception
    {
        QueuedThreadPool serverThreadPool = new QueuedThreadPool();
        serverThreadPool.setName("Server");
        server = new Server(serverThreadPool);
        
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        connector.addBean(new ChannelLogger());
        server.addConnector(connector);
        
        ServletContextHandler contextHandler = new ServletContextHandler();
        
        Path storageDir = workDir.getEmptyPathDir();
        contextHandler.setBaseResource(new PathResource(storageDir));
        
        UploadDownloadServlet uploadDownloadServlet = new UploadDownloadServlet(storageDir);
        ServletHolder uploadDownloadHolder = new ServletHolder(uploadDownloadServlet);
        contextHandler.addServlet(uploadDownloadHolder, "/");
        server.setHandler(contextHandler);
        
        server.start();
        
        QueuedThreadPool clientThreadPool = new QueuedThreadPool();
        clientThreadPool.setName("Client"); // so we can tell which threads belong to client
        client = new HttpClient();
        client.setExecutor(clientThreadPool);
        
        client.start();
    }
    
    @AfterEach
    public void stopServerAndClient()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }
    
    @Test
    public void testMultipleUploadDownload() throws Exception
    {
        Path workBase = workDir.getPath().resolve("upload-download");
        FS.ensureEmpty(workBase);
        Path uploadFile = workBase.resolve("upload-test-file.txt");
        Path downloadFile = workBase.resolve("download-test-file.txt");

        createTestFile(uploadFile, "test-file-content", 10_000_000);
        
        int iterations = 100;
        
        for (int i = 0; i < iterations; i++)
        {
            URI uri = server.getURI().resolve("/upload-filename?iter=" + i);

            LOG.info("---- XXXX Test Iteration {} -----", i);
            // Thread.sleep(1500);
            
            // Upload (PUT) File
            clientPUT(uploadFile, uri);
            
            // Download (GET) File
            clientGET(uri, downloadFile);
        }
    }
    
    private void clientGET(URI uri, Path localFile) throws InterruptedException, TimeoutException, ExecutionException, IOException
    {
        Files.deleteIfExists(localFile);
        
        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest(uri).send(listener);
        
        Response response = listener.get(Long.MAX_VALUE, TimeUnit.SECONDS);
        
        assertThat("GET Response status", response.getStatus(), is(200));
        try (InputStream inStream = listener.getInputStream())
        {
            Files.copy(inStream, localFile);
        }
    }
    
    private void clientPUT(Path uploadFile, URI uri) throws InterruptedException, TimeoutException, ExecutionException, IOException
    {
        ContentResponse response = client.newRequest(uri)
                .method(HttpMethod.PUT)
                .file(uploadFile, "text/plain")
                .send();
        
        assertThat("PUT Response status", response.getStatus(), is(204));
    }
    
    public static void createTestFile(Path file, String lineContent, long lineCount) throws IOException
    {
        try (BufferedWriter writer = Files.newBufferedWriter(file, UTF_8))
        {
            for (int i = 0; i < lineCount; i++)
            {
                writer.write("" + i + "\t" + lineContent + "\n");
            }
        }
    }
    
    public static class ChannelLogger implements HttpChannel.Listener
    {
        private static final Logger LOG = Log.getLogger(ChannelLogger.class);
        
        public void onDispatchFailure(Request request, Throwable failure)
        {
            dump("onDispatchFailure ", request, failure);
        }
        
        public void onRequestFailure(Request request, Throwable failure)
        {
            dump("onRequestFailure", request, failure);
        }
        
        public void onResponseFailure(Request request, Throwable failure)
        {
            dump("onResponseFailure ", request, failure);
        }
        
        private void dump(String method, Request request, Throwable failure)
        {
            HttpChannel channel = request.getHttpChannel();
            LOG.warn(method + " " + channel + " - " + request, failure);
        }
    }
    
    
    public static class UploadDownloadServlet extends DefaultServlet
    {
        private Path storageDir;
        
        public UploadDownloadServlet(Path storageDir)
        {
            this.storageDir = storageDir;
        }
        
        @Override
        protected void doPut(HttpServletRequest request, HttpServletResponse response)
                throws IOException
        {
            String path = request.getServletPath();
            while (path.startsWith("/"))
            {
                path = path.substring(1);
            }
            
            Path file = storageDir.resolve(path);
            
            // allow repeated tests
            Files.deleteIfExists(file);
            
            try (InputStream inputStream = request.getInputStream())
            {
                Files.copy(inputStream, file);
            }
            
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        }
    }
}
