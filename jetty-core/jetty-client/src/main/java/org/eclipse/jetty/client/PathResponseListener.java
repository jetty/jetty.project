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

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.Response.CompleteListener;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * All classes should have a javadoc
 */
public class PathResponseListener implements CompleteListener, Response.ContentListener
{
    private static final Logger LOG = LoggerFactory.getLogger(InputStreamResponseListener.class);
    
    private CompletableFuture<Path> completable = new CompletableFuture<>();
    private final AutoLock.WithCondition lock = new AutoLock.WithCondition();
    private final CountDownLatch responseLatch = new CountDownLatch(1);
    private Path path;
    private Response response;
    private Throwable failure;
    private int bytesWritten;
    private FileOutputStream fileOut;
    private FileLock fileLock;
    
    public PathResponseListener(Path path) throws FileNotFoundException, IOException
    {   
        if (!path.isAbsolute())
        {
            throw new FileNotFoundException();
        }
        
        this.path = path;
        
        try 
        {
            fileOut = new FileOutputStream(this.path.toFile(), true);
            fileOut.getChannel().truncate(0);
            fileLock = fileOut.getChannel().lock();
        }
        catch (IOException e) 
        {
            throw e;
        }
    }

    @Override
    public void onContent(Response response, ByteBuffer content) throws IOException
    {
        if (response.getStatus() != 200)
        {
            throw new HttpResponseException(String.format("HTTP status code of this response %d", response.getStatus()), response);
        }
        
        if (!fileLock.isValid())
        {
            throw new IOException("File lock is not valid");
        }
        try
        {   
            bytesWritten += fileOut.getChannel().write(content);
        }
        catch (IOException e) 
        {
            throw e;
        }

    }

    @Override
    public void onComplete(Result result)
    {
        if (result.isFailed() && this.failure == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Result failure", failure);
        }
        
        this.response = result.getResponse();
    }
    
    public Response get(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException, ExecutionException 
    {
        boolean expired = !responseLatch.await(timeout, unit);
        if (expired && this.response == null)
            throw new TimeoutException();
        try (AutoLock ignored = lock.lock())
        {
            // If the request failed there is no response.
            if (response == null)
                throw new ExecutionException(failure);
            return response;
        }
    }
    
//    public Response get()
//    {
//        
//    }
//    
//    public CompletableFuture<Path> write(Request request, Path path)
//    {
//        
//    }
}
