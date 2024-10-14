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
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.client.Response.Listener;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.IO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Implementation of {@link Response.ContentListener} that
 * saves the response content to a file {@link Path}, like
 * {@code curl <url> -o file.bin} does.</p>
 * <p>Typical usage is:</p>
 * <pre>{@code
 * // Typical usage.
 * httpClient.newRequest(host, port)
 *     .send(new PathResponseListener(Path.of("/tmp/file.bin")), overwriteExistingFile);
 *
 * // Alternative usage.
 * var request = httpClient.newRequest(host, port);
 * CompletableFuture<PathResponse> completable = PathResponseListener.write(request, Path.of("/tmp/file.bin"), overwriteExistingFile);
 * }</pre>
 */
public class PathResponseListener extends CompletableFuture<PathResponseListener.PathResponse> implements Listener
{
    private static final Logger LOG = LoggerFactory.getLogger(InputStreamResponseListener.class);

    private final Path path;
    private final FileChannel fileChannel;

    public PathResponseListener(Path path, boolean overwrite) throws IOException
    {
        this.path = path;

        if (Files.exists(path) && !overwrite)
            throw new FileAlreadyExistsException(path.toString(), null, "File cannot be overwritten");

        fileChannel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Override
    public void onHeaders(Response response)
    {
        if (response.getStatus() != HttpStatus.OK_200)
            response.abort(new HttpResponseException(String.format("Cannot save response content for HTTP status code %d", response.getStatus()), response));
        else if (LOG.isDebugEnabled())
            LOG.debug("saving response content to {}", path);
    }

    @Override
    public void onContent(Response response, ByteBuffer content)
    {
        try
        {
            var bytesWritten = fileChannel.write(content);
            if (LOG.isDebugEnabled())
                LOG.debug("{} bytes written to {}", bytesWritten, path);
        }
        catch (Throwable x)
        {
            response.abort(x);
        }
    }

    @Override
    public void onSuccess(Response response)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("saved response content to {}", path);
    }

    @Override
    public void onFailure(Response response, Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("failed to save response content to {}", path);
    }

    @Override
    public void onComplete(Result result)
    {
        IO.close(fileChannel);
        if (result.isSucceeded())
            complete(new PathResponse(result.getResponse(), path));
        else
            completeExceptionally(result.getFailure());
    }

    /**
     * <p>Writes the response content to the given file {@link Path}.</p>
     * 
     * @param request the HTTP request
     * @param path the path to write the response content to
     * @param overwrite whether to overwrite an existing file
     * @return a {@link CompletableFuture} that is completed when the exchange completes
     */
    public static CompletableFuture<PathResponse> write(Request request, Path path, boolean overwrite)
    {
        PathResponseListener listener = null;
        try
        {
            listener = new PathResponseListener(path, overwrite);
            request.send(listener);
            return listener;
        }
        catch (Throwable x)
        {
            CompletableFuture<PathResponse> completable = Objects.requireNonNullElse(listener, new CompletableFuture<>());
            completable.completeExceptionally(x);
            return completable;
        }
    }

    public record PathResponse(Response response, Path path)
    {
    }
}
