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

package org.eclipse.jetty.compression.server;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.pathmap.PathSpecSet;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.AsciiLowerCaseSet;
import org.eclipse.jetty.util.IncludeExclude;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;

@ManagedObject("Compression Configuration")
public class CompressionConfig extends AbstractLifeCycle
{
    /**
     * Set of {@code Accept-Encoding} encodings that are supported for compressing Response content.
     */
    private final IncludeExcludeSet<String, String> compressEncodings;
    /**
     * Set of {@code Content-Encoding} encodings that are supported for decompressing Request content.
     */
    private final IncludeExcludeSet<String, String> decompressEncodings;
    /**
     * Set of HTTP Methods that are supported for compressing Response content.
     */
    private final IncludeExcludeSet<String, String> compressMethods;
    /**
     * Set of HTTP Methods that are supported for decompressing Request content.
     */
    private final IncludeExcludeSet<String, String> decompressMethods;
    /**
     * Mime-Types that support decompressing of Request content.
     */
    private final IncludeExcludeSet<String, String> compressMimeTypes;
    /**
     * Mime-Types that support compressing Response content.
     */
    private final IncludeExcludeSet<String, String> decompressMimeTypes;
    /**
     * Set of paths that support compressing Response content.
     */
    private final IncludeExcludeSet<String, String> compressPaths;
    /**
     * Set of paths that support decompressing Request content.
     */
    private final IncludeExcludeSet<String, String> decompressPaths;

    private final HttpField vary;

    private CompressionConfig(Builder builder)
    {
        this.compressEncodings = builder.compressEncodings.asImmutable();
        this.decompressEncodings = builder.decompressEncodings.asImmutable();
        this.compressMethods = builder.decompressMethods.asImmutable();
        this.decompressMethods = builder.decompressMethods.asImmutable();
        this.compressMimeTypes = builder.compressMimeTypes.asImmutable();
        this.decompressMimeTypes = builder.decompressMimeTypes.asImmutable();
        this.compressPaths = builder.compressPaths.asImmutable();
        this.decompressPaths = builder.decompressPaths.asImmutable();
        this.vary = builder.vary;
    }

    public static Builder builder()
    {
        return new Builder();
    }

    /**
     * Get the set of excluded HTTP methods for Response compression.
     *
     * @return the set of excluded HTTP methods
     * @see #getCompressMethodIncludes()
     */
    @ManagedAttribute("Set of HTTP Method Exclusions")
    public Set<String> getCompressMethodExcludes()
    {
        Set<String> excluded = compressMethods.getExcluded();
        return Collections.unmodifiableSet(excluded);
    }

    /**
     * Get the set of included HTTP methods for Response compression
     *
     * @return the set of included HTTP methods
     * @see #getCompressMethodExcludes()
     */
    @ManagedAttribute("Set of HTTP Method Inclusions")
    public Set<String> getCompressMethodIncludes()
    {
        Set<String> includes = compressMethods.getIncluded();
        return Collections.unmodifiableSet(includes);
    }

    /**
     * Get the set of excluded MIME types for Response compression.
     *
     * @return the set of excluded MIME types
     * @see #getCompressMimeTypeIncludes()
     */
    @ManagedAttribute("Set of Mime-Types Excluded from Response compression")
    public Set<String> getCompressMimeTypeExcludes()
    {
        Set<String> excluded = compressMimeTypes.getExcluded();
        return Collections.unmodifiableSet(excluded);
    }

    /**
     * Get the set of included MIME types for Response compression.
     *
     * @return the filter list of included MIME types
     * @see #getCompressMimeTypeExcludes()
     */
    @ManagedAttribute("Set of Mime-Types Included in Response compression")
    public Set<String> getCompressMimeTypeIncludes()
    {
        Set<String> includes = compressMimeTypes.getIncluded();
        return Collections.unmodifiableSet(includes);
    }

    /**
     * Get the set of excluded Path Specs for response compression.
     *
     * @return the set of excluded Path Specs
     * @see #getCompressPathIncludes()
     */
    @ManagedAttribute("Set of Response Compression Path Exclusions")
    public Set<String> getCompressPathExcludes()
    {
        Set<String> excluded = compressPaths.getExcluded();
        return Collections.unmodifiableSet(excluded);
    }

    /**
     * Get the set of included Path Specs for response compression.
     *
     * @return the set of included Path Specs
     * @see #getCompressPathExcludes()
     */
    @ManagedAttribute("Set of Response Compression Path Exclusions")
    public Set<String> getCompressPathIncludes()
    {
        Set<String> includes = compressPaths.getIncluded();
        return Collections.unmodifiableSet(includes);
    }

