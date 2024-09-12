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
     * Set of `Content-Encoding` encodings that are supported by this configuration.
     */
    private final IncludeExcludeSet<String, String> decompressEncodings;
    /**
     * Set of `Accept-Encoding` encodings that are supported by this configuration.
     */
    private final IncludeExcludeSet<String, String> compressEncodings;
    /**
     * Set of HTTP Methods that are supported by this configuration.
     */
    private final IncludeExcludeSet<String, String> methods;
    private final IncludeExcludeSet<String, String> mimetypes;
    private final IncludeExcludeSet<String, String> decompressPaths;
    private final IncludeExcludeSet<String, String> compressPaths;
    private final HttpField vary;

    private CompressionConfig(Builder builder)
    {
        this.decompressEncodings = builder.decompressEncodings.asImmutable();
        this.compressEncodings = builder.compressEncodings.asImmutable();
        this.methods = builder.methods.asImmutable();
        this.mimetypes = builder.mimetypes.asImmutable();
        this.decompressPaths = builder.decompressPaths.asImmutable();
        this.compressPaths = builder.compressPaths.asImmutable();
        this.vary = builder.vary;
    }

    public static Builder builder()
    {
        return new Builder();
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

        // TODO: add testcase for `Accept-Encoding: *`
        for (String encoding : requestAcceptEncoding)
        {
            if (compressEncodings.test(encoding))
            {
                matchedEncoding = encoding;
            }
        }

        if (matchedEncoding == null)
            return null;

        if (!isMethodSupported(request.getMethod()))
            return null;

        return matchedEncoding;
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

        if (!isMethodSupported(request.getMethod()))
            return null;

        // TODO: testing mime-type is really a response test, not a request test.
        if (!isMimeTypeCompressible(request.getContext().getMimeTypes(), pathInContext))
            return null;

        return matchedEncoding;
    }

    /**
     * Get the set of excluded HTTP methods
     *
     * @return the set of excluded HTTP methods
     * @see #getMethodIncludes()
     */
    @ManagedAttribute("Set of HTTP Method Exclusions")
    public Set<String> getMethodExcludes()
    {
        Set<String> excluded = methods.getExcluded();
        return Collections.unmodifiableSet(excluded);
    }

    /**
     * Get the set of included HTTP methods
     *
     * @return the set of included HTTP methods
     * @see #getMethodExcludes()
     */
    @ManagedAttribute("Set of HTTP Method Inclusions")
    public Set<String> getMethodIncludes()
    {
        Set<String> includes = methods.getIncluded();
        return Collections.unmodifiableSet(includes);
    }

    /**
     * Get the set of excluded MIME types
     *
     * @return the set of excluded MIME types
     * @see #getMimeTypeIncludes()
     */
    @ManagedAttribute("Set of Mime Type Exclusions")
    public Set<String> getMimeTypeExcludes()
    {
        Set<String> excluded = mimetypes.getExcluded();
        return Collections.unmodifiableSet(excluded);
    }

    /**
     * Get the set of included MIME types
     *
     * @return the filter list of included MIME types
     * @see #getMimeTypeExcludes()
     */
    @ManagedAttribute("Set of Mime Type Inclusions")
    public Set<String> getMimeTypeIncludes()
    {
        Set<String> includes = mimetypes.getIncluded();
        return Collections.unmodifiableSet(includes);
    }

    /**
     * @return The VARY field to use.
     */
    public HttpField getVary()
    {
        return vary;
    }

    public boolean isMethodSupported(String method)
    {
        return methods.test(method);
    }

    public boolean isMimeTypeCompressible(MimeTypes mimeTypes, String pathInContext)
    {
        // Exclude non-compressible mime-types known from URI extension
        String mimeType = mimeTypes.getMimeByExtension(pathInContext);
        if (mimeType != null)
        {
            mimeType = HttpField.getValueParameters(mimeType, null);
            return isMimeTypeCompressible(mimeType);
        }
        return true;
    }

    public boolean isMimeTypeCompressible(String mimeType)
    {
        return mimetypes.test(mimeType);
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
         * Set of `Content-Encoding` encodings that are supported by this configuration.
         */
        private final IncludeExclude<String> decompressEncodings = new IncludeExclude<>();
        /**
         * Set of `Accept-Encoding` encodings that are supported by this configuration.
         */
        private final IncludeExclude<String> compressEncodings = new IncludeExclude<>();

        private final IncludeExclude<String> methods = new IncludeExclude<>();
        private final IncludeExclude<String> mimetypes = new IncludeExclude<>(AsciiLowerCaseSet.class);
        private final IncludeExclude<String> decompressPaths = new IncludeExclude<>(PathSpecSet.class);
        private final IncludeExclude<String> compressPaths = new IncludeExclude<>(PathSpecSet.class);
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
         * Initialize builder with existing {@link CompressionConfig}
         *
         * @param config existing config to base builder off of
         * @return this builder.
         */
        public Builder from(CompressionConfig config)
        {
            this.decompressEncodings.addAll(config.decompressEncodings);
            this.decompressPaths.addAll(config.decompressPaths);
            this.compressEncodings.addAll(config.compressEncodings);
            this.compressPaths.addAll(config.compressPaths);
            this.methods.addAll(config.methods);
            this.mimetypes.addAll(config.mimetypes);
            this.vary = config.vary;
            return this;
        }

        /**
         * An HTTP method to exclude.
         *
         * @param method the method to exclude
         * @return this builder
         */
        public Builder methodExclude(String method)
        {
            this.methods.exclude(method);
            return this;
        }

        /**
         * An HTTP method to include.
         *
         * @param method the method to include
         * @return this builder
         */
        public Builder methodInclude(String method)
        {
            this.methods.include(method);
            return this;
        }

        /**
         * A non-compressible mimetype to exclude.
         *
         * <p>
         * The response {@code Content-Type} is evaluated.
         * </p>
         *
         * @param mimetype the mimetype to exclude
         * @return this builder
         */
        public Builder mimeTypeExclude(String mimetype)
        {
            this.mimetypes.exclude(mimetype);
            return this;
        }

        /**
         * A compressible mimetype to include.
         *
         * <p>
         * The response {@code Content-Type} is evaluated.
         * </p>
         *
         * @param mimetype the mimetype to include
         * @return this builder
         */
        public Builder mimeTypeInclude(String mimetype)
        {
            this.mimetypes.include(mimetype);
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
