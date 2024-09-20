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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import org.eclipse.jetty.client.Response.Listener;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link Response.ContentListener} that produces an {@link Path}
 * that allows applications to save a file from a response {@link Response}
 * like curl &lt;URL&gt; -o file.bin does.
 * <p>
 * Typical usage is:
 * <pre>
 *  httpClient.newRequest(host, port)
 * .send(new PathResponseListener(Path.of("/tmp/file.bin"));
 * 
 *  var request = httpClient.newRequest(host, port);
 *  CompletableFuture&gt;Path&gt; completable = PathResponseListener.write(request, Path.of("/tmp/file.bin"));
 * </pre>
 */
public class PathResponseListener extends CompletableFuture<Response> implements Listener
{
    private static final Logger LOG = LoggerFactory.getLogger(InputStreamResponseListener.class);
    
    private Path path;
    private Throwable failure;
    private FileChannel fileOut;
    private int bytesWrite;
    
    public PathResponseListener(Path path, boolean overwrite) throws FileNotFoundException, IOException, FileAlreadyExistsException
    {           
        this.path = path;
        
        // Throws the exception if file can't be overwritten 
        // otherwise truncate it.
        if (this.path.toFile().exists() && !overwrite)
        {
            throw new FileAlreadyExistsException("File can't be overwritten");
        }
        
        try
        {
            fileOut = FileChannel.open(this.path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        catch (IOException e) 
        {   
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to instantiate object", e);
            
            throw e;
        }
    }
    
    @Override
    public void onHeaders(Response response)
    {
        if (response.getStatus() != HttpStatus.OK_200)
        {
            this.cancel(true);
            throw new HttpResponseException(String.format("HTTP status code of this response %d", response.getStatus()), response);
        }
    }
    
    @Override
    public void onContent(Response response, ByteBuffer content)
    {
        try
        {
            this.bytesWrite += this._write(content).get();
            if (LOG.isDebugEnabled())
                LOG.debug("%d bytes written", bytesWrite);
        }
        catch (InterruptedException | ExecutionException e)
        {
            e.printStackTrace();
        }
    }

    @Override
    public void onComplete(Result result)
    {
        if (result.isFailed() && this.failure == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Result failure", failure);
            
            this.cancel(true);
            return;
        }
        
        this.complete(result.getResponse());        
    }
    
    public static CompletableFuture<Response> write(Request request, Path path)
    {
        
    }
    
    private CompletableFuture<Integer> _write(ByteBuffer content) {
        return CompletableFuture.supplyAsync(() -> {
            int bytesWritten = 0;
            try
            {   
                bytesWritten += fileOut.write(content);
            }
            catch (IOException e) 
            {   
                if (LOG.isDebugEnabled())
                    LOG.debug("Unable to write file", e);
                
                throw new CompletionException(e);
            }
            
            return bytesWritten;
        });
    }
}
