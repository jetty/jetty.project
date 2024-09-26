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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.client.Response.Listener;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link Response.ContentListener} that produces an {@link Path}
 * that allows applications to save a file from a response {@link Response}
 * like {@code curl <url> -o file.bin} does.
 * <p>
 * Typical usage is:
 * <pre>{@code httpClient.newRequest(host, port)
 * .send(new PathResponseListener(Path.of("/tmp/file.bin"));
 * 
 *  var request = httpClient.newRequest(host, port);
 *  CompletableFuture<Path> completable = PathResponseListener.write(request, Path.of("/tmp/file.bin"), rewriteExistingFile);
 *  }</pre>
 */
public class PathResponseListener extends CompletableFuture<Path> implements Listener
{
    private static final Logger LOG = LoggerFactory.getLogger(InputStreamResponseListener.class);

    private final Path path;
    private final FileChannel fileChannel;

    public PathResponseListener(Path path, boolean overwrite) throws IOException
    {
        this.path = path;

        // Throws the exception if file can't be overwritten 
        // otherwise truncate it.
        if (Files.exists(path) && !overwrite)
        {
            throw new FileAlreadyExistsException("File can't be overwritten");
        }

        fileChannel = FileChannel.open(this.path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Override
    public void onHeaders(Response response)
    {
        if (response.getStatus() != HttpStatus.OK_200)
        {
            response.abort(new HttpResponseException(String.format("HTTP status code of response %d", response.getStatus()), response));
        }
    }

    @Override
    public void onContent(Response response, ByteBuffer content)
    {
        try
        {
            var bytesWritten = fileChannel.write(content);
            if (LOG.isDebugEnabled())
                LOG.debug("%d bytes written", bytesWritten);
        }
        catch (IOException e)
        {
            response.abort(e);
        }
    }

    @Override
    public void onComplete(Result result)
    {
        try
        {
            if (result.isFailed())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Result failure", result.getFailure());
                completeExceptionally(result.getFailure());
                return;
            }

            this.complete(this.path);
        }
        finally
        {
            try
            {
                fileChannel.close();
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }

    }

    /**
     * Writes a file into {@link Path}.
     * 
     * @param request to a server
     * @param path to write a file
     * @param overwrite true overwrites a file, otherwise fails
     * @return {@code CompletableFuture<Path>}
     */
    public static CompletableFuture<Path> write(Request request, Path path, boolean overwrite)
    {
        PathResponseListener l = null;
        try
        {
            l = new PathResponseListener(path, overwrite);
            request.send(l);
        }
        catch (Throwable e)
        {
            l.completeExceptionally(e);
        }
        return l;
    }
}
