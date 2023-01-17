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
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.handler.DelayedContentHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;

/**
 * <p>Servlet specific class for multipart content support.</p>
 * <p>Use {@link #from(ServletContextRequest.ServletApiRequest)} to
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
     * @see DelayedContentHandler
     */
    public static Parts from(ServletContextRequest.ServletApiRequest request) throws IOException
    {
        try
        {
            // Look for a previously read and parsed MultiPartFormData from the DelayedHandler
            MultiPartFormData formData = (MultiPartFormData)request.getAttribute(MultiPartFormData.class.getName());
            if (formData != null)
                return new Parts(formData);

            // TODO set the files directory
            return new ServletMultiPartFormData().parse(request);
        }
        catch (Throwable x)
        {
            throw IO.rethrow(x);
        }
    }

    private Parts parse(ServletContextRequest.ServletApiRequest request) throws IOException
    {
        MultipartConfigElement config = (MultipartConfigElement)request.getAttribute(ServletContextRequest.__MULTIPART_CONFIG_ELEMENT);
        if (config == null)
            throw new IllegalStateException("No multipart configuration element");

        String boundary = MultiPart.extractBoundary(request.getContentType());
        if (boundary == null)
            throw new IllegalStateException("No multipart boundary parameter in Content-Type");

        MultiPartFormData formData = new MultiPartFormData(boundary);

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
        ConnectionMetaData connectionMetaData = request.getRequest().getConnectionMetaData();
        formData.setPartHeadersMaxLength(connectionMetaData.getHttpConfiguration().getRequestHeaderSize());

        Connection connection = connectionMetaData.getConnection();
        int bufferSize = connection instanceof AbstractConnection c ? c.getInputBufferSize() : 2048;
        byte[] buffer = new byte[bufferSize];
        InputStream input = request.getInputStream();
        while (true)
        {
            int read = input.read(buffer);
            if (read < 0)
            {
                formData.parse(Content.Chunk.EOF);
                break;
            }
            Content.Chunk chunk = Content.Chunk.from(ByteBuffer.wrap(buffer, 0, read), false);
            formData.parse(chunk);
            chunk.release();
        }

        return new Parts(formData);
    }

    /**
     * <p>An ordered list of {@link Part}s that can be accessed by name.</p>
     */
    public static class Parts
    {
        private final List<Part> parts = new ArrayList<>();

        public Parts(MultiPartFormData formData)
        {
            formData.join().forEach(part -> parts.add(new ServletPart(formData, part)));
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
        private final long _length;
        private final InputStream _input;

        private ServletPart(MultiPartFormData formData, MultiPart.Part part)
        {
            _formData = formData;
            _part = part;
            Content.Source content = part.getContent();
            _length = content.getLength();
            _input = Content.Source.asInputStream(content);
        }

        @Override
        public InputStream getInputStream() throws IOException
        {
            return _input;
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
            return _length;
        }

        @Override
        public void write(String fileName) throws IOException
        {
            // TODO This should simply move a part that is already on the file system.
            Path filePath = Path.of(fileName);
            if (!filePath.isAbsolute())
                filePath = _formData.getFilesDirectory().resolve(filePath).normalize();
            _part.writeTo(filePath);
        }

        @Override
        public void delete() throws IOException
        {
            if (_part instanceof MultiPart.PathPart pathPart)
                pathPart.delete();
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
