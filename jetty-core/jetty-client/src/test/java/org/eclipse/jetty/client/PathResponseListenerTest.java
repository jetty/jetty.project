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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PathResponseListenerTest
{
    private Server server;
    private ServerConnector connector;
    private HttpClient client;
    
    private static final Path ORIGIN_ZERO_FILE = Path.of(System.getProperty("user.dir"), "origin_zero");
    private static final Path ORIGIN_SMALL_FILE = Path.of(System.getProperty("user.dir"), "origin_small");
    private static final Path ORIGIN_LARGE_FILE = Path.of(System.getProperty("user.dir"), "origin_large");
    
    private static final Path RESPONSE_ZERO_FILE = Path.of(System.getProperty("user.dir"), "response_zero");
    private static final Path RESPONSE_SMALL_FILE = Path.of(System.getProperty("user.dir"), "response_small");
    private static final Path RESPONSE_LARGE_FILE = Path.of(System.getProperty("user.dir"), "response_large");
    
    private void configureServer() throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setBaseResource(ResourceFactory.of(resourceHandler).newResource(System.getProperty("user.dir")));
        resourceHandler.setDirAllowed(false);
        connector = new ServerConnector(server);
        server.addConnector(connector);
        server.setHandler(resourceHandler);
    }
    
    private void createZeroFile() throws IOException
    {   
        try (OutputStream zeroFileWriter = Files.newOutputStream(ORIGIN_ZERO_FILE, StandardOpenOption.CREATE_NEW))
        {    
            zeroFileWriter.write(ByteBuffer.allocate(0).array());
        } 
        catch (IOException e) 
        {
            throw e;
        }
    }
    
    private void createSmallFile() throws IOException
    {   
        try (OutputStream smallFileWriter = Files.newOutputStream(ORIGIN_SMALL_FILE, StandardOpenOption.CREATE_NEW))
        {    
            Random random = new Random();
            for (int i = 0; i < 1048576; i++) 
            {
                smallFileWriter.write(random.nextInt());
            }
        } 
        catch (IOException e) 
        {
            throw e;
        }
    }
    
    private void createLargeFile() throws IOException
    {   
        try (OutputStream largeFileWriter = Files.newOutputStream(ORIGIN_LARGE_FILE, StandardOpenOption.CREATE_NEW))
        {    
            Random random = new Random();
            for (int i = 0; i < 1048576; i++) 
            {
                largeFileWriter.write(random.nextInt());
            }
        } 
        catch (IOException e) 
        {
            throw e;
        }
    }
    
    @BeforeEach
    public void startServer() throws Exception
    {   
        Files.deleteIfExists(ORIGIN_ZERO_FILE);
        Files.deleteIfExists(ORIGIN_SMALL_FILE);
        Files.deleteIfExists(ORIGIN_LARGE_FILE);
        Files.deleteIfExists(RESPONSE_ZERO_FILE);
        Files.deleteIfExists(RESPONSE_SMALL_FILE);
        Files.deleteIfExists(RESPONSE_LARGE_FILE);
        
        createZeroFile();
        createSmallFile();
        createLargeFile();
        
        configureServer();
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
        
        Files.deleteIfExists(ORIGIN_ZERO_FILE);
        Files.deleteIfExists(ORIGIN_SMALL_FILE);
        Files.deleteIfExists(ORIGIN_LARGE_FILE);
        Files.deleteIfExists(RESPONSE_ZERO_FILE);
        Files.deleteIfExists(RESPONSE_SMALL_FILE);
        Files.deleteIfExists(RESPONSE_LARGE_FILE);
    }

    @Test
    public void testClientConnection() throws Exception
    {
        try (HttpClient client = new HttpClient(new HttpClientTransportOverHTTP(1)))
        {
            client.start();

            URL url = new URL("http", "localhost", connector.getLocalPort(), "/favicon.ico");
            Request request = client.newRequest(url.toURI().toString());
            Response response = request.send();
            assertEquals(404, response.getStatus());
        }
        
    }
    
    @Test
    public void testZeroFileDownload() throws Exception
    {   
        try (HttpClient client = new HttpClient(new HttpClientTransportOverHTTP(1)))
        {   
            client.start();
            
            URL url = new URL("http", "localhost", connector.getLocalPort(), "/" + ORIGIN_SMALL_FILE.getFileName().toString());
            
            PathResponseListener listener = new PathResponseListener(RESPONSE_ZERO_FILE);
            Request request = client.newRequest(url.toURI().toString());
            request.send(listener);
            Response response = listener.get(5, TimeUnit.SECONDS);
            assertEquals(200, response.getStatus());

            MessageDigest originFileChcksm = MessageDigest.getInstance("SHA-256");
            MessageDigest responseFileChcksm = MessageDigest.getInstance("SHA-256");
            
//            try (InputStream responseContent = listener.getInputStream();
//                 InputStream originFile = Files.newInputStream(Path.of(System.getProperty("user.dir"), "zero"), StandardOpenOption.READ)
//                )
//            {   
//                originFileChcksm.update(originFile.readAllBytes());
//                responseFileChcksm.update(responseContent.readAllBytes());
//                
//                assertTrue(MessageDigest.isEqual(originFileChcksm.digest(), responseFileChcksm.digest()));
//            }
            
            
        } 
        catch (Exception e) 
        {
            throw e;
        }
    }
    
    @Test
    public void testSmalFileDownload() throws Exception
    {   
        try (HttpClient client = new HttpClient(new HttpClientTransportOverHTTP(1));)
        {   
            client.start();
            
            URL url = new URL("http", "localhost", connector.getLocalPort(), "/" + ORIGIN_SMALL_FILE.getFileName().toString());
            
            InputStreamResponseListener listener = new InputStreamResponseListener();
            Request request = client.newRequest(url.toURI().toString());
            request.send(listener);
            Response response = listener.get(10, TimeUnit.SECONDS);
            assertEquals(200, response.getStatus());
            
            MessageDigest originFileChcksm = MessageDigest.getInstance("SHA-256");
            MessageDigest responseFileChcksm = MessageDigest.getInstance("SHA-256");
            
            try (InputStream responseContent = listener.getInputStream();
                 InputStream originFile = Files.newInputStream(ORIGIN_SMALL_FILE, StandardOpenOption.READ)
                )
            {   
                originFileChcksm.update(originFile.readAllBytes());
                responseFileChcksm.update(responseContent.readAllBytes());
                
                assertTrue(MessageDigest.isEqual(originFileChcksm.digest(), responseFileChcksm.digest()));
            }
        } 
        catch (Exception e) 
        {
            throw e;
        }
    }
    
    @Test
    public void testLargeFileDownload() throws Exception
    {   
        try (HttpClient client = new HttpClient(new HttpClientTransportOverHTTP(1));)
        {
            client.start();
            
            //URL url = new URL("http", "localhost", connector.getLocalPort(), "/" + ORIGIN_LARGE_FILE.getFileName().toString());
            
            PathResponseListener listener = new PathResponseListener(RESPONSE_LARGE_FILE);
            Request request = client.newRequest("http://" + "localhost" + ":" + connector.getLocalPort() + "/" + ORIGIN_LARGE_FILE.getFileName().toString());
            request.send(listener);
            Response response = listener.get(5, TimeUnit.SECONDS);
            assertEquals(200, response.getStatus());
            
            MessageDigest originFileChcksm = MessageDigest.getInstance("SHA-256");
            MessageDigest responseFileChcksm = MessageDigest.getInstance("SHA-256");
            
            try (InputStream responseFile = Files.newInputStream(RESPONSE_LARGE_FILE, StandardOpenOption.READ);
                 InputStream originFile = Files.newInputStream(ORIGIN_LARGE_FILE, StandardOpenOption.READ)
                )
            {   
                originFileChcksm.update(originFile.readAllBytes());
                responseFileChcksm.update(responseFile.readAllBytes());
                
                assertTrue(MessageDigest.isEqual(originFileChcksm.digest(), responseFileChcksm.digest()));
            }
        } 
        catch (Exception e) 
        {
            throw e;
        }
    }
}
