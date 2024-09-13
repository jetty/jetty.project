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

import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Random;

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

public class PathResponseListenerTest
{
    private Server server;
    private ServerConnector connector;
    private HttpClient client;
    private Path zeroFile;
    private Path smallFile;
    private Path largeFile;
    
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
    
    private void createTestFiles() throws Exception
    {   
        zeroFile = Files.createFile(Path.of(System.getProperty("user.dir"), "zero"));
        smallFile = Files.createFile(Path.of(System.getProperty("user.dir"), "small"));
        largeFile = Files.createFile(Path.of(System.getProperty("user.dir"), "large"));
        
        try (FileOutputStream zeroFileWriter = new FileOutputStream(zeroFile.toFile());
            FileOutputStream smallFileWriter = new FileOutputStream(smallFile.toFile());
            FileOutputStream largeFileWriter = new FileOutputStream(largeFile.toFile());
            )
        {    
            zeroFileWriter.write(ByteBuffer.allocate(0).array());
            
            Random random = new Random();
            for (int i = 0; i < 1048576; i++) 
            {
                smallFileWriter.write(random.nextInt());
            }
        } 
        catch (Exception e) 
        {
            throw e;
        }
    }
    
    private void deleteTestFiles() throws Exception
    {
        try
        {                
            Files.delete(Optional.ofNullable(zeroFile)));
            Files.delete(smallFile);
            Files.delete(largeFile);
        }
        catch (Exception e)
        {
            throw e;
        }
    }

    @BeforeEach
    public void startServer() throws Exception
    {   
        configureServer();
        createTestFiles();
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
        deleteTestFiles();
    }

    @Test
    public void testClientConnection() throws Exception
    {
        client = new HttpClient(new HttpClientTransportOverHTTP(1));
        client.start();

        String host = "localhost";
        int port = connector.getLocalPort();
        String path = "/favicon.ico";
        Request request = client.newRequest("http://" + host + ":" + port + path);
        Response response = request.send();
        assertEquals(404, response.getStatus());
    }
    
    @Test
    public void testZeroFileDownload() throws Exception
    {
        client = new HttpClient(new HttpClientTransportOverHTTP(1));
        client.start();
        
        String host = "localhost";
        int port = connector.getLocalPort();
        String path = "/zero";
        Request request = client.newRequest("http://" + host + ":" + port + path);
        Response response = request.send();
        assertEquals(200, response.getStatus());
    }
    
    @Test
    public void testSmalFileDownload() throws Exception
    {
        client = new HttpClient(new HttpClientTransportOverHTTP(1));
        client.start();
        
        String host = "localhost";
        int port = connector.getLocalPort();
        String path = "/small";
        Request request = client.newRequest("http://" + host + ":" + port + path);
        Response response = request.send();
        assertEquals(200, response.getStatus());
    }
    
    @Test
    public void testLargeFileDownload() throws Exception
    {
        client = new HttpClient(new HttpClientTransportOverHTTP(1));
        client.start();
        
        String host = "localhost";
        int port = connector.getLocalPort();
        String path = "/large";
        Request request = client.newRequest("http://" + host + ":" + port + path);
        Response response = request.send();
        assertEquals(200, response.getStatus());
    }
}