    public String getCompressionEncoding(List<String> requestAcceptEncoding, Request request, String pathInContext)
    {
        if (requestAcceptEncoding == null || requestAcceptEncoding.isEmpty())
            return null;

        String matchedEncoding = null;

        for (String encoding : requestAcceptEncoding)
        {
            if (compressEncodings.test(encoding))
            {
                matchedEncoding = encoding;
            }
        }

        if (matchedEncoding == null)
            return null;

        if (!compressMethods.test(request.getMethod()))
            return null;

        if (!compressPaths.test(pathInContext))
            return null;

        return matchedEncoding;
    }

    /**
     * Get the set of excluded HTTP methods for Request decompression.
     *
     * @return the set of excluded HTTP methods
     * @see #getDecompressMethodIncludes()
     */
    @ManagedAttribute("Set of HTTP Method Exclusions")
    public Set<String> getDecompressMethodExcludes()
    {
        Set<String> excluded = decompressMethods.getExcluded();
        return Collections.unmodifiableSet(excluded);
    }

    /**
     * Get the set of included HTTP methods for Request decompression
     *
     * @return the set of included HTTP methods
     * @see #getDecompressMethodExcludes()
     */
    @ManagedAttribute("Set of HTTP Method Inclusions")
    public Set<String> getDecompressMethodIncludes()
    {
        Set<String> includes = decompressMethods.getIncluded();
        return Collections.unmodifiableSet(includes);
    }

    /**
     * Get the set of excluded Path Specs for request decompression.
     *
     * @return the set of excluded Path Specs
     * @see #getDecompressPathIncludes()
     */
    @ManagedAttribute("Set of Request Decompression Path Exclusions")
    public Set<String> getDecompressPathExcludes()
    {
        Set<String> excluded = decompressPaths.getExcluded();
        return Collections.unmodifiableSet(excluded);
    }

    /**
     * Get the set of included Path Specs for request decompression.
     *
     * @return the set of included Path Specs
     * @see #getDecompressPathExcludes()
     */
    @ManagedAttribute("Set of Request Decompression Path Inclusions")
    public Set<String> getDecompressPathIncludes()
    {
        Set<String> includes = decompressPaths.getIncluded();
        return Collections.unmodifiableSet(includes);
    }

    public String getDecompressionEncoding(String requestContentEncoding, Request request, String pathInContext)
    {
        String matchedEncoding = null;

        if (decompressEncodings.test(requestContentEncoding))
            matchedEncoding = requestContentEncoding;

        if (!decompressMethods.test(request.getMethod()))
            return null;

        String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
        if (!decompressMimeTypes.test(contentType))
            return null;

        return matchedEncoding;
    }

    /**
     * @return The VARY field to use.
     */
    public HttpField getVary()
    {
        return vary;
    }

    public boolean isCompressMethodSupported(String method)
    {
        return compressMethods.test(method);
    }

    public boolean isCompressMimeTypeSupported(String mimeType)
    {
        return compressMimeTypes.test(mimeType);
    }

    public boolean isDecompressMethodSupported(String method)
    {
        return decompressMethods.test(method);
    }

    public boolean isDecompressMimeTypeSupported(String mimeType)
    {
        return decompressMimeTypes.test(mimeType);
    }

    /**
     * Builder of CompressionConfig immutable instances.
     *
     * <p><em>Notes about PathSpec strings</em></p>
     *
     * <p>
     * There are 2 syntaxes supported, Servlet {@code url-pattern} based, and
     * Regex based.  This means that the initial characters on the path spec
     * line are very strict, and determine the behavior of the path matching.
     * </p>
     *
     * <ul>
     * <li>If the spec starts with {@code '^'} the spec is assumed to be
     * a regex based path spec and will match with normal Java regex rules.</li>
     * <li>If the spec starts with {@code '/'} then spec is assumed to be
     * a Servlet url-pattern rules path spec for either an exact match
     * or prefix based match.</li>
     * <li>If the spec starts with {@code '*.'} then spec is assumed to be
     * a Servlet url-pattern rules path spec for a suffix based match.</li>
     * <li>All other syntaxes are unsupported</li>
     * </ul>
     *
     * <p>
     *     Note: inclusion take precedence over exclude.
     * </p>
     */
    public static class Builder
    {
        /**
         * Set of {@code Content-Encoding} encodings that are supported for decompressing Request content.
         */
        private final IncludeExclude<String> decompressEncodings = new IncludeExclude<>();
        /**
         * Set of {@code Accept-Encoding} encodings that are supported for compressing Response content.
         */
        private final IncludeExclude<String> compressEncodings = new IncludeExclude<>();
        /**
         * Set of HTTP Methods that are supported for decompressing Request content.
         */
        private final IncludeExclude<String> decompressMethods = new IncludeExclude<>();
        /**
         * Set of HTTP Methods that are supported for compressing Response content.
         */
        private final IncludeExclude<String> compressMethods = new IncludeExclude<>();
        /**
         * Set of paths that support decompressing of Request content.
         */
        private final IncludeExclude<String> decompressPaths = new IncludeExclude<>(PathSpecSet.class);
        /**
         * Set of paths that support compressing Response content.
         */
        private final IncludeExclude<String> compressPaths = new IncludeExclude<>(PathSpecSet.class);
        /**
         * Mime-Types that support decompressing of Request content.
         */
        private final IncludeExclude<String> compressMimeTypes = new IncludeExclude<>(AsciiLowerCaseSet.class);
        /**
         * Mime-Types that support compressing Response content.
         */
        private final IncludeExclude<String> decompressMimeTypes = new IncludeExclude<>(AsciiLowerCaseSet.class);
        private HttpField vary = new PreEncodedHttpField(HttpHeader.VARY, HttpHeader.ACCEPT_ENCODING.asString());

