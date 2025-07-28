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
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiPartConfig;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.InputStreamContentSource;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.thread.Invocable;

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
     * Get {@code multipart/form-data} {@link ServletMultiPartFormData.Parts} from a {@link ServletRequest}, caching the
     * results in the request {@link ServletRequest#getAttribute(String) Attributes}.  If not already available,
     * the {@code Parts} are read and parsed, blocking if necessary.
     * <p>
     * Calls to {@code onParts} and {@code getParts} methods are idempotent, and
     * can be called multiple times, with subsequent calls returning the results of the first call.
     * @param servletRequest A servlet request
     * @return the parts
     */
    static Parts getParts(ServletRequest servletRequest)
    {
        CompletableFuture<Parts> futureParts = from(servletRequest, org.eclipse.jetty.util.thread.Invocable.InvocationType.NON_BLOCKING);
        return futureParts.join();
    }

    /**
     * Asynchronously get {@code multipart/form-data} {@link ServletMultiPartFormData.Parts} from a {@link ServletRequest},
     * caching the results in the request {@link ServletRequest#getAttribute(String) Attributes}.  If not already available,
     * the {@code Parts} are read and parsed.
     * <p>
     * Calls to {@code onParts} and {@code getParts} methods are idempotent, and
     * can be called multiple times, with subsequent calls returning the results of the first call.
     * @param servletRequest A servlet request
     * @param contentType The contentType, passed as an optimization as it has likely already been retrieved.
     * @param promise The action to take when the {@link Parts} are available.
     */
    static void onParts(ServletRequest servletRequest, String contentType, Promise.Invocable<Parts> promise)
    {
        CompletableFuture<Parts> futureParts = from(servletRequest, promise.getInvocationType(), contentType);
        futureParts.whenComplete(promise);
    }

    /**
     * Get future {@link ServletMultiPartFormData.Parts} from a servlet request.
     * @param servletRequest A servlet request
     * @return A future {@link ServletMultiPartFormData.Parts}, which may have already been created and/or completed.
     * @see #from(ServletRequest, String)
     */
    @Deprecated(forRemoval = true, since = "12.0.15")
    public static CompletableFuture<Parts> from(ServletRequest servletRequest)
    {
        return from(servletRequest, org.eclipse.jetty.util.thread.Invocable.InvocationType.NON_BLOCKING, servletRequest.getContentType());
    }

    /**
     * Get future {@link ServletMultiPartFormData.Parts} from a servlet request.
     * @param servletRequest A servlet request
     * @param invocationType The invocation type of the resulting CompletableFuture.
     * @return A future {@link ServletMultiPartFormData.Parts}, which may have already been created and/or completed.
     * @see #from(ServletRequest, String)
     */
    @Deprecated(forRemoval = true, since = "12.0.15")
    static CompletableFuture<Parts> from(ServletRequest servletRequest, Invocable.InvocationType invocationType)
    {
        return from(servletRequest, invocationType, servletRequest.getContentType());
    }

    /**
     * Get future {@link ServletMultiPartFormData.Parts} from a servlet request.
     * @param servletRequest A servlet request
     * @param contentType The contentType, passed as an optimization as it has likely already been retrieved.
     * @return A future {@link ServletMultiPartFormData.Parts}, which may have already been created and/or completed.
     */
    @Deprecated(forRemoval = true, since = "12.0.15")
    public static CompletableFuture<Parts> from(ServletRequest servletRequest, String contentType)
    {
        return from(servletRequest, org.eclipse.jetty.util.thread.Invocable.InvocationType.NON_BLOCKING, contentType);
    }

    /**
     * Get future {@link ServletMultiPartFormData.Parts} from a servlet request.
     * @param servletRequest A servlet request
     * @param invocationType The invocation type of the resulting CompletableFuture.
     * @param contentType The contentType, passed as an optimization as it has likely already been retrieved.
     * @return A future {@link ServletMultiPartFormData.Parts}, which may have already been created and/or completed.
     */
    @Deprecated(forRemoval = true, since = "12.0.15")
    static CompletableFuture<Parts> from(ServletRequest servletRequest, Invocable.InvocationType invocationType, String contentType)
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
            Path filesDirectory = StringUtil.isBlank(config.getLocation())
                ? servletContextRequest.getContext().getTempDirectory().toPath()
                : new File(config.getLocation()).toPath();

            try
            {
                // Look for an existing future MultiPartFormData.Parts
                MultiPartFormData.Parts formParts = MultiPartFormData.getParts(servletContextRequest);
                if (formParts != null)
                {
                    futureServletParts = CompletableFuture.completedFuture(new Parts(filesDirectory, formParts));
                }
                else
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
                        source = new InputStreamContentSource(servletRequest.getInputStream(), new ByteBufferPool.Sized(byteBufferPool, false, bufferSize));
                    }

                    MultiPartConfig multiPartConfig = Request.getMultiPartConfig(servletContextRequest, filesDirectory)
                        .location(filesDirectory)
                        .maxParts(contextHandler.getMaxFormKeys())
                        .maxMemoryPartSize(config.getFileSizeThreshold())
                        .maxPartSize(config.getMaxFileSize())
                        .maxSize(config.getMaxRequestSize())
                        .build();

                    futureServletParts = new CompletableFuture<>();
                    CompletableFuture<Parts> futureConvertParts = futureServletParts;

                    Promise.Invocable<MultiPartFormData.Parts> onParts = new Promise.Invocable<>()
                    {
                        @Override
                        public void failed(Throwable x)
                        {
                            futureConvertParts.completeExceptionally(x);
                        }

                        @Override
                        public void succeeded(MultiPartFormData.Parts parts)
                        {
                            futureConvertParts.complete(new Parts(filesDirectory, parts));
                        }

                        @Override
                        public InvocationType getInvocationType()
                        {
                            return invocationType;
                        }
                    };

                    MultiPartFormData.onParts(source, servletContextRequest, contentType, multiPartConfig, onParts);
                }
                // cache the result in attributes.
                servletRequest.setAttribute(ServletMultiPartFormData.class.getName(), futureServletParts);

            }
            catch (Throwable failure)
            {
                return CompletableFuture.failedFuture(failure);
            }
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
            return Content.Source.asInputStream(_part.newContentSource(null, 0L, -1L)); // TODO use a pool
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
