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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpStatus;
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
    private MessageDigest origDigest;
    private MessageDigest respDigest;
    
    private static final Path ORIGIN_ZERO_FILE = Path.of(System.getProperty("user.dir"), "origin_zero");
    private static final Path ORIGIN_SMALL_FILE = Path.of(System.getProperty("user.dir"), "origin_small");
    private static final Path ORIGIN_LARGE_FILE = Path.of(System.getProperty("user.dir"), "origin_large");
    
    private static final Path RESPONSE_ZERO_FILE = Path.of(System.getProperty("user.dir"), "response_zero");
    private static final Path RESPONSE_SMALL_FILE = Path.of(System.getProperty("user.dir"), "response_small");
    private static final Path RESPONSE_LARGE_FILE = Path.of(System.getProperty("user.dir"), "response_large");
    
    private void configureTestEnvironment() throws Exception
    {   
        origDigest = MessageDigest.getInstance("MD5");
        respDigest = MessageDigest.getInstance("MD5");
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
    
    private void deleteFiles(Path...paths) 
    {
        for (Path p : paths)
        {
            try
            {
                Files.deleteIfExists(p);
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
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
            ByteBuffer buff = ByteBuffer.allocate(1024);
            Random random = new Random();
            int writeBytes = 0; 
            while (writeBytes < 1024)
            {
                random.nextBytes(buff.array());
                buff.flip();
                smallFileWriter.write(buff.array());
                buff.clear();
                writeBytes++;
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
            ByteBuffer buff = ByteBuffer.allocate(1048576);
            Random random = new Random();
            int writeBytes = 0; 
            while (writeBytes < 1024)
            {
                random.nextBytes(buff.array());
                buff.flip();
                largeFileWriter.write(buff.array());
                buff.clear();
                writeBytes++;
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
        configureTestEnvironment();
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
        
        // Reuse message digest
        origDigest.reset();
        respDigest.reset();
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
            assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
        }
        
    }
    
    @Test
    public void testZeroFileDownload() throws Exception
    {   
        try (HttpClient client = new HttpClient(new HttpClientTransportOverHTTP(1)))
        {   
            deleteFiles(ORIGIN_ZERO_FILE, RESPONSE_ZERO_FILE);
            createZeroFile();
            
            client.start();
            
            URL url = new URL("http", "localhost", connector.getLocalPort(), "/" + ORIGIN_ZERO_FILE.getFileName().toString());
            
            PathResponseListener listener = new PathResponseListener(RESPONSE_ZERO_FILE);
            Request request = client.newRequest(url.toURI().toString());
            request.send(listener);
            Response response = listener.get(5, TimeUnit.SECONDS);
            assertEquals(HttpStatus.OK_200, response.getStatus());
  
            File originFile = new File(ORIGIN_ZERO_FILE.toUri());
            File responseFile = new File(RESPONSE_ZERO_FILE.toUri());
            
            assertTrue(originFile.exists() && responseFile.exists());
            assertTrue(originFile.length() == 0 && responseFile.length() == 0);
        } 
        catch (Exception e) 
        {
            throw e;
        } 
        finally 
        {
            deleteFiles(ORIGIN_ZERO_FILE, RESPONSE_ZERO_FILE);
        }
    }
    
    @Test
    public void testSmallFileDownload() throws Exception
    {   
        try (HttpClient client = new HttpClient(new HttpClientTransportOverHTTP(1));)
        {   
            deleteFiles(ORIGIN_SMALL_FILE, RESPONSE_SMALL_FILE);
            createSmallFile();
            
            client.start();
            
            URL url = new URL("http", "localhost", connector.getLocalPort(), "/" + ORIGIN_SMALL_FILE.getFileName().toString());
            
            PathResponseListener listener = new PathResponseListener(RESPONSE_SMALL_FILE);
            Request request = client.newRequest(url.toURI().toString());
            request.send(listener);
            Response response = listener.get();
            assertEquals(HttpStatus.OK_200, response.getStatus());
            
            try (InputStream responseFile = Files.newInputStream(RESPONSE_SMALL_FILE, StandardOpenOption.READ);
                 InputStream originFile = Files.newInputStream(ORIGIN_SMALL_FILE, StandardOpenOption.READ)
                )
            {   
                origDigest.update(originFile.readAllBytes());
                respDigest.update(responseFile.readAllBytes());
                
                assertTrue(MessageDigest.isEqual(origDigest.digest(), respDigest.digest()));
            }
        } 
        catch (Exception e) 
        {
            throw e;
        }
        finally
        {
            deleteFiles(ORIGIN_SMALL_FILE, RESPONSE_SMALL_FILE);
        }
    }
    
    @Test
    public void testLargeFileDownload() throws Exception
    {   
        try (HttpClient client = new HttpClient(new HttpClientTransportOverHTTP(1));)
        {   
            deleteFiles(ORIGIN_LARGE_FILE, RESPONSE_LARGE_FILE);
            createLargeFile();
            
            client.start();
            
            URL url = new URL("http", "localhost", connector.getLocalPort(), "/" + ORIGIN_LARGE_FILE.getFileName().toString());
            
            PathResponseListener listener = new PathResponseListener(RESPONSE_LARGE_FILE);
            Request request = client.newRequest(url.toURI().toString());
            request.send(listener);
            Response response = listener.get(25, TimeUnit.SECONDS);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            
            try (InputStream responseFile = Files.newInputStream(RESPONSE_LARGE_FILE, StandardOpenOption.READ);
                 InputStream originFile = Files.newInputStream(ORIGIN_LARGE_FILE, StandardOpenOption.READ)
                )
            {   
                origDigest.update(originFile.readAllBytes());
                respDigest.update(responseFile.readAllBytes());
                
                assertTrue(MessageDigest.isEqual(origDigest.digest(), respDigest.digest()));
            }
        } 
        catch (Exception e) 
        {
            throw e;
        }
        finally
        {   
            deleteFiles(ORIGIN_LARGE_FILE, RESPONSE_LARGE_FILE);
        }
    }
    
    @Test
    public void testZeroFileDownloadCompletable() throws Exception
    {   
        try (HttpClient client = new HttpClient(new HttpClientTransportOverHTTP(1));)
        {   
            deleteFiles(ORIGIN_ZERO_FILE, RESPONSE_ZERO_FILE);
            createZeroFile();
            
            client.start();
            
            URL url = new URL("http", "localhost", connector.getLocalPort(), "/" + ORIGIN_ZERO_FILE.getFileName().toString());
            
            Request request = client.newRequest(url.toURI().toString());
            
            CompletableFuture<Path> completable = PathResponseListener.write(request, RESPONSE_ZERO_FILE);
            completable.thenAccept(path -> 
            {
                try (InputStream responseFile = Files.newInputStream(path, StandardOpenOption.READ);
                    InputStream originFile = Files.newInputStream(ORIGIN_ZERO_FILE, StandardOpenOption.READ)
                   )
               {   
                   origDigest.update(originFile.readAllBytes());
                   respDigest.update(responseFile.readAllBytes());
                   
                   assertTrue(MessageDigest.isEqual(origDigest.digest(), respDigest.digest()));
               }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            });
            completable.get();
        } 
        catch (Exception e) 
        {
            throw e;
        }
        finally
        {
            deleteFiles(ORIGIN_ZERO_FILE, RESPONSE_ZERO_FILE);
        }
    }
    
    @Test
    public void testSmallFileDownloadCompletable() throws Exception
    {   
        try (HttpClient client = new HttpClient(new HttpClientTransportOverHTTP(1));)
        {   
            deleteFiles(ORIGIN_SMALL_FILE, RESPONSE_SMALL_FILE);
            createSmallFile();
            
            client.start();
            
            URL url = new URL("http", "localhost", connector.getLocalPort(), "/" + ORIGIN_SMALL_FILE.getFileName().toString());
            
            Request request = client.newRequest(url.toURI().toString());
            CompletableFuture<Path> completable = PathResponseListener.write(request, RESPONSE_SMALL_FILE);
//          
            completable.thenAccept(path -> 
            {
                try (InputStream responseFile = Files.newInputStream(path, StandardOpenOption.READ);
                    InputStream originFile = Files.newInputStream(ORIGIN_SMALL_FILE, StandardOpenOption.READ)
                   )
               {   
                   origDigest.update(originFile.readAllBytes());
                   respDigest.update(responseFile.readAllBytes());
                   
                   assertTrue(MessageDigest.isEqual(origDigest.digest(), respDigest.digest()));
               }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            });
            completable.get();
        } 
        catch (Exception e) 
        {
            throw e;
        }
        finally
        {
            deleteFiles(ORIGIN_SMALL_FILE, RESPONSE_SMALL_FILE);
        }
    }
    
    @Test
    public void testLargeFileDownloadCompletable() throws Exception
    {   
        try (HttpClient client = new HttpClient(new HttpClientTransportOverHTTP(1));)
        {   
            deleteFiles(ORIGIN_LARGE_FILE, RESPONSE_LARGE_FILE);
            createLargeFile();
            
            client.start();
            
            URL url = new URL("http", "localhost", connector.getLocalPort(), "/" + ORIGIN_LARGE_FILE.getFileName().toString());
            
            Request request = client.newRequest(url.toURI().toString());
            CompletableFuture<Path> completable = PathResponseListener.write(request, RESPONSE_LARGE_FILE);
//          
            completable.thenAccept(path -> 
            {
                try (InputStream responseFile = Files.newInputStream(path, StandardOpenOption.READ);
                    InputStream originFile = Files.newInputStream(ORIGIN_LARGE_FILE, StandardOpenOption.READ)
                   )
               {   
                   origDigest.update(originFile.readAllBytes());
                   respDigest.update(responseFile.readAllBytes());
                   
                   assertTrue(MessageDigest.isEqual(origDigest.digest(), respDigest.digest()));
               }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            });
            completable.get();
        } 
        catch (Exception e) 
        {
            throw e;
        }
        finally
        {
            deleteFiles(ORIGIN_LARGE_FILE, RESPONSE_LARGE_FILE);
        }
    }
}
