//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server.handler.gzip;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.ListIterator;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import javax.servlet.DispatcherType;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.pathmap.PathSpecSet;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.IncludeExclude;
import org.eclipse.jetty.util.RegexSet;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.compression.DeflaterPool;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * A Handler that can dynamically GZIP uncompress requests, and compress responses.
 * <p>
 * The GzipHandler can be applied to the entire server (a {@code gzip.mod} is included in
 * the {@code jetty-home}) or it may be applied to individual contexts.
 * </p>
 * <p>
 * Both Request uncompress and Response compress are gated by a configurable
 * {@link DispatcherType} check on the GzipHandler.
 * (This is similar in behavior to a {@link javax.servlet.Filter} configuration
 * you would find in a Servlet Descriptor file ({@code WEB-INF/web.xml})
 * <br>(Default: {@link DispatcherType#REQUEST}).
 * </p>
 * <p>
 * Requests with a {@code Content-Encoding} header with the value {@code gzip} will
 * be uncompressed by a {@link GzipHttpInputInterceptor} for any API that uses
 * {@link HttpServletRequest#getInputStream()} or {@link HttpServletRequest#getReader()}.
 * </p>
 * <p>
 * Response compression has a number of checks before GzipHandler will perform compression.
 * </p>
 * <ol>
 * <li>
 * Does the request contain a {@code Accept-Encoding} header that specifies
 * {@code gzip} value?
 * </li>
 * <li>
 * Is the {@link HttpServletRequest#getMethod()} allowed by the configured HTTP Method Filter.
 * <br> (Default: {@code GET})
 * </li>
 * <li>
 * Is the incoming Path allowed by the configured Path Specs filters?
 * <br> (Default: all paths are allowed)
 * </li>
 * <li>
 * Is the Request User-Agent allowed by the configured User-Agent filters?
 * <br> (Default: MSIE 6 is excluded)
 * </li>
 * <li>
 * Is the Response {@code Content-Length} header present, and does its
 * value meet the minimum gzip size requirements (default 32 bytes)?
 * </li>
 * <li>
 * Is the Request {@code Accept} header present and does it contain the
 * required {@code gzip} value?
 * </li>
 * </ol>
 * <p>
 * When you encounter a configurable filter in the GzipHandler (method, paths, user-agent,
 * mime-types, etc) that has both Included and Excluded values, note that the Included
 * values always win over the Excluded values.
 * </p>
 * <p>
 * <em>Important note about Default Values</em>:
 * It is important to note that the GzipHandler will automatically configure itself from the
 * MimeType present on the Server, System, and Contexts and the ultimate set of default values
 * for the various filters (paths, methods, mime-types, etc) can be influenced by the
 * available mime types to work with.
 * </p>
 * <p>
 * ETag (or Entity Tag) information: any Request headers for {@code If-None-Match} or
 * {@code If-Match} will be evaluated by the GzipHandler to determine if it was involved
 * in compression of the response earlier.  This is usually present as a {@code --gzip} suffix
 * on the ETag that the Client User-Agent is tracking and handed to the Jetty server.
 * The special {@code --gzip} suffix on the ETag is how GzipHandler knows that the content
 * passed through itself, and this suffix will be stripped from the Request header values
 * before the request is sent onwards to the specific webapp / servlet endpoint for
 * handling.
 * If a ETag is present in the Response headers, and GzipHandler is compressing the
 * contents, it will add the {@code --gzip} suffix before the Response headers are committed
 * and sent to the User Agent.
 * </p>
 * <p>
 * This implementation relies on an Jetty internal {@link org.eclipse.jetty.server.HttpOutput.Interceptor}
 * mechanism to allow for effective and efficient compression of the response on all Output API usages:
 * </p>
 * <ul>
 * <li>
 * {@link javax.servlet.ServletOutputStream} - Obtained from {@link HttpServletResponse#getOutputStream()}
 * using the traditional Blocking I/O techniques
 * </li>
 * <li>
 * {@link javax.servlet.WriteListener} - Provided to
 * {@link javax.servlet.ServletOutputStream#setWriteListener(javax.servlet.WriteListener)}
 * using the new (since Servlet 3.1) Async I/O techniques
 * </li>
 * <li>
 * {@link java.io.PrintWriter} - Obtained from {@link HttpServletResponse#getWriter()}
 * using Blocking I/O techniques
 * </li>
 * </ul>
 * <p>
 * Historically the compression of responses were accomplished via
 * Servlet Filters (eg: {@code GzipFilter}) and usage of {@link javax.servlet.http.HttpServletResponseWrapper}.
 * Since the introduction of Async I/O in Servlet 3.1, this older form of Gzip support
 * in web applications has been problematic and bug ridden.
 * </p>
 */
public class GzipHandler extends HandlerWrapper implements GzipFactory
{
    public static final String GZIP = "gzip";
    public static final String DEFLATE = "deflate";
    public static final int DEFAULT_MIN_GZIP_SIZE = 32;
    public static final int BREAK_EVEN_GZIP_SIZE = 23;
    private static final Logger LOG = Log.getLogger(GzipHandler.class);
    private static final HttpField X_CE_GZIP = new PreEncodedHttpField("X-Content-Encoding", "gzip");
    private static final HttpField TE_CHUNKED = new PreEncodedHttpField(HttpHeader.TRANSFER_ENCODING, HttpHeaderValue.CHUNKED.asString());
    private static final Pattern COMMA_GZIP = Pattern.compile(".*, *gzip");

    private int _poolCapacity = -1;
    private DeflaterPool _deflaterPool = null;

    private int _minGzipSize = DEFAULT_MIN_GZIP_SIZE;
    private int _compressionLevel = Deflater.DEFAULT_COMPRESSION;
    /**
     * @deprecated feature will be removed in Jetty 10.x, with no replacement.
     */
    @Deprecated
    private boolean _checkGzExists = false;
    private boolean _syncFlush = false;
    private int _inflateBufferSize = -1;
    private EnumSet<DispatcherType> _dispatchers = EnumSet.of(DispatcherType.REQUEST);
    // non-static, as other GzipHandler instances may have different configurations
    private final IncludeExclude<String> _agentPatterns = new IncludeExclude<>(RegexSet.class);
    private final IncludeExclude<String> _methods = new IncludeExclude<>();
    private final IncludeExclude<String> _paths = new IncludeExclude<>(PathSpecSet.class);
    private final IncludeExclude<String> _mimeTypes = new IncludeExclude<>();
    private HttpField _vary;

    /**
     * Instantiates a new GzipHandler.
     */
    public GzipHandler()
    {
        _methods.include(HttpMethod.GET.asString());
        for (String type : MimeTypes.getKnownMimeTypes())
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

        if (LOG.isDebugEnabled())
            LOG.debug("{} mime types {}", this, _mimeTypes);

        _agentPatterns.exclude(".*MSIE 6.0.*");
    }

    /**
     * Add excluded to the User-Agent filtering.
     *
     * @param patterns Regular expressions matching user agents to exclude
     * @see #addIncludedAgentPatterns(String...)
     */
    public void addExcludedAgentPatterns(String... patterns)
    {
        _agentPatterns.exclude(patterns);
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
     * Get the Set of {@link DispatcherType} that this Filter will operate on.
     *
     * @return the set of {@link DispatcherType} this filter will operate on
     */
    public EnumSet<DispatcherType> getDispatcherTypes()
    {
        return _dispatchers;
    }

    /**
     * Set of supported {@link DispatcherType} that this filter will operate on.
     *
     * @param dispatchers the set of {@link DispatcherType} that this filter will operate on
     */
    public void setDispatcherTypes(EnumSet<DispatcherType> dispatchers)
    {
        _dispatchers = dispatchers;
    }

    /**
     * Set the list of supported {@link DispatcherType} that this filter will operate on.
     *
     * @param dispatchers the list of {@link DispatcherType} that this filter will operate on
     */
    public void setDispatcherTypes(DispatcherType... dispatchers)
    {
        _dispatchers = EnumSet.copyOf(Arrays.asList(dispatchers));
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
     * Adds included User-Agents for filtering.
     *
     * @param patterns Regular expressions matching user agents to include
     * @see #addExcludedAgentPatterns(String...)
     */
    public void addIncludedAgentPatterns(String... patterns)
    {
        _agentPatterns.include(patterns);
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

    @Override
    protected void doStart() throws Exception
    {
        _deflaterPool = newDeflaterPool(_poolCapacity);
        _vary = (_agentPatterns.size() > 0) ? GzipHttpOutputInterceptor.VARY_ACCEPT_ENCODING_USER_AGENT : GzipHttpOutputInterceptor.VARY_ACCEPT_ENCODING;
        super.doStart();
    }

    /**
     * @deprecated feature will be removed in Jetty 10.x, with no replacement.
     */
    @Deprecated
    public boolean getCheckGzExists()
    {
        return _checkGzExists;
    }

    public int getCompressionLevel()
    {
        return _compressionLevel;
    }

    @Override
    public Deflater getDeflater(Request request, long contentLength)
    {
        HttpFields httpFields = request.getHttpFields();
        String ua = httpFields.get(HttpHeader.USER_AGENT);
        if (ua != null && !isAgentGzipable(ua))
        {
            LOG.debug("{} excluded user agent {}", this, request);
            return null;
        }

        if (contentLength >= 0 && contentLength < _minGzipSize)
        {
            LOG.debug("{} excluded minGzipSize {}", this, request);
            return null;
        }

        // check the accept encoding header
        if (!httpFields.contains(HttpHeader.ACCEPT_ENCODING, "gzip"))
        {
            LOG.debug("{} excluded not gzip accept {}", this, request);
            return null;
        }

        return _deflaterPool.acquire();
    }

    /**
     * Get the current filter list of excluded User-Agent patterns
     *
     * @return the filter list of excluded User-Agent patterns
     * @see #getIncludedAgentPatterns()
     */
    public String[] getExcludedAgentPatterns()
    {
        Set<String> excluded = _agentPatterns.getExcluded();
        return excluded.toArray(new String[excluded.size()]);
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
        return excluded.toArray(new String[excluded.size()]);
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
        return excluded.toArray(new String[excluded.size()]);
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
        return excluded.toArray(new String[excluded.size()]);
    }

    /**
     * Get the current filter list of included User-Agent patterns
     *
     * @return the filter list of included User-Agent patterns
     * @see #getExcludedAgentPatterns()
     */
    public String[] getIncludedAgentPatterns()
    {
        Set<String> includes = _agentPatterns.getIncluded();
        return includes.toArray(new String[includes.size()]);
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
        return includes.toArray(new String[includes.size()]);
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
        return includes.toArray(new String[includes.size()]);
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
        return includes.toArray(new String[includes.size()]);
    }

    /**
     * Get the current filter list of included HTTP methods
     *
     * @return the filter list of included HTTP methods
     * @deprecated use {@link #getIncludedMethods()} instead.  (Will be removed in Jetty 10)
     */
    @Deprecated
    public String[] getMethods()
    {
        return getIncludedMethods();
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

    protected HttpField getVaryField()
    {
        return _vary;
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
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        ServletContext context = baseRequest.getServletContext();
        String path = context == null ? baseRequest.getRequestURI() : URIUtil.addPaths(baseRequest.getServletPath(), baseRequest.getPathInfo());
        LOG.debug("{} handle {} in {}", this, baseRequest, context);

        if (!_dispatchers.contains(baseRequest.getDispatcherType()))
        {
            LOG.debug("{} excluded by dispatcherType {}", this, baseRequest.getDispatcherType());
            _handler.handle(target, baseRequest, request, response);
            return;
        }

        // Handle request inflation
        if (_inflateBufferSize > 0)
        {
            boolean inflate = false;
            for (ListIterator<HttpField> i = baseRequest.getHttpFields().listIterator(); i.hasNext(); )
            {
                HttpField field = i.next();

                if (field.getHeader() == HttpHeader.CONTENT_ENCODING)
                {
                    if (field.getValue().equalsIgnoreCase("gzip"))
                    {
                        i.set(X_CE_GZIP);
                        inflate = true;
                        break;
                    }

                    if (COMMA_GZIP.matcher(field.getValue()).matches())
                    {
                        String v = field.getValue();
                        v = v.substring(0, v.lastIndexOf(','));
                        i.set(new HttpField(HttpHeader.CONTENT_ENCODING, v));
                        i.add(X_CE_GZIP);
                        inflate = true;
                        break;
                    }
                }
            }

            if (inflate)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} inflate {}", this, request);

                baseRequest.getHttpInput().addInterceptor(new GzipHttpInputInterceptor(baseRequest.getHttpChannel().getByteBufferPool(), _inflateBufferSize));

                for (ListIterator<HttpField> i = baseRequest.getHttpFields().listIterator(); i.hasNext(); )
                {
                    HttpField field = i.next();
                    if (field.getHeader() == HttpHeader.CONTENT_LENGTH)
                    {
                        i.set(new HttpField("X-Content-Length", field.getValue()));
                        break;
                    }
                }
            }
        }

        // Are we already being gzipped?
        HttpOutput out = baseRequest.getResponse().getHttpOutput();
        HttpOutput.Interceptor interceptor = out.getInterceptor();
        while (interceptor != null)
        {
            if (interceptor instanceof GzipHttpOutputInterceptor)
            {
                LOG.debug("{} already intercepting {}", this, request);
                _handler.handle(target, baseRequest, request, response);
                return;
            }
            interceptor = interceptor.getNextInterceptor();
        }

        // Special handling for etags
        for (ListIterator<HttpField> fields = baseRequest.getHttpFields().listIterator(); fields.hasNext(); )
        {
            HttpField field = fields.next();
            if (field.getHeader() == HttpHeader.IF_NONE_MATCH || field.getHeader() == HttpHeader.IF_MATCH)
            {
                String etag = field.getValue();
                int i = etag.indexOf(CompressedContentFormat.GZIP._etagQuote);
                if (i > 0)
                {
                    baseRequest.setAttribute("o.e.j.s.h.gzip.GzipHandler.etag", etag);
                    while (i >= 0)
                    {
                        etag = etag.substring(0, i) + etag.substring(i + CompressedContentFormat.GZIP._etag.length());
                        i = etag.indexOf(CompressedContentFormat.GZIP._etagQuote, i);
                    }

                    fields.set(new HttpField(field.getHeader(), etag));
                }
            }
        }

        // If not a supported method - no Vary because no matter what client, this URI is always excluded
        if (!_methods.test(baseRequest.getMethod()))
        {
            LOG.debug("{} excluded by method {}", this, request);
            _handler.handle(target, baseRequest, request, response);
            return;
        }

        // If not a supported URI- no Vary because no matter what client, this URI is always excluded
        // Use pathInfo because this is be
        if (!isPathGzipable(path))
        {
            LOG.debug("{} excluded by path {}", this, request);
            _handler.handle(target, baseRequest, request, response);
            return;
        }

        // Exclude non compressible mime-types known from URI extension. - no Vary because no matter what client, this URI is always excluded
        String mimeType = context == null ? MimeTypes.getDefaultMimeByExtension(path) : context.getMimeType(path);
        if (mimeType != null)
        {
            mimeType = MimeTypes.getContentTypeWithoutCharset(mimeType);
            if (!isMimeTypeGzipable(mimeType))
            {
                LOG.debug("{} excluded by path suffix mime type {}", this, request);
                // handle normally without setting vary header
                _handler.handle(target, baseRequest, request, response);
                return;
            }
        }

        if (_checkGzExists && context != null)
        {
            String realpath = request.getServletContext().getRealPath(path);
            if (realpath != null)
            {
                File gz = new File(realpath + ".gz");
                if (gz.exists())
                {
                    LOG.debug("{} gzip exists {}", this, request);
                    // allow default servlet to handle
                    _handler.handle(target, baseRequest, request, response);
                    return;
                }
            }
        }

        HttpOutput.Interceptor origInterceptor = out.getInterceptor();
        try
        {
            // install interceptor and handle
            out.setInterceptor(new GzipHttpOutputInterceptor(this, getVaryField(), baseRequest.getHttpChannel(), origInterceptor, isSyncFlush()));

            if (_handler != null)
                _handler.handle(target, baseRequest, request, response);
        }
        finally
        {
            // reset interceptor if request not handled
            if (!baseRequest.isHandled() && !baseRequest.isAsyncStarted())
                out.setInterceptor(origInterceptor);
        }
    }

    /**
     * Test if the provided User-Agent is allowed based on the User-Agent filters.
     *
     * @param ua the user agent
     * @return whether compressing is allowed for the given user agent
     */
    protected boolean isAgentGzipable(String ua)
    {
        if (ua == null)
            return false;

        return _agentPatterns.test(ua);
    }

    /**
     * Test if the provided MIME type is allowed based on the MIME type filters.
     *
     * @param mimetype the MIME type to test
     * @return true if allowed, false otherwise
     */
    @Override
    public boolean isMimeTypeGzipable(String mimetype)
    {
        return _mimeTypes.test(mimetype);
    }

    /**
     * Test if the provided Request URI is allowed based on the Path Specs filters.
     *
     * @param requestURI the request uri
     * @return whether compressing is allowed for the given the path
     */
    protected boolean isPathGzipable(String requestURI)
    {
        if (requestURI == null)
            return true;

        return _paths.test(requestURI);
    }

    @Override
    public void recycle(Deflater deflater)
    {
        _deflaterPool.release(deflater);
    }

    /**
     * Set the Check if {@code *.gz} file for the incoming file exists.
     *
     * @param checkGzExists whether to check if a static gz file exists for
     * the resource that the DefaultServlet may serve as precompressed.
     * @deprecated feature will be removed in Jetty 10.x, with no replacement.
     */
    @Deprecated
    public void setCheckGzExists(boolean checkGzExists)
    {
        _checkGzExists = checkGzExists;
    }

    /**
     * Set the Compression level that {@link Deflater} uses.
     *
     * @param compressionLevel The compression level to use to initialize {@link Deflater#setLevel(int)}
     * @see Deflater#setLevel(int)
     */
    public void setCompressionLevel(int compressionLevel)
    {
        if (isStarted())
            throw new IllegalStateException(getState());

        _compressionLevel = compressionLevel;
    }

    /**
     * Set the excluded filter list of User-Agent patterns (replacing any previously set)
     *
     * @param patterns Regular expressions list matching user agents to exclude
     * @see #setIncludedAgentPatterns(String...)
     */
    public void setExcludedAgentPatterns(String... patterns)
    {
        _agentPatterns.getExcluded().clear();
        addExcludedAgentPatterns(patterns);
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
     * Set the included filter list of User-Agent patterns (replacing any previously set)
     *
     * @param patterns Regular expressions matching user agents to include
     * @see #setExcludedAgentPatterns(String...)
     */
    public void setIncludedAgentPatterns(String... patterns)
    {
        _agentPatterns.getIncluded().clear();
        addIncludedAgentPatterns(patterns);
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
     * Gets the maximum number of Deflaters that the DeflaterPool can hold.
     *
     * @return the Deflater pool capacity
     */
    public int getDeflaterPoolCapacity()
    {
        return _poolCapacity;
    }

    /**
     * Sets the maximum number of Deflaters that the DeflaterPool can hold.
     */
    public void setDeflaterPoolCapacity(int capacity)
    {
        if (isStarted())
            throw new IllegalStateException(getState());

        _poolCapacity = capacity;
    }

    protected DeflaterPool newDeflaterPool(int capacity)
    {
        return new DeflaterPool(capacity, getCompressionLevel(), true);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s,min=%s,inflate=%s}", getClass().getSimpleName(), hashCode(), getState(), _minGzipSize, _inflateBufferSize);
    }
}