        public CompressionConfig build()
        {
            return new CompressionConfig(this);
        }

        /**
         * A {@code Accept-Encoding} encoding to exclude.
         *
         * @param encoding the encoding to exclude
         * @return this builder
         */
        public Builder compressEncodingExclude(String encoding)
        {
            this.compressEncodings.exclude(encoding);
            return this;
        }

        /**
         * A {@code Accept-Encoding} encoding to include.
         *
         * @param encoding the encoding to include
         * @return this builder
         */
        public Builder compressEncodingInclude(String encoding)
        {
            this.compressEncodings.include(encoding);
            return this;
        }

        /**
         * An HTTP method to exclude for Response compression.
         *
         * @param method the method to exclude
         * @return this builder
         */
        public Builder compressMethodExclude(String method)
        {
            this.compressMethods.exclude(method);
            return this;
        }

        /**
         * An HTTP method to include for Response compression.
         *
         * @param method the method to include
         * @return this builder
         */
        public Builder compressMethodInclude(String method)
        {
            this.compressMethods.include(method);
            return this;
        }

        /**
         * A non-compressible mimetype to exclude for Response compression.
         *
         * <p>
         * The response {@code Content-Type} is evaluated.
         * </p>
         *
         * @param mimetype the mimetype to exclude
         * @return this builder
         */
        public Builder compressMimeTypeExclude(String mimetype)
        {
            this.compressMimeTypes.exclude(mimetype);
            return this;
        }

        /**
         * A compressible mimetype to include for Response compression.
         *
         * <p>
         * The response {@code Content-Type} is evaluated.
         * </p>
         *
         * @param mimetype the mimetype to include
         * @return this builder
         */
        public Builder compressMimeTypeInclude(String mimetype)
        {
            this.compressMimeTypes.include(mimetype);
            return this;
        }

        /**
         * A path that does not supports response content compression.
         *
         * <p>
         * See {@link Builder} for details on PathSpec string.
         * </p>
         *
         * @param pathSpecString the path spec string to exclude.  The pathInContext
         * is used to match against this path spec.
         * @return this builder.
         * @see #compressPathInclude(String)
         */
        public Builder compressPathExclude(String pathSpecString)
        {
            this.compressPaths.exclude(pathSpecString);
            return this;
        }

        /**
         * A path that supports response content compression.
         *
         * <p>
         * See {@link Builder} for details on PathSpec string.
         * </p>
         *
         * @param pathSpecString the path spec string to include.  The pathInContext
         * is used to match against this path spec.
         * @return this builder.
         * @see #compressPathExclude(String)
         */
        public Builder compressPathInclude(String pathSpecString)
        {
            this.compressPaths.include(pathSpecString);
            return this;
        }

        /**
         * A {@code Content-Encoding} encoding to exclude.
         *
         * @param encoding the encoding to exclude
         * @return this builder
         */
        public Builder decompressEncodingExclude(String encoding)
        {
            this.decompressEncodings.exclude(encoding);
            return this;
        }

        /**
         * A {@code Content-Encoding} encoding to include.
         *
         * @param encoding the encoding to include
         * @return this builder
         */
        public Builder decompressEncodingInclude(String encoding)
        {
            this.decompressEncodings.include(encoding);
            return this;
        }

        /**
         * An HTTP method to exclude for Request decompression.
         *
         * @param method the method to exclude
         * @return this builder
         */
        public Builder decompressMethodExclude(String method)
        {
            this.decompressMethods.exclude(method);
            return this;
        }

        /**
         * An HTTP method to include for Request decompression.
         *
         * @param method the method to include
         * @return this builder
         */
        public Builder decompressMethodInclude(String method)
        {
            this.decompressMethods.include(method);
            return this;
        }

