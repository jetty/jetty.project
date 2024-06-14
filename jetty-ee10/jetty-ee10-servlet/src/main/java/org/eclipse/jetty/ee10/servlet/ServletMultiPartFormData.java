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

package org.eclipse.jetty.ee10.servlet;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.Part;
import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiPartCompliance;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.InputStreamContentSource;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.util.StringUtil;

/**
 * <p>Servlet specific class for multipart content support.</p>
 * <p>Use {@link #from(ServletRequest)} to
 * parse multipart request content into a {@link Parts} object that can
 * be used to access Servlet {@link Part} objects.</p>
 *
 * @see Parts
 */
public class ServletMultiPartFormData
{
    /**
     * Get future {@link ServletMultiPartFormData.Parts} from a servlet request.
     * @param servletRequest A servlet request
     * @return A future {@link ServletMultiPartFormData.Parts}, which may have already been created and/or completed.
     * @see #from(ServletRequest, String)
     */
    public static CompletableFuture<Parts> from(ServletRequest servletRequest)
    {
        return from(servletRequest, servletRequest.getContentType());
    }

    /**
     * Get future {@link ServletMultiPartFormData.Parts} from a servlet request.
     * @param servletRequest A servlet request
     * @param contentType The contentType, passed as an optimization as it has likely already been retrieved.
     * @return A future {@link ServletMultiPartFormData.Parts}, which may have already been created and/or completed.
     */
    public static CompletableFuture<Parts> from(ServletRequest servletRequest, String contentType)
    {
        // Look for an existing future (we use the future here rather than the parts as it can remember any failure).
        @SuppressWarnings("unchecked")
        CompletableFuture<Parts> futureServletParts = (CompletableFuture<Parts>)servletRequest.getAttribute(ServletMultiPartFormData.class.getName());
        if (futureServletParts == null)
        {
            // No existing parts, so we need to try to read them ourselves

            // Is this servlet a valid target for Multipart?
            MultipartConfigElement config = (MultipartConfigElement)servletRequest.getAttribute(ServletContextRequest.MULTIPART_CONFIG_ELEMENT);
            if (config == null)
                return CompletableFuture.failedFuture(new IllegalStateException("No multipart configuration element"));

            // Are we the right content type to produce our own parts?
            if (contentType == null || !MimeTypes.Type.MULTIPART_FORM_DATA.is(HttpField.getValueParameters(contentType, null)))
                return CompletableFuture.failedFuture(new IllegalStateException("Not multipart Content-Type"));

            // Do we have a boundary?
            String boundary = MultiPart.extractBoundary(servletRequest.getContentType());
            if (boundary == null)
                return CompletableFuture.failedFuture(new IllegalStateException("No multipart boundary parameter in Content-Type"));

            // Can we access the core request, needed for components (eg buffer pools, temp directory, etc.) as well
            // as IO optimization
            ServletContextRequest servletContextRequest = ServletContextRequest.getServletContextRequest(servletRequest);
            if (servletContextRequest == null)
                return CompletableFuture.failedFuture(new IllegalStateException("No core request"));

            // Get a temporary directory for larger parts.
            File filesDirectory = StringUtil.isBlank(config.getLocation())
                ? servletContextRequest.getContext().getTempDirectory()
                : new File(config.getLocation());

            HttpChannel httpChannel = HttpChannel.from(servletContextRequest);
            ComplianceViolation.Listener complianceViolationListener = httpChannel.getComplianceViolationListener();
            MultiPartCompliance compliance = servletContextRequest.getConnectionMetaData().getHttpConfiguration().getMultiPartCompliance();

            // Look for an existing future MultiPartFormData.Parts
            CompletableFuture<MultiPartFormData.Parts> futureFormData = MultiPartFormData.from(servletContextRequest, compliance, complianceViolationListener, boundary, parser ->
            {
                try
                {
                    // No existing core parts, so we need to configure the parser.
                    ServletContextHandler contextHandler = servletContextRequest.getServletContext().getServletContextHandler();
                    ByteBufferPool byteBufferPool = servletContextRequest.getComponents().getByteBufferPool();
                    ConnectionMetaData connectionMetaData = servletContextRequest.getConnectionMetaData();
                    Connection connection = connectionMetaData.getConnection();

                    Content.Source source;
                    if (servletRequest instanceof ServletApiRequest servletApiRequest)
                    {
                        source = servletApiRequest.getRequest();
                    }
                    else
                    {
                        int bufferSize = connection instanceof AbstractConnection c ? c.getInputBufferSize() : 2048;
                        InputStreamContentSource iscs = new InputStreamContentSource(servletRequest.getInputStream(), byteBufferPool);
                        iscs.setBufferSize(bufferSize);
                        source = iscs;
                    }

                    parser.setMaxParts(contextHandler.getMaxFormKeys());
                    parser.setFilesDirectory(filesDirectory.toPath());
                    parser.setMaxMemoryFileSize(config.getFileSizeThreshold());
                    parser.setMaxFileSize(config.getMaxFileSize());
                    parser.setMaxLength(config.getMaxRequestSize());
                    parser.setPartHeadersMaxLength(connectionMetaData.getHttpConfiguration().getRequestHeaderSize());

                    // parse the core parts.
                    return parser.parse(source);
                }
                catch (Throwable failure)
                {
                    return CompletableFuture.failedFuture(failure);
                }
            });

            // When available, convert the core parts to servlet parts
            futureServletParts = futureFormData.thenApply(formDataParts -> new Parts(filesDirectory.toPath(), formDataParts));

            // cache the result in attributes.
            servletRequest.setAttribute(ServletMultiPartFormData.class.getName(), futureServletParts);
        }
        return futureServletParts;
    }

    /**
     * <p>An ordered list of {@link Part}s that can be accessed by name.</p>
     */
    public static class Parts
    {
        private final List<Part> parts = new ArrayList<>();

        public Parts(Path directory, MultiPartFormData.Parts parts)
        {
            parts.forEach(part -> this.parts.add(new ServletPart(directory, part)));
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
        private final Path _directory;
        private final MultiPart.Part _part;

        private ServletPart(Path directory, MultiPart.Part part)
        {
            _directory = directory;
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
            if (!filePath.isAbsolute() && Files.isDirectory(_directory))
                filePath = _directory.resolve(filePath).normalize();
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
