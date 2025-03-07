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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.QuotedQualityCSV;
import org.eclipse.jetty.http.pathmap.PathSpecSet;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.AsciiLowerCaseSet;
import org.eclipse.jetty.util.IncludeExclude;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;

/**
 * <p>Configuration for a specific compression behavior per matching path from the {@link CompressionHandler}.</p>
 * <p>Configuration is split between compression (of responses) and decompression (of requests).</p>
 */
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
    /**
     * Optional preferred order of encoders for compressing Response content.
     */
    private final List<String> preferredCompressEncodings;

    private CompressionConfig(Builder builder)
    {
        this.preferredCompressEncodings = Collections.unmodifiableList(builder.compressPreferredEncodings);
        this.compressEncodings = builder.compressEncodings.asImmutable();
        this.decompressEncodings = builder.decompressEncodings.asImmutable();
        this.compressMethods = builder.compressMethods.asImmutable();
        this.decompressMethods = builder.decompressMethods.asImmutable();
        this.compressMimeTypes = builder.compressMimeTypes.asImmutable();
        this.decompressMimeTypes = builder.decompressMimeTypes.asImmutable();
        this.compressPaths = builder.compressPaths.asImmutable();
        this.decompressPaths = builder.decompressPaths.asImmutable();
    }

    /**
     * @return a new {@link Builder} to configure a {@code CompressionConfig} instance
     */
    public static Builder builder()
    {
        return new Builder();
    }

    /**
     * @return the encodings that disable response compression
     * @see #getCompressIncludeEncodings()
     */
    @ManagedAttribute("Encodings that disable response compression")
    public Set<String> getCompressExcludeEncodings()
    {
        return compressEncodings.getExcluded();
    }

    /**
     * @return the HTTP methods that disable response compression
     * @see #getCompressIncludeMethods()
     */
    @ManagedAttribute("HTTP methods that disable response compression")
    public Set<String> getCompressExcludeMethods()
    {
        return compressMethods.getExcluded();
    }

    /**
     * @return the MIME types that disable response compression
     * @see #getCompressIncludeMimeTypes()
     */
    @ManagedAttribute("MIME types that disable response compression")
    public Set<String> getCompressExcludeMimeTypes()
    {
        return compressMimeTypes.getExcluded();
    }

    /**
     * @return the path specs that exclude response compression
     * @see #getCompressIncludePaths()
     */
    @ManagedAttribute("Path specs that exclude response compression")
    public Set<String> getCompressExcludePaths()
    {
        return compressPaths.getExcluded();
    }

    /**
     * @return the encodings that enable response compression
     * @see #getCompressExcludeEncodings()
     */
    @ManagedAttribute("Encodings that enable response compression")
    public Set<String> getCompressIncludeEncodings()
    {
        return compressEncodings.getIncluded();
    }

    /**
     * @return HTTP methods that enable response compression
     * @see #getCompressExcludeMethods()
     */
    @ManagedAttribute("HTTP methods that enable response compression")
    public Set<String> getCompressIncludeMethods()
    {
        return compressMethods.getIncluded();
    }

    /**
     * @return the MIME types that enable response compression
     * @see #getCompressExcludeMimeTypes()
     */
    @ManagedAttribute("MIME types that enable response compression")
    public Set<String> getCompressIncludeMimeTypes()
    {
        return compressMimeTypes.getIncluded();
    }

    /**
     * @return the path specs that enable response compression
     * @see #getCompressExcludePaths()
     */
    @ManagedAttribute("Path specs that enable response compression")
    public Set<String> getCompressIncludePaths()
    {
        return compressPaths.getIncluded();
    }

    /**
     * @return the encodings for response compression in preferred order
     */
    @ManagedAttribute("Encodings for response compression in preferred order")
    public List<String> getCompressPreferredEncodings()
    {
        return preferredCompressEncodings;
    }

    String getCompressionEncoding(Set<String> supportedEncodings, Request request, List<QuotedQualityCSV.QualityValue> requestAcceptEncoding, String pathInContext)
    {
        if (requestAcceptEncoding.isEmpty())
            return null;

        if (!isCompressMethodSupported(request.getMethod()))
            return null;

        // MIME types checks are performed later in CompressionResponse.

        if (!compressPaths.test(pathInContext))
            return null;

        List<String> matches = new ArrayList<>();
        QuotedQualityCSV.QualityValue star = null;
        QuotedQualityCSV.QualityValue identity = null;
        for (QuotedQualityCSV.QualityValue qualityValue : requestAcceptEncoding)
        {
            String value = qualityValue.getValue();
            if ("*".equals(value))
            {
                star = qualityValue;
                continue;
            }
            if ("identity".equalsIgnoreCase(value))
            {
                identity = qualityValue;
                continue;
            }
            if (!qualityValue.isAcceptable())
                continue;
            if (!supportedEncodings.contains(value))
                continue;
            if (compressEncodings.test(value))
                matches.add(value);
        }

        List<String> preferred = getCompressPreferredEncodings();

        if (matches.isEmpty())
        {
            // Try a default encoding if possible.
            if (star != null && star.isAcceptable())
            {
                String candidate;
                if (preferred.isEmpty())
                    candidate = supportedEncodings.stream().findFirst().orElse(null);
                else
                    candidate = preferred.stream().filter(supportedEncodings::contains).findFirst().orElse(null);
                if (!compressEncodings.test(candidate))
                    candidate = null;
                if (candidate != null)
                    return candidate;
            }

            // The only option left is identity, if acceptable.
            if (identity != null && !identity.isAcceptable())
            {
                List<String> accepted = supportedEncodings.stream().filter(compressEncodings).toList();
                throw new HttpException.RuntimeException(HttpStatus.UNSUPPORTED_MEDIA_TYPE_415, String.join(", ", accepted));
            }

            // Identity is acceptable.
            return null;
        }

        // Only one match.
        if (matches.size() == 1)
            return matches.get(0);

        // Multiple matches, return most preferred, if any.
        return preferred.stream()
            .filter(matches::contains)
            .findFirst()
            .orElse(matches.get(0));
    }

    /**
     * @return the HTTP methods that disable request decompression
     * @see #getDecompressIncludeMethods()
     */
    @ManagedAttribute("HTTP methods that disable request decompression")
    public Set<String> getDecompressExcludeMethods()
    {
        return decompressMethods.getExcluded();
    }

    /**
     * @return the path specs that disable request decompression
     * @see #getDecompressIncludePaths()
     */
    @ManagedAttribute("Path specs that disable request decompression")
    public Set<String> getDecompressExcludePaths()
    {
        return decompressPaths.getExcluded();
    }

    /**
     * @return the HTTP methods that enable request decompression
     * @see #getDecompressExcludeMethods()
     */
    @ManagedAttribute("HTTP methods that enable request decompression")
    public Set<String> getDecompressIncludeMethods()
    {
        return decompressMethods.getIncluded();
    }

    /**
     * @return the path specs that enable request decompression
     * @see #getDecompressExcludePaths()
     */
    @ManagedAttribute("Path specs that enable request decompression")
    public Set<String> getDecompressIncludePaths()
    {
        return decompressPaths.getIncluded();
    }

    String getDecompressionEncoding(Set<String> supportedEncodings, Request request, String requestContentEncoding, String pathInContext)
    {
        if (requestContentEncoding == null)
            return null;

        if (!supportedEncodings.contains(requestContentEncoding))
            return null;

        if (!decompressEncodings.test(requestContentEncoding))
            return null;

        if (!isDecompressMethodSupported(request.getMethod()))
            return null;

        String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
        if (!isDecompressMimeTypeSupported(contentType))
            return null;

        if (!decompressPaths.test(pathInContext))
            return null;

        return requestContentEncoding;
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
     * <p>The builder of {@link CompressionConfig} immutable instances.</p>
     * <p><em>Notes about PathSpec strings</em></p>
     * <p>There are 2 syntaxes supported, Servlet {@code url-pattern} based,
     * and regex based.</p>
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
     * <p>For all properties, exclusion takes precedence over inclusion,
     * as defined by {@link IncludeExcludeSet}.</p>
     */
    public static class Builder
    {
        private final IncludeExclude<String> decompressEncodings = new IncludeExclude<>();
        private final IncludeExclude<String> compressEncodings = new IncludeExclude<>();
        private final IncludeExclude<String> decompressMethods = new IncludeExclude<>();
        private final IncludeExclude<String> compressMethods = new IncludeExclude<>();
        private final IncludeExclude<String> decompressPaths = new IncludeExclude<>(PathSpecSet.class);
        private final IncludeExclude<String> compressPaths = new IncludeExclude<>(PathSpecSet.class);
        private final IncludeExclude<String> compressMimeTypes = new IncludeExclude<>(AsciiLowerCaseSet.class);
        private final IncludeExclude<String> decompressMimeTypes = new IncludeExclude<>(AsciiLowerCaseSet.class);
        private final List<String> compressPreferredEncodings = new ArrayList<>();

        private Builder()
        {
            // Use the static builder() method instead.
        }

        /**
         * @param encoding the encoding to exclude for response compression.
         * @return this builder
         */
        public Builder compressExcludeEncoding(String encoding)
        {
            this.compressEncodings.exclude(encoding);
            return this;
        }

        /**
         * @param method the HTTP method to exclude for response compression
         * @return this builder
         */
        public Builder compressExcludeMethod(String method)
        {
            this.compressMethods.exclude(method);
            return this;
        }

        /**
         * @param mimetype the MIME type to exclude for response compression
         * @return this builder
         */
        public Builder compressExcludeMimeType(String mimetype)
        {
            this.compressMimeTypes.exclude(mimetype);
            return this;
        }

        /**
         * <p>A path spec to exclude for response compression.</p>
         * <p>The path spec is matched against {@link Request#getPathInContext(Request)}.</p>
         *
         * @param pathSpecString the path spec to exclude for response compression.
         * @return this builder
         * @see #compressIncludePath(String)
         */
        public Builder compressExcludePath(String pathSpecString)
        {
            this.compressPaths.exclude(pathSpecString);
            return this;
        }

        /**
         * @param encoding the encoding to include for response compression.
         * @return this builder
         */
        public Builder compressIncludeEncoding(String encoding)
        {
            this.compressEncodings.include(encoding);
            return this;
        }

        /**
         * @param method the HTTP method to include for response compression
         * @return this builder
         */
        public Builder compressIncludeMethod(String method)
        {
            this.compressMethods.include(method);
            return this;
        }

        /**
         * @param mimetype the MIME type to include for response compression
         * @return this builder
         */
        public Builder compressIncludeMimeType(String mimetype)
        {
            this.compressMimeTypes.include(mimetype);
            return this;
        }

        /**
         * <p>A path spec to include for response compression.</p>
         * <p>The path spec is matched against {@link Request#getPathInContext(Request)}.</p>
         *
         * @param pathSpecString the path spec to include for response compression.
         * @return this builder
         * @see #compressExcludePath(String)
         */
        public Builder compressIncludePath(String pathSpecString)
        {
            this.compressPaths.include(pathSpecString);
            return this;
        }

        /**
         * <p>Specifies a list of encodings for response compression in preferred order.</p>
         * <p>This list is only used when {@link CompressionHandler} computes more
         * than one candidate content encoding for response compression.
         * This happens only when {@code Accept-Encoding} specifies more than
         * one encoding, and they are all supported by the server, or when
         * {@code Accept-Encoding} specifies the token {@code *} and the server
         * supports more than one encoding.</p>
         *
         * @param encodings a list of encodings for response compression in preferred order
         * @return this builder
         */
        public Builder compressPreferredEncodings(List<String> encodings)
        {
            this.compressPreferredEncodings.clear();
            if (encodings != null)
                this.compressPreferredEncodings.addAll(encodings);
            return this;
        }

        /**
         * @param encoding the encoding to exclude for request decompression
         * @return this builder
         */
        public Builder decompressExcludeEncoding(String encoding)
        {
            this.decompressEncodings.exclude(encoding);
            return this;
        }

        /**
         * @param method the HTTP method to exclude for request decompression
         * @return this builder
         */
        public Builder decompressExcludeMethod(String method)
        {
            this.decompressMethods.exclude(method);
            return this;
        }

        /**
         * @param mimetype the MIME type to exclude for request decompression
         * @return this builder
         */
        public Builder decompressExcludeMimeType(String mimetype)
        {
            this.decompressMimeTypes.exclude(mimetype);
            return this;
        }

        /**
         * <p>A path spec to exclude for request decompression.</p>
         * <p>The path spec is matched against {@link Request#getPathInContext(Request)}.</p>
         *
         * @param pathSpecString the path spec to exclude for request decompression
         * @return this builder
         * @see #decompressIncludePath(String)
         */
        public Builder decompressExcludePath(String pathSpecString)
        {
            this.decompressPaths.exclude(pathSpecString);
            return this;
        }

        /**
         * @param encoding the encoding to include for request decompression
         * @return this builder
         */
        public Builder decompressIncludeEncoding(String encoding)
        {
            this.decompressEncodings.include(encoding);
            return this;
        }

        /**
         * @param method the HTTP method to include for request decompression
         * @return this builder
         */
        public Builder decompressIncludeMethod(String method)
        {
            this.decompressMethods.include(method);
            return this;
        }

        /**
         * @param mimetype the MIME type to include for request decompression
         * @return this builder
         */
        public Builder decompressIncludeMimeType(String mimetype)
        {
            this.decompressMimeTypes.include(mimetype);
            return this;
        }

        /**
         * <p>A path spec to include for request decompression.</p>
         * <p>The path spec is matched against {@link Request#getPathInContext(Request)}.</p>
         *
         * @param pathSpecString the path spec to include for request decompression
         * @return this builder
         * @see #decompressExcludePath(String)
         */
        public Builder decompressIncludePath(String pathSpecString)
        {
            this.decompressPaths.include(pathSpecString);
            return this;
        }

        /**
         * <p>Configures this {@code Builder} with the default configuration.</p>
         * <p>Additional configuration may be specified using the {@code Builder}
         * methods, possibly overriding the defaults.</p>
         *
         * @return this builder
         */
        public Builder defaults()
        {
            for (String type : MimeTypes.DEFAULTS.getMimeMap().values())
            {
                if ("image/svg+xml".equals(type))
                {
                    compressExcludeMimeType(type);
                    decompressExcludeMimeType(type);
                    compressExcludePath("*.svgz");
                    decompressExcludePath("*.svgz");
                }
                else if (type.startsWith("image/") ||
                         type.startsWith("audio/") ||
                         type.startsWith("video/"))
                {
                    compressExcludeMimeType(type);
                    decompressExcludeMimeType(type);
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
                // It is possible to use SSE with CompressionHandler, but only if you use
                // `gzip` encoding with syncFlush to true which will impact performance.
                "text/event-stream"
            ).forEach((type) ->
            {
                compressExcludeMimeType(type);
                decompressExcludeMimeType(type);
            });

            decompressIncludeMethod(HttpMethod.POST.asString());

            compressIncludeMethod(HttpMethod.GET.asString());
            compressIncludeMethod(HttpMethod.POST.asString());

            return this;
        }

        /**
         * @return a new {@link CompressionConfig} instance configured with this {@code Builder}.
         */
        public CompressionConfig build()
        {
            return new CompressionConfig(this);
        }

        // TODO: compression specific config (eg: compression level, strategy, etc)
        // TODO: dictionary support

        // TODO: Add configuration for decompression body size limit (to help with decompression bombs)
        // See: apache httpd mod_deflate DeflateInflateLimitRequestBody config
        // TODO: Add configuration for decompression ration burst / limit (to help with decompression bombs)
        // See: apache httpd mod_deflate DeflateInflateRatioBurst and DeflateInflateRatioLimit configs
    }
}
