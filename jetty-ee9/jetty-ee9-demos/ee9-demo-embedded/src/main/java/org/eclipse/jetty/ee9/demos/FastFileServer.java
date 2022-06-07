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

package org.eclipse.jetty.ee9.demos;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.StandardOpenOption;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.servlet.DefaultServlet;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;

/**
 * Fast FileServer.
 * <p>
 * This example shows how to use the Jetty APIs for sending static as fast as
 * possible using various strategies for small, medium and large content.
 * </p>
 * <p>
 * The Jetty {@link DefaultServlet} does all this and more, and to a lesser
 * extent so does the {@link ResourceHandler}, so unless you have exceptional
 * circumstances it is best to use those classes for static content
 * </p>
 * <p>
 *     <em>WARNING</em>: This is an example on how to send content fast.
 *     It is not secure, is highly vulnerable, and does not contain the
 *     common set of mitigations for malicious requests that bypass
 *     your controls over what a client can access.
 *
 *     If you want to continue this codebase, consider adding
 *     checks for content outside of the resourceBase, and other
 *     bypasses such as alias references, alternate stream references,
 *     filesystem case sensitivity differences, filesystem utf-8 handling
 *     differences, bad filename concerns, etc..
 *
 *     Or just use the existing {@link DefaultServlet} or
 *     {@link ResourceHandler} that gives you all of these protections
 *     (and more) built-in.
 * </p>
 */
public class FastFileServer
{
    public static Server createServer(int port, File resourceBase)
    {
        Server server = new Server(port);

        server.setHandler(new HandlerList(
            new FastFileHandler(resourceBase),
            new DefaultHandler()));
        return server;
    }

    public static void main(String[] args) throws Exception
    {
        int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
        File directory = new File(System.getProperty("user.dir"));
        Server server = createServer(port, directory);
        server.start();
        server.join();
    }

    static class FastFileHandler extends AbstractHandler
    {
        private final MimeTypes mimeTypes = new MimeTypes();
        private final File dir;

        private FastFileHandler(File dir)
        {
            this.dir = dir;
        }

        @Override
        public void handle(String target,
                           Request baseRequest,
                           HttpServletRequest request,
                           HttpServletResponse response) throws IOException,
            ServletException
        {
            // define small medium and large.
            // This should be turned for your content, JVM and OS, but we will
            // huge HTTP response buffer size as a measure
            final int SMALL = response.getBufferSize();
            final int MEDIUM = 8 * SMALL;

            // What file to serve?
            final File file = new File(this.dir, request.getPathInfo());

            // Only handle existing files
            if (!file.exists())
                return;

            // we will handle this request
            baseRequest.setHandled(true);

            // Handle directories
            if (file.isDirectory())
            {
                if (!request.getPathInfo().endsWith(URIUtil.SLASH))
                {
                    response.sendRedirect(response.encodeRedirectURL(request.getRequestURI() + URIUtil.SLASH));
                    return;
                }
                String listing = Resource.newResource(file).getListHTML(
                    request.getRequestURI(),
                    request.getPathInfo().lastIndexOf("/") > 0,
                    request.getQueryString());
                response.setContentType("text/html; charset=utf-8");
                response.getWriter().println(listing);
                return;
            }

            // Set some content headers.

            // Jetty DefaultServlet will cache formatted date strings, but we
            // will reformat for each request here
            response.setDateHeader("Last-Modified", file.lastModified());
            response.setContentLengthLong(file.length());
            response.setContentType(mimeTypes.getMimeByExtension(file.getName()));

            // send "small" files blocking directly from an input stream
            if (file.length() < SMALL)
            {
                // need to caste to Jetty output stream for best API
                ((HttpOutput)response.getOutputStream())
                    .sendContent(FileChannel.open(file.toPath(),
                        StandardOpenOption.READ));
                return;
            }

            // send not "small" files asynchronously so we don't hold threads if
            // the client is slow
            final AsyncContext async = request.startAsync();
            Callback completionCB = new Callback()
            {
                @Override
                public void succeeded()
                {
                    // Async content write succeeded, so complete async response
                    async.complete();
                }

                @Override
                public void failed(Throwable x)
                {
                    // log error and complete async response;
                    x.printStackTrace();
                    async.complete();
                }

                @Override
                public InvocationType getInvocationType()
                {
                    return InvocationType.NON_BLOCKING;
                }
            };

            // send "medium" files from an input stream
            if (file.length() < MEDIUM)
            {
                // the file channel is closed by the async send
                ((HttpOutput)response.getOutputStream())
                    .sendContent(FileChannel.open(file.toPath(),
                        StandardOpenOption.READ), completionCB);
                return;
            }

            // for "large" files get the file mapped buffer to send Typically
            // the resulting buffer should be cached as allocating kernel memory
            // can be hard to GC on some JVMs. But for this example we will
            // create a new buffer per file
            ByteBuffer buffer;
            try (RandomAccessFile raf = new RandomAccessFile(file, "r");)
            {
                buffer = raf.getChannel().map(MapMode.READ_ONLY, 0,
                    raf.length());
            }

            // Assuming the file buffer might be shared cached version, so lets
            // take our own view of it
            buffer = buffer.asReadOnlyBuffer();

            // send the content as a buffer with a callback to complete the
            // async request need to caste to Jetty output stream for best API
            ((HttpOutput)response.getOutputStream()).sendContent(buffer,
                completionCB);
        }
    }
}