        /**
         * A non-compressed mimetype to exclude for Request decompression.
         *
         * <p>
         * The Request {@code Content-Type} is evaluated.
         * </p>
         *
         * @param mimetype the mimetype to exclude
         * @return this builder
         */
        public Builder decompressMimeTypeExclude(String mimetype)
        {
            this.decompressMimeTypes.exclude(mimetype);
            return this;
        }

        /**
         * A compressed mimetype to include for Request decompression.
         *
         * <p>
         * The request {@code Content-Type} is evaluated.
         * </p>
         *
         * @param mimetype the mimetype to include
         * @return this builder
         */
        public Builder decompressMimeTypeInclude(String mimetype)
        {
            this.decompressMimeTypes.include(mimetype);
            return this;
        }

        /**
         * A path that does not support request content decompression.
         *
         * <p>
         * See {@link Builder} for details on PathSpec string.
         * </p>
         *
         * @param pathSpecString the path spec string to exclude.  The pathInContext
         * is used to match against this path spec.
         * @return this builder.
         * @see #decompressPathInclude(String)
         */
        public Builder decompressPathExclude(String pathSpecString)
        {
            this.decompressPaths.exclude(pathSpecString);
            return this;
        }

        /**
         * A path that supports request content decompression.
         *
         * <p>
         * See {@link Builder} for details on PathSpec string.
         * </p>
         *
         * @param pathSpecString the path spec string to include.  The pathInContext
         * is used to match against this path spec.
         * @return this builder.
         * @see #decompressPathExclude(String)
         */
        public Builder decompressPathInclude(String pathSpecString)
        {
            this.decompressPaths.include(pathSpecString);
            return this;
        }

        /**
         * Setup MimeType exclusion and path exclusion from the provided {@link MimeTypes} configuration.
         *
         * @param mimeTypes the mime types to iterate.
         * @return this builder.
         */
        public Builder from(MimeTypes mimeTypes)
        {
            for (String type : mimeTypes.getMimeMap().values())
            {
                if ("image/svg+xml".equals(type))
                {
                    compressMimeTypeExclude(type);
                    decompressMimeTypeExclude(type);
                    compressPathExclude("*.svgz");
                    decompressPathExclude("*.svgz");
                }
                else if (type.startsWith("image/") ||
                    type.startsWith("audio/") ||
                    type.startsWith("video/"))
                {
                    compressMimeTypeExclude(type);
                    decompressMimeTypeExclude(type);
                }
            }

            Stream.of("application/compress",
                "application/zip",
                "application/gzip",
                "application/x-bzip2",
                "application/brotli",
                "application/x-br",
                "application/x-xz",
                "application/x-rar-compressed",
                "application/vnd.bzip3",
                "application/zstd",
                // It is possible to use SSE with CompressionHandler, but only if you use `gzip` encoding with syncFlush to true which will impact performance.
                "text/event-stream"
                ).forEach((type) ->
            {
                compressMimeTypeExclude(type);
                decompressMimeTypeExclude(type);
            });

            return this;
        }

        /**
         * Initialize builder with existing {@link CompressionConfig}
         *
         * @param config existing config to base builder off of
         * @return this builder.
         */
        public Builder from(CompressionConfig config)
        {
            this.compressEncodings.addAll(config.compressEncodings);
            this.decompressEncodings.addAll(config.decompressEncodings);
            this.compressMethods.addAll(config.compressMethods);
            this.decompressMethods.addAll(config.decompressMethods);
            this.compressMimeTypes.addAll(config.compressMimeTypes);
            this.decompressMimeTypes.addAll(config.decompressMimeTypes);
            this.compressPaths.addAll(config.compressPaths);
            this.decompressPaths.addAll(config.decompressPaths);
            this.vary = config.vary;
            return this;
        }

        /**
         * Specify the Response {@code Vary} header field to use.
         *
         * @param vary the {@code Vary} HTTP field to use.  If it is not an instance of {@link PreEncodedHttpField},
         * then it will be converted to one.
         * @return this builder
         */
        public Builder varyHeader(HttpField vary)
        {
            if (vary == null || (vary instanceof PreEncodedHttpField))
                this.vary = vary;
            else
                this.vary = new PreEncodedHttpField(vary.getHeader(), vary.getName(), vary.getValue());
            return this;
        }

        // TODO: preference order of compressions.
        // TODO: compression specific config (eg: compression level, strategy, etc)
        // TODO: dictionary support

        // TODO: Add configuration for decompression body size limit (to help with decompression bombs)
        // See: apache httpd mod_deflate DeflateInflateLimitRequestBody config
        // TODO: Add configuration for decompression ration burst / limit (to help with decompression bombs)
        // See: apache httpd mod_deflate DeflateInflateRatioBurst and DeflateInflateRatioLimit configs
    }
}
