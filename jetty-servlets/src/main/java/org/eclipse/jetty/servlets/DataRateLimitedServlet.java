//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.servlets;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel.MapMode;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.util.ProcessorUtils;

/**
 * A demonstration servlet that uses the Servlet 3.1 asynchronous IO API to server
 * static content at a limited data rate.
 * <p>
 * Two implementations are supported: <ul>
 * <li>The <code>StandardDataStream</code> impl uses only standard
 * APIs, but produces more garbage due to the byte[] nature of the API.
 * <li>the <code>JettyDataStream</code> impl uses a Jetty API to write a ByteBuffer
 * and thus allow the efficient use of file mapped buffers without any
 * temporary buffer copies.
 * </ul>
 * <p>
 * The data rate is controlled by setting init parameters:
 * <dl>
 * <dt>buffersize</dt><dd>The amount of data in bytes written per write</dd>
 * <dt>pause</dt><dd>The period in ms to wait after a write before attempting another</dd>
 * <dt>pool</dt><dd>The size of the thread pool used to service the writes (defaults to available processors)</dd>
 * </dl>
 * Thus if buffersize = 1024 and pause = 100, the data rate will be limited to 10KB per second.
 * @deprecated this is intended as a demonstration and not production quality.
 */
@Deprecated
public class DataRateLimitedServlet extends HttpServlet
{
    private static final long serialVersionUID = -4771757707068097025L;
    private int buffersize = 8192;
    private long pauseNS = TimeUnit.MILLISECONDS.toNanos(100);
    ScheduledThreadPoolExecutor scheduler;
    private final ConcurrentHashMap<String, ByteBuffer> cache = new ConcurrentHashMap<>();

    @Override
    public void init() throws ServletException
    {
        // read the init params
        String tmp = getInitParameter("buffersize");
        if (tmp != null)
            buffersize = Integer.parseInt(tmp);
        tmp = getInitParameter("pause");
        if (tmp != null)
            pauseNS = TimeUnit.MILLISECONDS.toNanos(Integer.parseInt(tmp));
        tmp = getInitParameter("pool");
        int pool = tmp == null ? ProcessorUtils.availableProcessors() : Integer.parseInt(tmp);

        // Create and start a shared scheduler.  
        scheduler = new ScheduledThreadPoolExecutor(pool);
    }

    @Override
    public void destroy()
    {
        scheduler.shutdown();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        // Get the path of the static resource to serve.
        String info = request.getPathInfo();

        // We don't handle directories
        if (info.endsWith("/"))
        {
            response.sendError(503, "directories not supported");
            return;
        }

        // Set the mime type of the response
        String contentType = getServletContext().getMimeType(info);
        response.setContentType(contentType == null ? "application/x-data" : contentType);

        // Look for a matching file path
        String path = request.getPathTranslated();

        // If we have a file path and this is a jetty response, we can use the JettyStream impl
        ServletOutputStream out = response.getOutputStream();
        if (path != null && out instanceof HttpOutput)
        {
            // If the file exists
            File file = new File(path);
            if (file.exists() && file.canRead())
            {
                // Set the content length
                response.setContentLengthLong(file.length());

                // Look for a file mapped buffer in the cache
                ByteBuffer mapped = cache.get(path);

                // Handle cache miss
                if (mapped == null)
                {
                    // TODO implement LRU cache flush
                    try (RandomAccessFile raf = new RandomAccessFile(file, "r"))
                    {
                        ByteBuffer buf = raf.getChannel().map(MapMode.READ_ONLY, 0, raf.length());
                        mapped = cache.putIfAbsent(path, buf);
                        if (mapped == null)
                            mapped = buf;
                    }
                }

                // start async request handling
                AsyncContext async = request.startAsync();

                // Set a JettyStream as the write listener to write the content asynchronously.
                out.setWriteListener(new JettyDataStream(mapped, async, out));
                return;
            }
        }

        // Jetty API was not used, so lets try the standards approach

        // Can we find the content as an input stream
        InputStream content = getServletContext().getResourceAsStream(info);
        if (content == null)
        {
            response.sendError(404);
            return;
        }

        // Set a StandardStream as he write listener to write the content asynchronously
        out.setWriteListener(new StandardDataStream(content, request.startAsync(), out));
    }

