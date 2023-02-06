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

package org.eclipse.jetty.ee10.servlet;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.Part;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;

/**
 * <p>Servlet specific class for multipart content support.</p>
 * <p>Use {@link #from(ServletApiRequest)} to
 * parse multipart request content into a {@link Parts} object that can
 * be used to access Servlet {@link Part} objects.</p>
 *
 * @see Parts
 */
public class ServletMultiPartFormData
{
    /**
     * <p>Parses the request content assuming it is a multipart content,
     * and returns a {@link Parts} objects that can be used to access
     * individual {@link Part}s.</p>
     *
     * @param request the HTTP request with multipart content
     * @return a {@link Parts} object to access the individual {@link Part}s
     * @throws IOException if reading the request content fails
     * @see org.eclipse.jetty.server.handler.DelayedHandler
     */
    public static Parts from(ServletApiRequest request) throws IOException
    {
        return from(request, ServletContextHandler.DEFAULT_MAX_FORM_KEYS);
    }

    /**
     * <p>Parses the request content assuming it is a multipart content,
     * and returns a {@link Parts} objects that can be used to access
     * individual {@link Part}s.</p>
     *
     * @param request the HTTP request with multipart content
     * @return a {@link Parts} object to access the individual {@link Part}s
     * @throws IOException if reading the request content fails
     * @see org.eclipse.jetty.server.handler.DelayedHandler
     */
    public static Parts from(ServletApiRequest request, int maxParts) throws IOException
    {
        try
        {
            // Look for a previously read and parsed MultiPartFormData from the DelayedHandler.
            MultiPartFormData.Parts parts = (MultiPartFormData.Parts)request.getAttribute(MultiPartFormData.Parts.class.getName());
            if (parts != null)
                return new Parts(parts);

            // TODO set the files directory
            return new ServletMultiPartFormData().parse(request, maxParts);
        }
        catch (Throwable x)
        {
            throw IO.rethrow(x);
        }
    }

    private Parts parse(ServletApiRequest request, int maxParts) throws IOException
    {
        MultipartConfigElement config = (MultipartConfigElement)request.getAttribute(ServletContextRequest.__MULTIPART_CONFIG_ELEMENT);
        if (config == null)
            throw new IllegalStateException("No multipart configuration element");

        String boundary = MultiPart.extractBoundary(request.getContentType());
        if (boundary == null)
            throw new IllegalStateException("No multipart boundary parameter in Content-Type");

        // Store MultiPartFormData as attribute on request so it is released by the HttpChannel.
        MultiPartFormData formData = new MultiPartFormData(boundary);
        formData.setMaxParts(maxParts);

        File tmpDirFile = (File)request.getServletContext().getAttribute(ServletContext.TEMPDIR);
        if (tmpDirFile == null)
            tmpDirFile = new File(System.getProperty("java.io.tmpdir"));
        String fileLocation = config.getLocation();
        if (!StringUtil.isBlank(fileLocation))
            tmpDirFile = new File(fileLocation);

        formData.setFilesDirectory(tmpDirFile.toPath());
        formData.setMaxMemoryFileSize(config.getFileSizeThreshold());
        formData.setMaxFileSize(config.getMaxFileSize());
        formData.setMaxLength(config.getMaxRequestSize());
        ConnectionMetaData connectionMetaData = request.getServletContextRequest().getConnectionMetaData();
        formData.setPartHeadersMaxLength(connectionMetaData.getHttpConfiguration().getRequestHeaderSize());

        RetainableByteBufferPool byteBufferPool = request.getServletContextRequest().getComponents().getRetainableByteBufferPool();
        Connection connection = connectionMetaData.getConnection();
        int bufferSize = connection instanceof AbstractConnection c ? c.getInputBufferSize() : 2048;
        InputStream input = request.getInputStream();
        while (!formData.isDone())
        {
            RetainableByteBuffer retainable = byteBufferPool.acquire(bufferSize, false);
            boolean readEof = false;
            ByteBuffer buffer = retainable.getByteBuffer();
            while (BufferUtil.space(buffer) > bufferSize / 2)
            {
                int read = BufferUtil.readFrom(input, buffer);
                if (read < 0)
                {
                    readEof = true;
                    break;
                }
            }

            formData.parse(Content.Chunk.from(buffer, false, retainable::release));
            if (readEof)
            {
                formData.parse(Content.Chunk.EOF);
                break;
            }
        }

        Parts parts = new Parts(formData.join());
        request.setAttribute(Parts.class.getName(), parts);
        return parts;
    }

    /**
     * <p>An ordered list of {@link Part}s that can be accessed by name.</p>
     */
    public static class Parts
    {
        private final List<Part> parts = new ArrayList<>();

        public Parts(MultiPartFormData.Parts parts)
        {
            parts.forEach(part -> this.parts.add(new ServletPart(parts.getMultiPartFormData(), part)));
        }

        public Part getPart(String name)
        {
            return parts.stream()
                .filter(part -> part.getName().equals(name))
                .findFirst()
                .orElse(null);
        }

        public Collection<Part> getParts()
        {
            return List.copyOf(parts);
        }
    }

    private static class ServletPart implements Part
    {
        private final MultiPartFormData _formData;
        private final MultiPart.Part _part;

        private ServletPart(MultiPartFormData formData, MultiPart.Part part)
        {
            _formData = formData;
            _part = part;
        }

        @Override
        public InputStream getInputStream() throws IOException
        {
            return Content.Source.asInputStream(_part.newContentSource());
        }

        @Override
        public String getContentType()
        {
            return _part.getHeaders().get(HttpHeader.CONTENT_TYPE);
        }

        @Override
        public String getName()
        {
            return _part.getName();
        }

        @Override
        public String getSubmittedFileName()
        {
            return _part.getFileName();
        }

        @Override
        public long getSize()
        {
            return _part.getLength();
        }

        @Override
        public void write(String fileName) throws IOException
        {
            Path filePath = Path.of(fileName);
            if (!filePath.isAbsolute())
                filePath = _formData.getFilesDirectory().resolve(filePath).normalize();
            _part.writeTo(filePath);
        }

        @Override
        public void delete() throws IOException
        {
            _part.delete();
        }

        @Override
        public String getHeader(String name)
        {
            return _part.getHeaders().get(name);
        }

        @Override
        public Collection<String> getHeaders(String name)
        {
            return _part.getHeaders().getValuesList(name);
        }

        @Override
        public Collection<String> getHeaderNames()
        {
            return _part.getHeaders().getFieldNamesCollection();
        }

        @Override
        public String toString()
        {
            return "%s@%x[part=%s]".formatted(
                getClass().getSimpleName(),
                hashCode(),
                _part
            );
        }
    }
}
