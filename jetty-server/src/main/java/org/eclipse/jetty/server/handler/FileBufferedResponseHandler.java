//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpOutput.Interceptor;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * A Handler that can apply a {@link org.eclipse.jetty.server.HttpOutput.Interceptor}
 * mechanism to buffer the entire response content until the output is closed.
 * This allows the commit to be delayed until the response is complete and thus
 * headers and response status can be changed while writing the body.
 * </p>
 * <p>
 * Note that the decision to buffer is influenced by the headers and status at the
 * first write, and thus subsequent changes to those headers will not influence the
 * decision to buffer or not.
 * </p>
 * <p>
 * Note also that there are no memory limits to the size of the buffer, thus
 * this handler can represent an unbounded memory commitment if the content
 * generated can also be unbounded.
 * </p>
 */
public class FileBufferedResponseHandler extends BufferedResponseHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(FileBufferedResponseHandler.class);

    private Path _tempDir = new File(System.getProperty("java.io.tmpdir")).toPath();

    public Path getTempDir()
    {
        return _tempDir;
    }

    public void setTempDir(Path tempDir)
    {
        _tempDir = Objects.requireNonNull(tempDir);
    }

    @Override
    protected BufferedInterceptor newBufferedInterceptor(HttpChannel httpChannel, Interceptor interceptor)
    {
        return new FileBufferedInterceptor(httpChannel, interceptor);
    }

    private class FileBufferedInterceptor implements BufferedResponseHandler.BufferedInterceptor
    {
        private static final int MAPPED_BUFFER_SIZE = Integer.MAX_VALUE / 2;

        private final Interceptor _next;
        private final HttpChannel _channel;
        private Boolean _aggregating;
        private Path _filePath;
        private OutputStream _fileOutputStream;

        public FileBufferedInterceptor(HttpChannel httpChannel, Interceptor interceptor)
        {
            _next = interceptor;
            _channel = httpChannel;
        }

        @Override
        public void resetBuffer()
        {
            if (_filePath != null)
            {
                try
                {
                    Files.delete(_filePath);
                }
                catch (Throwable t)
                {
                    LOG.warn("Could Not Delete File {}", _filePath, t);
                }
            }

            IO.close(_fileOutputStream);
            _fileOutputStream = null;
            _aggregating = null;
            _filePath = null;
        }

        @Override
        public void write(ByteBuffer content, boolean last, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} write last={} {}", this, last, BufferUtil.toDetailString(content));

            // If we are not committed, must decide if we should aggregate or not.
            if (_aggregating == null)
            {
                if (last)
                    _aggregating = Boolean.FALSE;
                else
                {
                    Response response = _channel.getResponse();
                    int sc = response.getStatus();
                    if (sc > 0 && (sc < 200 || sc == 204 || sc == 205 || sc >= 300))
                        _aggregating = Boolean.FALSE;  // No body
                    else
                    {
                        String ct = response.getContentType();
                        if (ct == null)
                            _aggregating = Boolean.TRUE;
                        else
                        {
                            ct = MimeTypes.getContentTypeWithoutCharset(ct);
                            _aggregating = isMimeTypeBufferable(StringUtil.asciiToLowerCase(ct));
                        }
                    }
                }
            }

            // If we are not aggregating, then handle normally.
            if (!_aggregating)
            {
                getNextInterceptor().write(content, last, callback);
                return;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("{} aggregating", this);

            try
            {
                if (BufferUtil.hasContent(content))
                    aggregate(content);
            }
            catch (Throwable t)
            {
                resetBuffer();
                callback.failed(t);
                return;
            }

            if (last)
                commit(callback);
            else
                callback.succeeded();
        }

        protected void aggregate(ByteBuffer content) throws IOException
        {
            if (_fileOutputStream == null)
            {
                // Create a new OutputStream to a file.
                _filePath = Files.createTempFile(_tempDir, "BufferedResponse", "");
                _fileOutputStream = Files.newOutputStream(_filePath, StandardOpenOption.WRITE);
            }

            BufferUtil.writeTo(content, _fileOutputStream);
        }

        @Override
        public Interceptor getNextInterceptor()
        {
            return _next;
        }

        protected void commit(Callback callback)
        {
            if (_fileOutputStream == null)
            {
                // We have no content to write, signal next interceptor that we are finished.
                getNextInterceptor().write(BufferUtil.EMPTY_BUFFER, true, callback);
                return;
            }

            try
            {
                _fileOutputStream.close();
                _fileOutputStream = null;
            }
            catch (Throwable t)
            {
                resetBuffer();
                callback.failed(t);
                return;
            }

            // Create an iterating callback to do the writing
            IteratingCallback icb = new IteratingCallback()
            {
                private final long fileLength = _filePath.toFile().length();
                private long _pos = 0;
                private boolean _last = false;

                @Override
                protected Action process() throws Exception
                {
                    if (_last)
                        return Action.SUCCEEDED;

                    long len = Math.min(MAPPED_BUFFER_SIZE, fileLength - _pos);
                    _last = (_pos + len == fileLength);
                    ByteBuffer buffer = BufferUtil.toMappedBuffer(_filePath, _pos, len);
                    getNextInterceptor().write(buffer, _last, this);
                    _pos += len;
                    return Action.SCHEDULED;
                }

                @Override
                protected void onCompleteSuccess()
                {
                    try
                    {
                        Files.delete(_filePath);
                        callback.succeeded();
                    }
                    catch (Throwable t)
                    {
                        callback.failed(t);
                    }
                }

                @Override
                protected void onCompleteFailure(Throwable cause)
                {
                    try
                    {
                        Files.delete(_filePath);
                    }
                    catch (Throwable t)
                    {
                        cause.addSuppressed(t);
                    }

                    callback.failed(cause);
                }
            };
            icb.iterate();
        }
    }
}