    /**
     * A standard API Stream writer
     */
    private final class StandardDataStream implements WriteListener, Runnable
    {
        private final InputStream content;
        private final AsyncContext async;
        private final ServletOutputStream out;

        private StandardDataStream(InputStream content, AsyncContext async, ServletOutputStream out)
        {
            this.content = content;
            this.async = async;
            this.out = out;
        }

        @Override
        public void onWritePossible() throws IOException
        {
            // If we are able to write
            if (out.isReady())
            {
                // Allocated a copy buffer for each write, so as to not hold while paused
                // TODO put these buffers into a pool
                byte[] buffer = new byte[buffersize];

                // read some content into the copy buffer
                int len = content.read(buffer);

                // If we are at EOF
                if (len < 0)
                {
                    // complete the async lifecycle
                    async.complete();
                    return;
                }

                // write out the copy buffer.  This will be an asynchronous write
                // and will always return immediately without blocking.  If a subsequent
                // call to out.isReady() returns false, then this onWritePossible method
                // will be called back when a write is possible.
                out.write(buffer, 0, len);

                // Schedule a timer callback to pause writing.  Because isReady() is not called,
                // a onWritePossible callback is no scheduled.
                scheduler.schedule(this, pauseNS, TimeUnit.NANOSECONDS);
            }
        }

        @Override
        public void run()
        {
            try
            {
                // When the pause timer wakes up, call onWritePossible.  Either isReady() will return
                // true and another chunk of content will be written, or it will return false and the 
                // onWritePossible() callback will be scheduled when a write is next possible.
                onWritePossible();
            }
            catch (Exception e)
            {
                onError(e);
            }
        }

        @Override
        public void onError(Throwable t)
        {
            getServletContext().log("Async Error", t);
            async.complete();
        }
    }

    /**
     * A Jetty API DataStream
     */
    private final class JettyDataStream implements WriteListener, Runnable
    {
        private final ByteBuffer content;
        private final int limit;
        private final AsyncContext async;
        private final HttpOutput out;

        private JettyDataStream(ByteBuffer content, AsyncContext async, ServletOutputStream out)
        {
            // Make a readonly copy of the passed buffer. This uses the same underlying content
            // without a copy, but gives this instance its own position and limit.
            this.content = content.asReadOnlyBuffer();
            // remember the ultimate limit.
            this.limit = this.content.limit();
            this.async = async;
            this.out = (HttpOutput)out;
        }

        @Override
        public void onWritePossible() throws IOException
        {
            // If we are able to write
            if (out.isReady())
            {
                // Position our buffers limit to allow only buffersize bytes to be written
                int l = content.position() + buffersize;
                // respect the ultimate limit
                if (l > limit)
                    l = limit;
                content.limit(l);

                // if all content has been written
                if (!content.hasRemaining())
                {
                    // complete the async lifecycle
                    async.complete();
                    return;
                }

                // write our limited buffer.  This will be an asynchronous write
                // and will always return immediately without blocking.  If a subsequent
                // call to out.isReady() returns false, then this onWritePossible method
                // will be called back when a write is possible.
                out.write(content);

                // Schedule a timer callback to pause writing.  Because isReady() is not called,
                // a onWritePossible callback is not scheduled.
                scheduler.schedule(this, pauseNS, TimeUnit.NANOSECONDS);
            }
        }

        @Override
        public void run()
        {
            try
            {
                // When the pause timer wakes up, call onWritePossible.  Either isReady() will return
                // true and another chunk of content will be written, or it will return false and the 
                // onWritePossible() callback will be scheduled when a write is next possible.
                onWritePossible();
            }
            catch (Exception e)
            {
                onError(e);
            }
        }

        @Override
        public void onError(Throwable t)
        {
            getServletContext().log("Async Error", t);
            async.complete();
        }
    }
}
