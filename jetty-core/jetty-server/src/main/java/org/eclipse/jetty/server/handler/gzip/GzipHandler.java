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

package org.eclipse.jetty.server.handler.gzip;

import java.util.Set;
import java.util.zip.Deflater;

import org.eclipse.jetty.http.EtagUtils;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.pathmap.PathSpecSet;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.AsciiLowerCaseSet;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IncludeExclude;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.compression.DeflaterPool;
import org.eclipse.jetty.util.compression.InflaterPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GzipHandler extends Handler.Wrapper implements GzipFactory
{
    public static final String GZIP_HANDLER_ETAGS = "o.e.j.s.h.gzip.GzipHandler.etag";
    public static final String GZIP = "gzip";
    public static final String DEFLATE = "deflate";
    public static final int DEFAULT_MIN_GZIP_SIZE = 32;
    public static final int BREAK_EVEN_GZIP_SIZE = 23;
    private static final Logger LOG = LoggerFactory.getLogger(GzipHandler.class);

    private InflaterPool _inflaterPool;
    private DeflaterPool _deflaterPool;
    private int _minGzipSize = DEFAULT_MIN_GZIP_SIZE;
    private boolean _syncFlush = false;
    private int _inflateBufferSize = -1;
    // non-static, as other GzipHandler instances may have different configurations
    private final IncludeExclude<String> _methods = new IncludeExclude<>();
    private final IncludeExclude<String> _inflatePaths = new IncludeExclude<>(PathSpecSet.class);
    private final IncludeExclude<String> _paths = new IncludeExclude<>(PathSpecSet.class);
    private final IncludeExclude<String> _mimeTypes = new IncludeExclude<>(AsciiLowerCaseSet.class);
    private HttpField _vary = GzipResponseAndCallback.VARY_ACCEPT_ENCODING;

    /**
     * Instantiates a new GzipHandler.
     */
    public GzipHandler()
    {
        _methods.include(HttpMethod.GET.asString());
        _methods.include(HttpMethod.POST.asString());
        for (String type : MimeTypes.DEFAULTS.getMimeMap().values())
        {
            if ("image/svg+xml".equals(type))
                _paths.exclude("*.svgz");
            else if (type.startsWith("image/") ||
                type.startsWith("audio/") ||
                type.startsWith("video/"))
                _mimeTypes.exclude(type);
        }
        _mimeTypes.exclude("application/compress");
        _mimeTypes.exclude("application/zip");
        _mimeTypes.exclude("application/gzip");
        _mimeTypes.exclude("application/bzip2");
        _mimeTypes.exclude("application/brotli");
        _mimeTypes.exclude("application/x-xz");
        _mimeTypes.exclude("application/x-rar-compressed");

        // It is possible to use SSE with GzipHandler but you will need to set _synFlush to true which will impact performance.
        _mimeTypes.exclude("text/event-stream");

        if (LOG.isDebugEnabled())
            LOG.debug("{} mime types {}", this, _mimeTypes);
    }

    @Override
    protected void doStart() throws Exception
    {
        Server server = getServer();
        if (_inflaterPool == null)
        {
            _inflaterPool = InflaterPool.ensurePool(server);
            addBean(_inflaterPool);
        }
        if (_deflaterPool == null)
        {
            _deflaterPool = DeflaterPool.ensurePool(server);
            addBean(_deflaterPool);
        }

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();

        removeBean(_inflaterPool);
        _inflaterPool = null;

        removeBean(_deflaterPool);
        _deflaterPool = null;
    }

    /**
     * @return The VARY field to use.
     */
    public HttpField getVary()
    {
        return _vary;
    }

    /**
     * @param vary The VARY field to use. It if is not an instance of {@link PreEncodedHttpField},
     *             then it will be converted to one.
     */
    public void setVary(HttpField vary)
    {
        if (isRunning())
            throw new IllegalStateException(getState());

        if (vary == null || (vary instanceof PreEncodedHttpField))
            _vary = vary;
        else
            _vary = new PreEncodedHttpField(vary.getHeader(), vary.getName(), vary.getValue());
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
            _methods.exclude(m);
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
     */
    public void addExcludedMimeTypes(String... types)
    {
        for (String t : types)
        {
            _mimeTypes.exclude(StringUtil.csvSplit(t));
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
     */
    public void addExcludedPaths(String... pathspecs)
    {
        for (String p : pathspecs)
        {
            _paths.exclude(StringUtil.csvSplit(p));
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
     */
    public void addExcludedInflationPaths(String... pathspecs)
    {
        for (String p : pathspecs)
        {
            _inflatePaths.exclude(StringUtil.csvSplit(p));
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
            _methods.include(m);
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
        return _syncFlush;
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
        _syncFlush = syncFlush;
    }

    /**
     * Add included MIME types for response filtering
     *
     * @param types The mime types to include (without charset or other parameters)
     * For backward compatibility the mimetypes may be comma separated strings, but this
     * will not be supported in future versions.
     * @see #addExcludedMimeTypes(String...)
     */
    public void addIncludedMimeTypes(String... types)
    {
        for (String t : types)
        {
            _mimeTypes.include(StringUtil.csvSplit(t));
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
     */
    public void addIncludedPaths(String... pathspecs)
    {
        for (String p : pathspecs)
        {
            _paths.include(StringUtil.csvSplit(p));
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
     */
    public void addIncludedInflationPaths(String... pathspecs)
    {
        for (String p : pathspecs)
        {
            _inflatePaths.include(StringUtil.csvSplit(p));
        }
    }

    @Override
    public DeflaterPool.Entry getDeflaterEntry(Request request, long contentLength)
    {
        if (contentLength >= 0 && contentLength < _minGzipSize)
        {
            LOG.debug("{} excluded minGzipSize {}", this, request);
            return null;
        }

        // check the accept encoding header
        if (!request.getHeaders().contains(HttpHeader.ACCEPT_ENCODING, "gzip"))
        {
            LOG.debug("{} excluded not gzip accept {}", this, request);
            return null;
        }

        return _deflaterPool.acquire();
    }

    /**
     * Get the current filter list of excluded HTTP methods
     *
     * @return the filter list of excluded HTTP methods
     * @see #getIncludedMethods()
     */
    public String[] getExcludedMethods()
    {
        Set<String> excluded = _methods.getExcluded();
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
        Set<String> excluded = _mimeTypes.getExcluded();
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
        Set<String> excluded = _paths.getExcluded();
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
        Set<String> excluded = _inflatePaths.getExcluded();
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
        Set<String> includes = _methods.getIncluded();
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
        Set<String> includes = _mimeTypes.getIncluded();
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
        Set<String> includes = _paths.getIncluded();
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
        Set<String> includes = _inflatePaths.getIncluded();
        return includes.toArray(new String[0]);
    }

    /**
     * Get the minimum size, in bytes, that a response {@code Content-Length} must be
     * before compression will trigger.
     *
     * @return minimum response size (in bytes) that triggers compression
     * @see #setMinGzipSize(int)
     */
    public int getMinGzipSize()
    {
        return _minGzipSize;
    }

    /**
     * Get the size (in bytes) of the {@link java.util.zip.Inflater} buffer used to inflate
     * compressed requests.
     *
     * @return size in bytes of the buffer, or 0 for no inflation.
     */
    public int getInflateBufferSize()
    {
        return _inflateBufferSize;
    }

    /**
     * Set the size (in bytes) of the {@link java.util.zip.Inflater} buffer used to inflate comrpessed requests.
     *
     * @param size size in bytes of the buffer, or 0 for no inflation.
     */
    public void setInflateBufferSize(int size)
    {
        _inflateBufferSize = size;
    }

    @Override
    public boolean process(Request request, Response response, Callback callback) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} handle {}", this, request);

        Handler next = getHandler();
        if (next == null)
            return false;

        // Are we already being gzipped?
        if (Request.as(request, GzipRequest.class) != null)
            return next.process(request, response, callback);

        String path = Request.getPathInContext(request);
        boolean tryInflate = getInflateBufferSize() >= 0 && isPathInflatable(path);
        boolean tryDeflate = _methods.test(request.getMethod()) && isPathDeflatable(path) && isMimeTypeDeflatable(request.getContext().getMimeTypes(), path);

        // Can we skip looking at the request and wrapping request or response?
        if (!tryInflate && !tryDeflate)
            // No need for a Vary header, as we will never deflate
            return next.process(request, response, callback);

        // Look for inflate and deflate headers
        HttpFields fields = request.getHeaders();
        boolean inflatable = false;
        boolean deflatable = false;
        boolean etagMatches = false;
        for (HttpField field : fields)
        {
            HttpHeader header = field.getHeader();
            if (header == null)
                continue;
            switch (header)
            {
                case CONTENT_ENCODING -> inflatable = field.contains("gzip");
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

        // Wrap the response and callback IFF we can be deflated and will try to deflate
        if (deflatable && tryDeflate)
        {
            GzipResponseAndCallback gzipResponseAndCallback = new GzipResponseAndCallback(this, request, response, callback);
            response = gzipResponseAndCallback;
            callback = gzipResponseAndCallback;
        }
        else if (tryDeflate && _vary != null)
        {
            // We are not wrapping the response, but could have if request accepted, so we add a Vary header.
            response.getHeaders().ensureField(_vary);
        }

        // Call the process with the possibly wrapped request, response and callback
        if (next.process(request, response, callback))
            return true;

        // If the request was not accepted, destroy any gzipRequest wrapper
        if (request instanceof GzipRequest gzipRequest)
            gzipRequest.destroy();
        return false;
    }

    protected boolean isMimeTypeDeflatable(MimeTypes mimeTypes, String requestURI)
    {
        // Exclude non-compressible mime-types known from URI extension
        String mimeType = mimeTypes.getMimeByExtension(requestURI);
        if (mimeType != null)
        {
            mimeType = HttpField.valueParameters(mimeType, null);
            return isMimeTypeDeflatable(mimeType);
        }
        return true;
    }

    /**
     * Test if the provided MIME type is allowed based on the MIME type filters.
     *
     * @param mimetype the MIME type to test
     * @return true if allowed, false otherwise
     */
    @Override
    public boolean isMimeTypeDeflatable(String mimetype)
    {
        return _mimeTypes.test(mimetype);
    }

    /**
     * Test if the provided Request URI is allowed based on the Path Specs filters.
     *
     * @param requestURI the request uri
     * @return whether compressing is allowed for the given the path
     */
    protected boolean isPathDeflatable(String requestURI)
    {
        if (requestURI == null)
            return true;

        return _paths.test(requestURI);
    }

    /**
     * Test if the provided Request URI is allowed to be inflated based on the Path Specs filters.
     *
     * @param requestURI the request uri
     * @return whether decompressing is allowed for the given the path.
     */
    protected boolean isPathInflatable(String requestURI)
    {
        if (requestURI == null)
            return true;

        return _inflatePaths.test(requestURI);
    }

    /**
     * Set the excluded filter list of HTTP methods (replacing any previously set)
     *
     * @param methods the HTTP methods to exclude
     * @see #setIncludedMethods(String...)
     */
    public void setExcludedMethods(String... methods)
    {
        _methods.getExcluded().clear();
        _methods.exclude(methods);
    }

    /**
     * Set the excluded filter list of MIME types (replacing any previously set)
     *
     * @param types The mime types to exclude (without charset or other parameters)
     * @see #setIncludedMimeTypes(String...)
     */
    public void setExcludedMimeTypes(String... types)
    {
        _mimeTypes.getExcluded().clear();
        _mimeTypes.exclude(types);
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
        _paths.getExcluded().clear();
        _paths.exclude(pathspecs);
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
        _inflatePaths.getExcluded().clear();
        _inflatePaths.exclude(pathspecs);
    }

    /**
     * Set the included filter list of HTTP methods (replacing any previously set)
     *
     * @param methods The methods to include in compression
     * @see #setExcludedMethods(String...)
     */
    public void setIncludedMethods(String... methods)
    {
        _methods.getIncluded().clear();
        _methods.include(methods);
    }

    /**
     * Set the included filter list of MIME types (replacing any previously set)
     *
     * @param types The mime types to include (without charset or other parameters)
     * @see #setExcludedMimeTypes(String...)
     */
    public void setIncludedMimeTypes(String... types)
    {
        _mimeTypes.getIncluded().clear();
        _mimeTypes.include(types);
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
        _paths.getIncluded().clear();
        _paths.include(pathspecs);
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
        _inflatePaths.getIncluded().clear();
        _inflatePaths.include(pathspecs);
    }

    /**
     * Set the minimum response size to trigger dynamic compression.
     * <p>
     *     Sizes below {@link #BREAK_EVEN_GZIP_SIZE} will result a compressed response that is larger than the
     *     original data.
     * </p>
     *
     * @param minGzipSize minimum response size in bytes (not allowed to be lower then {@link #BREAK_EVEN_GZIP_SIZE})
     */
    public void setMinGzipSize(int minGzipSize)
    {
        if (minGzipSize < BREAK_EVEN_GZIP_SIZE)
            LOG.warn("minGzipSize of {} is inefficient for short content, break even is size {}", minGzipSize, BREAK_EVEN_GZIP_SIZE);
        _minGzipSize = Math.max(0, minGzipSize);
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

    /**
     * Get the DeflaterPool being used. The default value of this is null before starting, but after starting if it is null
     * it will be set to the default DeflaterPool which is stored as a bean on the server.
     * @return the DeflaterPool being used.
     */
    public DeflaterPool getDeflaterPool()
    {
        return _deflaterPool;
    }

    /**
     * Get the InflaterPool being used. The default value of this is null before starting, but after starting if it is null
     * it will be set to the default InflaterPool which is stored as a bean on the server.
     * @return the DeflaterPool being used.
     */
    public InflaterPool getInflaterPool()
    {
        return _inflaterPool;
    }

    /**
     * Set the DeflaterPool to be used. This should be called before starting.
     * If this value is null when starting the default pool will be used from the server.
     * @param deflaterPool the DeflaterPool to use.
     */
    public void setDeflaterPool(DeflaterPool deflaterPool)
    {
        if (isStarted())
            throw new IllegalStateException(getState());

        updateBean(_deflaterPool, deflaterPool);
        _deflaterPool = deflaterPool;
    }

    /**
     * Set the InflaterPool to be used. This should be called before starting.
     * If this value is null when starting the default pool will be used from the server.
     * @param inflaterPool the InflaterPool to use.
     */
    public void setInflaterPool(InflaterPool inflaterPool)
    {
        if (isStarted())
            throw new IllegalStateException(getState());

        updateBean(_inflaterPool, inflaterPool);
        _inflaterPool = inflaterPool;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s,min=%s,inflate=%s}", getClass().getSimpleName(), hashCode(), getState(), _minGzipSize, _inflateBufferSize);
    }
}
