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

package org.eclipse.jetty.compress;

import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.zip.Deflater;

import org.eclipse.jetty.http.EtagUtils;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.pathmap.PathSpecSet;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.gzip.GzipRequest;
import org.eclipse.jetty.server.handler.gzip.GzipResponseAndCallback;
import org.eclipse.jetty.util.AsciiLowerCaseSet;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IncludeExclude;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.Destroyable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DynamicCompressHandler extends Handler.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(DynamicCompressHandler.class);

    private static final int MIN_FUNCTIONAL_SIZE = 23;
    private static final int DEFAULT_MIN_COMPRESS_SIZE = 32;
    private int compressSizeMinimum = DEFAULT_MIN_COMPRESS_SIZE;
    private int decompressBufferSize = -1;

    private boolean syncFlush = false;
    private final IncludeExclude<String> methods = new IncludeExclude<>();
    private final IncludeExclude<String> inflatePaths = new IncludeExclude<>(PathSpecSet.class);
    private final IncludeExclude<String> paths = new IncludeExclude<>(PathSpecSet.class);
    private final IncludeExclude<String> mimeTypes = new IncludeExclude<>(AsciiLowerCaseSet.class);
    private HttpField vary = new PreEncodedHttpField(HttpHeader.VARY, HttpHeader.ACCEPT_ENCODING.asString());

    /**
     * @return The VARY field to use.
     */
    public HttpField getVary()
    {
        return vary;
    }

    /**
     * @param vary The VARY field to use. If it is not an instance of {@link PreEncodedHttpField},
     *             then it will be converted to one.
     */
    public void setVary(HttpField vary)
    {
        if (isRunning())
            throw new IllegalStateException(getState());

        if (vary == null || (vary instanceof PreEncodedHttpField))
            this.vary = vary;
        else
            this.vary = new PreEncodedHttpField(vary.getHeader(), vary.getName(), vary.getValue());
    }

    /**
     * Add excluded to the HTTP methods filtering.
     *
     * @param methods The methods to exclude in compression
     * @see #addIncludedMethods(String...)
     */
    public void addExcludedMethods(String... methods)
    {
        for (String m : methods)
        {
            this.methods.exclude(m);
        }
    }

    /**
     * Adds excluded MIME types for response filtering.
     *
     * <p>
     * <em>Deprecation Warning: </em>
     * For backward compatibility the MIME types parameters may be comma separated strings,
     * but this will not be supported in future versions of Jetty.
     * </p>
     *
     * @param types The mime types to exclude (without charset or other parameters).
     * @see #addIncludedMimeTypes(String...)
     * @deprecated review "backward compatibility" here.
     */
    @Deprecated // TODO review "backward compatibility" here. (use of csvSplit with var-args)
    public void addExcludedMimeTypes(String... types)
    {
        for (String t : types)
        {
            mimeTypes.exclude(StringUtil.csvSplit(t));
        }
    }

    /**
     * Adds excluded Path Specs for request filtering.
     *
     * <p>
     * There are 2 syntaxes supported, Servlet <code>url-pattern</code> based, and
     * Regex based.  This means that the initial characters on the path spec
     * line are very strict, and determine the behavior of the path matching.
     * <ul>
     * <li>If the spec starts with <code>'^'</code> the spec is assumed to be
     * a regex based path spec and will match with normal Java regex rules.</li>
     * <li>If the spec starts with <code>'/'</code> then spec is assumed to be
     * a Servlet url-pattern rules path spec for either an exact match
     * or prefix based match.</li>
     * <li>If the spec starts with <code>'*.'</code> then spec is assumed to be
     * a Servlet url-pattern rules path spec for a suffix based match.</li>
     * <li>All other syntaxes are unsupported</li>
     * </ul>
     * <p>
     * Note: inclusion takes precedence over exclude.
     *
     * @param pathspecs Path specs (as per servlet spec) to exclude. If a
     * ServletContext is available, the paths are relative to the context path,
     * otherwise they are absolute.<br>
     * For backward compatibility the pathspecs may be comma separated strings, but this
     * will not be supported in future versions.
     * @see #addIncludedPaths(String...)
     * @deprecated review "backward compatibility" here.
     */
    @Deprecated // TODO review "backward compatibility" here. (use of csvSplit with var-args)
    public void addExcludedPaths(String... pathspecs)
    {
        for (String p : pathspecs)
        {
            paths.exclude(StringUtil.csvSplit(p));
        }
    }

    /**
     * Adds excluded Path Specs for request filtering on request inflation.
     *
     * <p>
     * There are 2 syntaxes supported, Servlet <code>url-pattern</code> based, and
     * Regex based.  This means that the initial characters on the path spec
     * line are very strict, and determine the behavior of the path matching.
     * <ul>
     * <li>If the spec starts with <code>'^'</code> the spec is assumed to be
     * a regex based path spec and will match with normal Java regex rules.</li>
     * <li>If the spec starts with <code>'/'</code> then spec is assumed to be
     * a Servlet url-pattern rules path spec for either an exact match
     * or prefix based match.</li>
     * <li>If the spec starts with <code>'*.'</code> then spec is assumed to be
     * a Servlet url-pattern rules path spec for a suffix based match.</li>
     * <li>All other syntaxes are unsupported</li>
     * </ul>
     * <p>
     * Note: inclusion takes precedence over exclude.
     *
     * @param pathspecs Path specs (as per servlet spec) to exclude. If a
     * ServletContext is available, the paths are relative to the context path,
     * otherwise they are absolute.<br>
     * For backward compatibility the pathspecs may be comma separated strings, but this
     * will not be supported in future versions.
     * @see #addIncludedInflationPaths(String...)
     * @deprecated review "backward compatibility" here.
     */
    @Deprecated // TODO review "backward compatibility" here. (use of csvSplit with var-args)
    public void addExcludedInflationPaths(String... pathspecs)
    {
        for (String p : pathspecs)
        {
            inflatePaths.exclude(StringUtil.csvSplit(p));
        }
    }

    /**
     * Adds included HTTP Methods (eg: POST, PATCH, DELETE) for filtering.
     *
     * @param methods The HTTP methods to include in compression.
     * @see #addExcludedMethods(String...)
     */
    public void addIncludedMethods(String... methods)
    {
        for (String m : methods)
        {
            this.methods.include(m);
        }
    }

    /**
     * Is the {@link Deflater} running {@link Deflater#SYNC_FLUSH} or not.
     *
     * @return True if {@link Deflater#SYNC_FLUSH} is used, else {@link Deflater#NO_FLUSH}
     * @see #setSyncFlush(boolean)
     */
    public boolean isSyncFlush()
    {
        return syncFlush;
    }

    /**
     * Set the {@link Deflater} flush mode to use.  {@link Deflater#SYNC_FLUSH}
     * should be used if the application wishes to stream the data, but this may
     * hurt compression performance.
     *
     * @param syncFlush True if {@link Deflater#SYNC_FLUSH} is used, else {@link Deflater#NO_FLUSH}
     * @see #isSyncFlush()
     */
    public void setSyncFlush(boolean syncFlush)
    {
        this.syncFlush = syncFlush;
    }

    /**
     * Add included MIME types for response filtering
     *
     * @param types The mime types to include (without charset or other parameters)
     * For backward compatibility the mimetypes may be comma separated strings, but this
     * will not be supported in future versions.
     * @see #addExcludedMimeTypes(String...)
     * @deprecated review "backward compatibility" here.
     */
    @Deprecated // TODO review "backward compatibility" here. (use of csvSplit with var-args)
    public void addIncludedMimeTypes(String... types)
    {
        for (String t : types)
        {
            mimeTypes.include(StringUtil.csvSplit(t));
        }
    }

    /**
     * Add included Path Specs for filtering.
     *
     * <p>
     * There are 2 syntaxes supported, Servlet <code>url-pattern</code> based, and
     * Regex based.  This means that the initial characters on the path spec
     * line are very strict, and determine the behavior of the path matching.
     * <ul>
     * <li>If the spec starts with <code>'^'</code> the spec is assumed to be
     * a regex based path spec and will match with normal Java regex rules.</li>
     * <li>If the spec starts with <code>'/'</code> then spec is assumed to be
     * a Servlet url-pattern rules path spec for either an exact match
     * or prefix based match.</li>
     * <li>If the spec starts with <code>'*.'</code> then spec is assumed to be
     * a Servlet url-pattern rules path spec for a suffix based match.</li>
     * <li>All other syntaxes are unsupported</li>
     * </ul>
     * <p>
     * Note: inclusion takes precedence over exclusion.
     *
     * @param pathspecs Path specs (as per servlet spec) to include. If a
     * ServletContext is available, the paths are relative to the context path,
     * otherwise they are absolute
     * @deprecated review "backward compatibility" here.
     */
    @Deprecated // TODO review "backward compatibility" here. (use of csvSplit with var-args)
    public void addIncludedPaths(String... pathspecs)
    {
        for (String p : pathspecs)
        {
            paths.include(StringUtil.csvSplit(p));
        }
    }

    /**
     * Add included Path Specs for filtering on request inflation.
     *
     * <p>
     * There are 2 syntaxes supported, Servlet <code>url-pattern</code> based, and
     * Regex based.  This means that the initial characters on the path spec
     * line are very strict, and determine the behavior of the path matching.
     * <ul>
     * <li>If the spec starts with <code>'^'</code> the spec is assumed to be
     * a regex based path spec and will match with normal Java regex rules.</li>
     * <li>If the spec starts with <code>'/'</code> then spec is assumed to be
     * a Servlet url-pattern rules path spec for either an exact match
     * or prefix based match.</li>
     * <li>If the spec starts with <code>'*.'</code> then spec is assumed to be
     * a Servlet url-pattern rules path spec for a suffix based match.</li>
     * <li>All other syntaxes are unsupported</li>
     * </ul>
     * <p>
     * Note: inclusion takes precedence over exclusion.
     *
     * @param pathspecs Path specs (as per servlet spec) to include. If a
     * ServletContext is available, the paths are relative to the context path,
     * otherwise they are absolute
     * @deprecated review "backward compatibility" here.
     */
    @Deprecated // TODO review "backward compatibility" here. (use of csvSplit with var-args)
    public void addIncludedInflationPaths(String... pathspecs)
    {
        for (String p : pathspecs)
        {
            inflatePaths.include(StringUtil.csvSplit(p));
        }
    }

    /**
     * Get the current filter list of excluded HTTP methods
     *
     * @return the filter list of excluded HTTP methods
     * @see #getIncludedMethods()
     */
    public String[] getExcludedMethods()
    {
        Set<String> excluded = methods.getExcluded();
        return excluded.toArray(new String[0]);
    }

    /**
     * Get the current filter list of excluded MIME types
     *
     * @return the filter list of excluded MIME types
     * @see #getIncludedMimeTypes()
     */
    public String[] getExcludedMimeTypes()
    {
        Set<String> excluded = mimeTypes.getExcluded();
        return excluded.toArray(new String[0]);
    }

    /**
     * Get the current filter list of excluded Path Specs
     *
     * @return the filter list of excluded Path Specs
     * @see #getIncludedPaths()
     */
    public String[] getExcludedPaths()
    {
        Set<String> excluded = paths.getExcluded();
        return excluded.toArray(new String[0]);
    }

    /**
     * Get the current filter list of excluded Path Specs for request inflation.
     *
     * @return the filter list of excluded Path Specs
     * @see #getIncludedInflationPaths()
     */
    public String[] getExcludedInflationPaths()
    {
        Set<String> excluded = inflatePaths.getExcluded();
        return excluded.toArray(new String[0]);
    }

    /**
     * Get the current filter list of included HTTP Methods
     *
     * @return the filter list of included HTTP methods
     * @see #getExcludedMethods()
     */
    public String[] getIncludedMethods()
    {
        Set<String> includes = methods.getIncluded();
        return includes.toArray(new String[0]);
    }

    /**
     * Get the current filter list of included MIME types
     *
     * @return the filter list of included MIME types
     * @see #getExcludedMimeTypes()
     */
    public String[] getIncludedMimeTypes()
    {
        Set<String> includes = mimeTypes.getIncluded();
        return includes.toArray(new String[0]);
    }

    /**
     * Get the current filter list of included Path Specs
     *
     * @return the filter list of included Path Specs
     * @see #getExcludedPaths()
     */
    public String[] getIncludedPaths()
    {
        Set<String> includes = paths.getIncluded();
        return includes.toArray(new String[0]);
    }

    /**
     * Get the current filter list of included Path Specs for request inflation.
     *
     * @return the filter list of included Path Specs
     * @see #getExcludedInflationPaths()
     */
    public String[] getIncludedInflationPaths()
    {
        Set<String> includes = inflatePaths.getIncluded();
        return includes.toArray(new String[0]);
    }

    /**
     * Get the minimum size, in bytes, that a response {@code Content-Length} must be
     * before compression will trigger.
     *
     * @return minimum response size (in bytes) that triggers compression
     * @see #setMinGzipSize(int)
     */
    public int getCompressSizeMinimum()
    {
        return compressSizeMinimum;
    }

    /**
     * Get the size (in bytes) of the {@link java.util.zip.Inflater} buffer used to inflate
     * compressed requests.
     *
     * @return size in bytes of the buffer, or 0 for no inflation.
     */
    public int getInflateBufferSize()
    {
        return decompressBufferSize;
    }

    /**
     * Set the size (in bytes) of the {@link java.util.zip.Inflater} buffer used to inflate comrpessed requests.
     *
     * @param size size in bytes of the buffer, or 0 for no inflation.
     */
    public void setInflateBufferSize(int size)
    {
        decompressBufferSize = size;
    }

    /**
     * Set the excluded filter list of HTTP methods (replacing any previously set)
     *
     * @param methods the HTTP methods to exclude
     * @see #setIncludedMethods(String...)
     */
    public void setExcludedMethods(String... methods)
    {
        this.methods.getExcluded().clear();
        this.methods.exclude(methods);
    }

    /**
     * Set the excluded filter list of MIME types (replacing any previously set)
     *
     * @param types The mime types to exclude (without charset or other parameters)
     * @see #setIncludedMimeTypes(String...)
     */
    public void setExcludedMimeTypes(String... types)
    {
        mimeTypes.getExcluded().clear();
        mimeTypes.exclude(types);
    }

    /**
     * Set the excluded filter list of MIME types (replacing any previously set)
     *
     * @param csvTypes The list of mime types to exclude (without charset or other parameters), CSV format
     * @see #setIncludedMimeTypesList(String)
     */
    public void setExcludedMimeTypesList(String csvTypes)
    {
        setExcludedMimeTypes(StringUtil.csvSplit(csvTypes));
    }

    /**
     * Set the excluded filter list of Path specs (replacing any previously set)
     *
     * @param pathspecs Path specs (as per servlet spec) to exclude. If a
     * ServletContext is available, the paths are relative to the context path,
     * otherwise they are absolute.
     * @see #setIncludedPaths(String...)
     */
    public void setExcludedPaths(String... pathspecs)
    {
        paths.getExcluded().clear();
        paths.exclude(pathspecs);
    }

    /**
     * Set the excluded filter list of Path specs (replacing any previously set)
     *
     * @param pathspecs Path specs (as per servlet spec) to exclude from inflation. If a
     * ServletContext is available, the paths are relative to the context path,
     * otherwise they are absolute.
     * @see #setIncludedInflatePaths(String...)
     */
    public void setExcludedInflatePaths(String... pathspecs)
    {
        inflatePaths.getExcluded().clear();
        inflatePaths.exclude(pathspecs);
    }

    /**
     * Set the included filter list of HTTP methods (replacing any previously set)
     *
     * @param methods The methods to include in compression
     * @see #setExcludedMethods(String...)
     */
    public void setIncludedMethods(String... methods)
    {
        this.methods.getIncluded().clear();
        this.methods.include(methods);
    }

    /**
     * Set the included filter list of MIME types (replacing any previously set)
     *
     * @param types The mime types to include (without charset or other parameters)
     * @see #setExcludedMimeTypes(String...)
     */
    public void setIncludedMimeTypes(String... types)
    {
        mimeTypes.getIncluded().clear();
        mimeTypes.include(types);
    }

    /**
     * Set the included filter list of MIME types (replacing any previously set)
     *
     * @param csvTypes The list of mime types to include (without charset or other parameters), CSV format
     * @see #setExcludedMimeTypesList(String)
     */
    public void setIncludedMimeTypesList(String csvTypes)
    {
        setIncludedMimeTypes(StringUtil.csvSplit(csvTypes));
    }

    /**
     * Set the included filter list of Path specs (replacing any previously set)
     *
     * @param pathspecs Path specs (as per servlet spec) to include. If a
     * ServletContext is available, the paths are relative to the context path,
     * otherwise they are absolute
     * @see #setExcludedPaths(String...)
     */
    public void setIncludedPaths(String... pathspecs)
    {
        paths.getIncluded().clear();
        paths.include(pathspecs);
    }

    /**
     * Set the included filter list of Path specs (replacing any previously set)
     *
     * @param pathspecs Path specs (as per servlet spec) to include for inflation. If a
     * ServletContext is available, the paths are relative to the context path,
     * otherwise they are absolute
     * @see #setExcludedInflatePaths(String...)
     */
    public void setIncludedInflatePaths(String... pathspecs)
    {
        inflatePaths.getIncluded().clear();
        inflatePaths.include(pathspecs);
    }

    /**
     * Set the minimum response size to trigger dynamic compression.
     *
     * @param minGzipSize minimum response size in bytes (not allowed to be lower then {@code 23}, as the various
     * compression algorithms start to have issues)
     */
    public void setMinGzipSize(int minGzipSize)
    {
        if (minGzipSize < MIN_FUNCTIONAL_SIZE)
            LOG.warn("minGzipSize of {} is inefficient for short content, break even is size {}", minGzipSize, MIN_FUNCTIONAL_SIZE);
        compressSizeMinimum = Math.max(0, minGzipSize);
    }

    /**
     * Set the included filter list of HTTP Methods (replacing any previously set)
     *
     * @param csvMethods the list of methods, CSV format
     * @see #setExcludedMethodList(String)
     */
    public void setIncludedMethodList(String csvMethods)
    {
        setIncludedMethods(StringUtil.csvSplit(csvMethods));
    }

    /**
     * Get the included filter list of HTTP methods in CSV format
     *
     * @return the included filter list of HTTP methods in CSV format
     * @see #getExcludedMethodList()
     */
    public String getIncludedMethodList()
    {
        return String.join(",", getIncludedMethods());
    }

    /**
     * Set the excluded filter list of HTTP Methods (replacing any previously set)
     *
     * @param csvMethods the list of methods, CSV format
     * @see #setIncludedMethodList(String)
     */
    public void setExcludedMethodList(String csvMethods)
    {
        setExcludedMethods(StringUtil.csvSplit(csvMethods));
    }

    /**
     * Get the excluded filter list of HTTP methods in CSV format
     *
     * @return the excluded filter list of HTTP methods in CSV format
     * @see #getIncludedMethodList()
     */
    public String getExcludedMethodList()
    {
        return String.join(",", getExcludedMethods());
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} handle {}", this, request);

        Handler next = getHandler();
        if (next == null)
            return false;

        // Are we already being gzipped?
        if (Request.as(request, GzipRequest.class) != null)
            return next.handle(request, response, callback);

        String pathInContext = Request.getPathInContext(request);
        boolean tryInflate = getInflateBufferSize() >= 0 && isPathInflatable(pathInContext);
        boolean tryDeflate = methods.test(request.getMethod()) && isPathDeflatable(pathInContext) && isMimeTypeDeflatable(request.getContext().getMimeTypes(), pathInContext);

        // Can we skip looking at the request and wrapping request or response?
        if (!tryInflate && !tryDeflate)
            // No need for a Vary header, as we will never deflate
            return next.handle(request, response, callback);

        // Look for inflate and deflate headers
        HttpFields fields = request.getHeaders();
        boolean inflatable = false;
        boolean deflatable = false;
        boolean etagMatches = false;
        boolean seenContentEncoding = false;
        for (ListIterator<HttpField> i = fields.listIterator(fields.size()); i.hasPrevious();)
        {
            HttpField field = i.previous();
            HttpHeader header = field.getHeader();
            if (header == null)
                continue;
            switch (header)
            {
                case CONTENT_ENCODING ->
                {
                    inflatable |= !seenContentEncoding && field.containsLast("gzip");
                    seenContentEncoding = true;
                }
                case ACCEPT_ENCODING -> deflatable = field.contains("gzip");
                case IF_MATCH, IF_NONE_MATCH -> etagMatches |= field.getValue().contains(EtagUtils.ETAG_SEPARATOR);
            }
        }

        // We need to wrap the request IFF we are inflating or have seen etags with compression separators
        if (inflatable && tryInflate || etagMatches)
        {
            // Wrap the request to update the fields and do any inflation
            request = new GzipRequest(request, inflatable && tryInflate ? getInflateBufferSize() : -1);
        }

        if (tryDeflate && vary != null)
        {
            // The response may vary based on the presence or lack of Accept-Encoding.
            response.getHeaders().ensureField(vary);
        }

        // Wrap the response and callback IFF we can be deflated and will try to deflate
        if (deflatable && tryDeflate)
        {
            GzipResponseAndCallback gzipResponseAndCallback = new GzipResponseAndCallback(this, request, response, callback);
            response = gzipResponseAndCallback;
            callback = gzipResponseAndCallback;
        }

        // Call handle() with the possibly wrapped request, response and callback
        if (next.handle(request, response, callback))
            return true;

        // If the request was not accepted, destroy any compressRequest wrapper
        if (request instanceof DynamicDecompressRequest decompressRequest)
        {
            decompressRequest.destroy();
        }
        return false;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s,min=%s,inflate=%s}", getClass().getSimpleName(), hashCode(), getState(), compressSizeMinimum, decompressBufferSize);
    }
}
