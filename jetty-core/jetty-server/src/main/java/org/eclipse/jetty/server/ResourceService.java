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

package org.eclipse.jetty.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.http.ByteRange;
import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.DateGenerator;
import org.eclipse.jetty.http.DateParser;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiPartByteRanges;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.http.QuotedQualityCSV;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resource service, used by DefaultServlet and ResourceHandler
 */
public class ResourceService
{
    private static final Logger LOG = LoggerFactory.getLogger(ResourceService.class);

    // TODO: see if we can set this to private eventually
    public static final int NO_CONTENT_LENGTH = -1;
    // TODO: see if we can set this to private eventually
    public static final int USE_KNOWN_CONTENT_LENGTH = -2;

    private List<CompressedContentFormat> _precompressedFormats = new ArrayList<>();
    private WelcomeFactory _welcomeFactory;

    private boolean _redirectWelcome = false;
    private boolean _etags = false;
    private List<String> _gzipEquivalentFileExtensions;
    private HttpContent.ContentFactory _contentFactory;
    private final Map<String, List<String>> _preferredEncodingOrderCache = new ConcurrentHashMap<>();
    private List<String> _preferredEncodingOrder = new ArrayList<>();
    private int _encodingCacheSize = 100;
    private boolean _dirAllowed = true;
    private boolean _acceptRanges = true;
    private HttpField _cacheControl;

    private ByteBuffer _stylesheetBuffer;
    private final long _stylesheetModifiedMs = (System.currentTimeMillis() / 1000) * 1000L;
    private final HttpField _stylesheetModified = new PreEncodedHttpField(HttpHeader.LAST_MODIFIED, DateGenerator.formatDate(_stylesheetModifiedMs));

    public ResourceService()
    {
    }

    /**
     * Load the stylesheet buffer
     *
     * @param stylesheet The location of the stylesheet to be used as-is.
     */
    public void loadStylesheet(Resource stylesheet) throws IOException
    {
        Objects.requireNonNull(stylesheet);

        if (!stylesheet.exists())
        {
            LOG.warn("Stylesheet does not exist: {}", stylesheet);
            return;
        }

        if (stylesheet.isDirectory())
        {
            LOG.warn("Stylesheet is a directory: {}", stylesheet);
            return;
        }

        Path path = stylesheet.getPath();
        if (path == null)
        {
            LOG.warn("Stylesheet is not a path resource: {}", stylesheet);
            return;
        }

        byte[] buf = Files.readAllBytes(path);
        _stylesheetBuffer = ByteBuffer.wrap(buf);
    }

    /**
     * Load the default Stylesheet if one has not been previously with a call to {@link #loadStylesheet(Resource)}
     */
    public void loadDefaultStylesheetIfNotPresent()
    {
        if (_stylesheetBuffer != null)
            return;

        URL url = ResourceService.class.getResource("jetty-dir.css");
        if (url == null)
        {
            LOG.warn("Unable to find resource: %s/jetty-dir.css".formatted(TypeUtil.toClassReference(ResourceService.class.getPackageName())));
        }
        else
        {
            try (InputStream in = url.openStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream())
            {
                IO.copy(in, out);
                _stylesheetBuffer = ByteBuffer.wrap(out.toByteArray());
            }
            catch (IOException e)
            {
                LOG.warn("Unable to load resource: " + url, e);
            }
        }
    }

    public HttpContent getContent(String path, Request request) throws IOException
    {
        ContextHandler contextHandler = ContextHandler.getContextHandler(request);
        return getContent(path, contextHandler);
    }

    public HttpContent getContent(String path, AliasCheck aliasCheck) throws IOException
    {
        HttpContent content = _contentFactory.getContent(path == null ? "" : path);
        if (content != null)
        {
            if (aliasCheck != null && !aliasCheck.checkAlias(path, content.getResource()))
                return null;
        }

        return content;
    }

    public HttpContent.ContentFactory getContentFactory()
    {
        return _contentFactory;
    }

    public void setContentFactory(HttpContent.ContentFactory contentFactory)
    {
        _contentFactory = contentFactory;
    }

    /**
     * @return the cacheControl header to set on all static content.
     */
    public String getCacheControl()
    {
        return _cacheControl.getValue();
    }

    /**
     * @return file extensions that signify that a file is gzip compressed. Eg ".svgz"
     */
    public List<String> getGzipEquivalentFileExtensions()
    {
        return _gzipEquivalentFileExtensions;
    }

    public void doGet(Request request, Response response, Callback callback, HttpContent content) throws Exception
    {
        String pathInContext = request.getPathInContext();

        // Is this a Range request?
        List<String> reqRanges = request.getHeaders().getValuesList(HttpHeader.RANGE.asString());

        boolean endsWithSlash = pathInContext.endsWith(URIUtil.SLASH);
        boolean checkPrecompressedVariants = _precompressedFormats.size() > 0 && !endsWithSlash && reqRanges.isEmpty();

        try
        {
            if (pathInContext.endsWith("/jetty-dir.css"))
            {
                if (sendStylesheet(pathInContext, request, response, callback))
                    return;
                else
                    Response.writeError(request, response, callback, HttpStatus.NOT_FOUND_404);
            }

            // Directory?
            if (content.getResource().isDirectory())
            {
                sendWelcome(content, pathInContext, endsWithSlash, request, response, callback);
                return;
            }

            // Strip slash?
            if (endsWithSlash && pathInContext.length() > 1)
            {
                // TODO need helper code to edit URIs
                String q = request.getHttpURI().getQuery();
                pathInContext = pathInContext.substring(0, pathInContext.length() - 1);
                if (q != null && q.length() != 0)
                    pathInContext += "?" + q;
                Response.sendRedirect(request, response, callback, URIUtil.addPaths(request.getContext().getContextPath(), pathInContext));
                return;
            }

            // Conditional response?
            if (passConditionalHeaders(request, response, content, callback))
                return;

            // Precompressed variant available?
            Map<CompressedContentFormat, ? extends HttpContent> precompressedContents = checkPrecompressedVariants ? content.getPrecompressedContents() : null;
            if (precompressedContents != null && precompressedContents.size() > 0)
            {
                // Tell caches that response may vary by accept-encoding
                response.getHeaders().put(HttpHeader.VARY, HttpHeader.ACCEPT_ENCODING.asString());

                List<String> preferredEncodings = getPreferredEncodingOrder(request);
                CompressedContentFormat precompressedContentEncoding = getBestPrecompressedContent(preferredEncodings, precompressedContents.keySet());
                if (precompressedContentEncoding != null)
                {
                    HttpContent precompressedContent = precompressedContents.get(precompressedContentEncoding);
                    if (LOG.isDebugEnabled())
                        LOG.debug("precompressed={}", precompressedContent);
                    content = precompressedContent;
                    response.getHeaders().put(HttpHeader.CONTENT_ENCODING, precompressedContentEncoding.getEncoding());
                }
            }

            // TODO this should be done by HttpContent#getContentEncoding
            if (isGzippedContent(pathInContext))
                response.getHeaders().put(HttpHeader.CONTENT_ENCODING, "gzip");

            // Send the data
            sendData(request, response, callback, content, reqRanges);
        }
        // Can be thrown from contentFactory.getContent() call when using invalid characters
        catch (InvalidPathException e) // TODO: this cannot trigger here, as contentFactory.getContent() isn't called in this try block
        {
            if (LOG.isDebugEnabled())
                LOG.debug("InvalidPathException for pathInContext: {}", pathInContext, e);
            Response.writeError(request, response, callback, HttpStatus.NOT_FOUND_404);
        }
        catch (IllegalArgumentException e)
        {
            LOG.warn("Failed to serve resource: {}", pathInContext, e);
            if (!response.isCommitted())
                Response.writeError(request, response, callback, e);
        }
    }

    private List<String> getPreferredEncodingOrder(Request request)
    {
        Enumeration<String> headers = request.getHeaders().getValues(HttpHeader.ACCEPT_ENCODING.asString());
        if (!headers.hasMoreElements())
            return Collections.emptyList();

        String key = headers.nextElement();
        if (headers.hasMoreElements())
        {
            StringBuilder sb = new StringBuilder(key.length() * 2);
            do
            {
                sb.append(',').append(headers.nextElement());
            }
            while (headers.hasMoreElements());
            key = sb.toString();
        }

        List<String> values = _preferredEncodingOrderCache.get(key);
        if (values == null)
        {
            QuotedQualityCSV encodingQualityCSV = new QuotedQualityCSV(_preferredEncodingOrder);
            encodingQualityCSV.addValue(key);
            values = encodingQualityCSV.getValues();

            // keep cache size in check even if we get strange/malicious input
            if (_preferredEncodingOrderCache.size() > _encodingCacheSize)
                _preferredEncodingOrderCache.clear();

            _preferredEncodingOrderCache.put(key, values);
        }

        return values;
    }

    private boolean isGzippedContent(String path)
    {
        if (path == null || _gzipEquivalentFileExtensions == null)
            return false;

        for (String suffix : _gzipEquivalentFileExtensions)
        {
            if (path.endsWith(suffix))
                return true;
        }
        return false;
    }

    private CompressedContentFormat getBestPrecompressedContent(List<String> preferredEncodings, java.util.Collection<CompressedContentFormat> availableFormats)
    {
        if (availableFormats.isEmpty())
            return null;

        for (String encoding : preferredEncodings)
        {
            for (CompressedContentFormat format : availableFormats)
            {
                if (format.getEncoding().equals(encoding))
                    return format;
            }

            if ("*".equals(encoding))
                return availableFormats.iterator().next();

            if (HttpHeaderValue.IDENTITY.asString().equals(encoding))
                return null;
        }
        return null;
    }

    /**
     * @return true if the request was processed, false otherwise.
     */
    protected boolean passConditionalHeaders(Request request, Response response, HttpContent content, Callback callback) throws IOException
    {
        try
        {
            String ifm = null;
            String ifnm = null;
            String ifms = null;
            long ifums = -1;

            // Find multiple fields by iteration as an optimization
            for (HttpField field : request.getHeaders())
            {
                if (field.getHeader() != null)
                {
                    switch (field.getHeader())
                    {
                        case IF_MATCH -> ifm = field.getValue();
                        case IF_NONE_MATCH -> ifnm = field.getValue();
                        case IF_MODIFIED_SINCE -> ifms = field.getValue();
                        case IF_UNMODIFIED_SINCE -> ifums = DateParser.parseDate(field.getValue());
                        default ->
                        {
                        }
                    }
                }
            }

            if (_etags)
            {
                String etag = content.getETagValue();
                if (ifm != null)
                {
                    boolean match = false;
                    if (etag != null && !etag.startsWith("W/"))
                    {
                        QuotedCSV quoted = new QuotedCSV(true, ifm);
                        for (String etagWithSuffix : quoted)
                        {
                            if (CompressedContentFormat.tagEquals(etag, etagWithSuffix))
                            {
                                match = true;
                                break;
                            }
                        }
                    }

                    if (!match)
                    {
                        Response.writeError(request, response, callback, HttpStatus.PRECONDITION_FAILED_412);
                        return true;
                    }
                }

                if (ifnm != null && etag != null)
                {
                    // Handle special case of exact match OR gzip exact match
                    if (CompressedContentFormat.tagEquals(etag, ifnm) && ifnm.indexOf(',') < 0)
                    {
                        Response.writeError(request, response, callback, HttpStatus.NOT_MODIFIED_304);
                        return true;
                    }

                    // Handle list of tags
                    QuotedCSV quoted = new QuotedCSV(true, ifnm);
                    for (String tag : quoted)
                    {
                        if (CompressedContentFormat.tagEquals(etag, tag))
                        {
                            Response.writeError(request, response, callback, HttpStatus.NOT_MODIFIED_304);
                            return true;
                        }
                    }

                    // If etag requires content to be served, then do not check if-modified-since
                    return false;
                }
            }

            // Handle if modified since
            if (ifms != null)
            {
                //Get jetty's Response impl
                String mdlm = content.getLastModifiedValue();
                if (ifms.equals(mdlm))
                {
                    Response.writeError(request, response, callback, HttpStatus.NOT_MODIFIED_304);
                    return true;
                }

                long ifmsl = request.getHeaders().getDateField(HttpHeader.IF_MODIFIED_SINCE);
                if (ifmsl != -1 && Files.getLastModifiedTime(content.getResource().getPath()).toMillis() / 1000 <= ifmsl / 1000)
                {
                    Response.writeError(request, response, callback, HttpStatus.NOT_MODIFIED_304);
                    return true;
                }
            }

            // Parse the if[un]modified dates and compare to resource
            if (ifums != -1 && Files.getLastModifiedTime(content.getResource().getPath()).toMillis() / 1000 > ifums / 1000)
            {
                Response.writeError(request, response, callback, HttpStatus.PRECONDITION_FAILED_412);
                return true;
            }
        }
        catch (IllegalArgumentException iae)
        {
            if (!response.isCommitted())
                Response.writeError(request, response, callback, HttpStatus.BAD_REQUEST_400, null, iae);
            throw iae;
        }

        return false;
    }

    /**
     * Send the optional stylesheet if requested.
     *
     * @param pathInContext the path in context to evaluate
     * @param request the request
     * @param response the response to use
     * @return true if stylesheet was served
     */
    public boolean sendStylesheet(String pathInContext, Request request, Response response, Callback callback)
    {
        if (_stylesheetBuffer == null)
            return false;

        if (pathInContext.endsWith("/jetty-dir.css"))
        {
            ByteBuffer content = BufferUtil.EMPTY_BUFFER;
            if (_stylesheetModifiedMs > 0 && request.getHeaders().getDateField(HttpHeader.IF_MODIFIED_SINCE) == _stylesheetModifiedMs)
                response.setStatus(HttpStatus.NOT_MODIFIED_304);
            else
            {
                response.setStatus(HttpStatus.OK_200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/css");
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, _stylesheetBuffer.remaining());
                response.getHeaders().add(_stylesheetModified);
                response.getHeaders().put(HttpHeader.CACHE_CONTROL.toString(), "max-age=360000,public");
                content = _stylesheetBuffer.slice();
            }
            response.write(true, content, callback);
            return true;
        }
        return false;
    }

    protected void sendWelcome(HttpContent content, String pathInContext, boolean endsWithSlash, Request request, Response response, Callback callback) throws Exception
    {
        // Redirect to directory
        if (!endsWithSlash)
        {
            HttpURI.Mutable uri = HttpURI.build(request.getHttpURI());
            if (!uri.getCanonicalPath().endsWith("/"))
            {
                // TODO need URI util that handles param and query without reconstructing entire URI with scheme and authority
                String parameter = uri.getParam();
                uri.path(uri.getCanonicalPath() + "/");
                uri.param(parameter);
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 0);
                // TODO: can writeRedirect (override) also work for WelcomeActionType.REDIRECT?
                Response.sendRedirect(request, response, callback, uri.getPathQuery());
                return;
            }
        }

        // process optional Welcome behaviors
        if (welcome(request, response, callback))
            return;

        if (!passConditionalHeaders(request, response, content, callback))
            sendDirectory(request, response, content, callback, pathInContext);
    }

    public enum WelcomeActionType
    {
        REDIRECT,
        SERVE
    }

    /**
     * Behavior for a potential welcome action
     * as determined by {@link ResourceService#processWelcome(Request, Response)}
     *
     * <p>
     * For {@link WelcomeActionType#REDIRECT} this is the resulting `Location` response header.
     * For {@link WelcomeActionType#SERVE} this is the resulting path to for welcome serve, note that
     * this is just a path, and can point to a real file, or a dynamic handler for
     * welcome processing (such as Jetty core Handler, or EE Servlet), it's up
     * to the implementation of {@link ResourceService#welcome(Request, Response, Callback)}
     * to handle the various action types.
     * </p>
     *
     * @param type the type of action
     * @param target The target URI path of the action.
     */
    public record WelcomeAction(WelcomeActionType type, String target) {}

    private boolean welcome(Request request, Response response, Callback callback) throws IOException
    {
        WelcomeAction welcomeAction = processWelcome(request, response);
        if (welcomeAction == null)
            return false;

        welcomeActionProcess(request, response, callback, welcomeAction);
        return true;
    }

    // TODO: could use a better name
    protected void welcomeActionProcess(Request request, Response response, Callback callback, WelcomeAction welcomeAction) throws IOException
    {
        switch (welcomeAction.type)
        {
            case REDIRECT ->
            {
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 0);
                Response.sendRedirect(request, response, callback, welcomeAction.target);
            }
            case SERVE ->
            {
                // TODO : check conditional headers.
                HttpContent c = _contentFactory.getContent(welcomeAction.target);
                sendData(request, response, callback, c, List.of());
            }
        }
    }

    private WelcomeAction processWelcome(Request request, Response response) throws IOException
    {
        String welcomeTarget = _welcomeFactory.getWelcomeTarget(request);
        if (welcomeTarget == null)
            return null;

        String contextPath = request.getContext().getContextPath();

        if (LOG.isDebugEnabled())
            LOG.debug("welcome={}", welcomeTarget);

        if (_redirectWelcome)
        {
            // Redirect to the index
            response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 0);
            // TODO need URI util that handles param and query without reconstructing entire URI with scheme and authority
            HttpURI.Mutable uri = HttpURI.build(request.getHttpURI());
            String parameter = uri.getParam();
            uri.path(URIUtil.addPaths(contextPath, welcomeTarget));
            uri.param(parameter);
            return new WelcomeAction(WelcomeActionType.REDIRECT, uri.getPathQuery());
        }

        // Serve welcome file
        return new WelcomeAction(WelcomeActionType.SERVE, welcomeTarget);
    }

    private void sendDirectory(Request request, Response response, HttpContent httpContent, Callback callback, String pathInContext) throws IOException
    {
        if (!_dirAllowed)
        {
            Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403);
            return;
        }

        String base = URIUtil.addEncodedPaths(request.getHttpURI().getPath(), URIUtil.SLASH);
        String listing = ResourceListing.getAsHTML(httpContent.getResource(), base, pathInContext.length() > 1, request.getHttpURI().getQuery());
        if (listing == null)
        {
            Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403);
            return;
        }

        byte[] data = listing.getBytes(StandardCharsets.UTF_8);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/html;charset=utf-8");
        response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, data.length);
        response.write(true, ByteBuffer.wrap(data), callback);
    }

    private void sendData(Request request, Response response, Callback callback, HttpContent content, List<String> reqRanges)
    {
        long contentLength = content.getContentLengthValue();

        if (LOG.isDebugEnabled())
            LOG.debug(String.format("sendData content=%s", content));

        if (reqRanges.isEmpty())
        {
            // If there are no ranges, send the entire content.
            if (contentLength >= 0)
                putHeaders(response, content, USE_KNOWN_CONTENT_LENGTH);
            else
                putHeaders(response, content, NO_CONTENT_LENGTH);
            writeHttpContent(request, response, callback, content);
            return;
        }

        // Parse the satisfiable ranges.
        List<ByteRange> ranges = ByteRange.parse(reqRanges, contentLength);

        // If there are no satisfiable ranges, send a 416 response.
        if (ranges.isEmpty())
        {
            putHeaders(response, content, NO_CONTENT_LENGTH);
            response.getHeaders().put(HttpHeader.CONTENT_RANGE, ByteRange.toNonSatisfiableHeaderValue(contentLength));
            Response.writeError(request, response, callback, HttpStatus.RANGE_NOT_SATISFIABLE_416);
            return;
        }

        // If there is only a single valid range, send that range with a 206 response.
        if (ranges.size() == 1)
        {
            ByteRange range = ranges.get(0);
            putHeaders(response, content, range.getLength());
            response.setStatus(HttpStatus.PARTIAL_CONTENT_206);
            response.getHeaders().put(HttpHeader.CONTENT_RANGE, range.toHeaderValue(contentLength));
            Content.copy(new MultiPartByteRanges.PathContentSource(content.getResource().getPath(), range), response, callback);
            return;
        }

        // There are multiple non-overlapping ranges, send a multipart/byteranges 206 response.
        putHeaders(response, content, NO_CONTENT_LENGTH);
        response.setStatus(HttpStatus.PARTIAL_CONTENT_206);
        String contentType = "multipart/byteranges; boundary=";
        String boundary = MultiPart.generateBoundary(null, 24);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, contentType + boundary);
        MultiPartByteRanges.ContentSource byteRanges = new MultiPartByteRanges.ContentSource(boundary);
        ranges.forEach(range -> byteRanges.addPart(new MultiPartByteRanges.Part(content.getContentTypeValue(), content.getResource().getPath(), range)));
        byteRanges.close();
        Content.copy(byteRanges, response, callback);
    }

    protected void writeHttpContent(Request request, Response response, Callback callback, HttpContent content)
    {
        try
        {
            ByteBuffer buffer = content.getBuffer();
            if (buffer != null)
                response.write(true, buffer, callback);
            else
                new ContentWriterIteratingCallback(content, response, callback).iterate();
        }
        catch (Throwable x)
        {
            callback.failed(x);
        }
    }

    protected void putHeaders(Response response, HttpContent content, long contentLength)
    {
        // TODO it is very inefficient to do many put's to a HttpFields, as each put is a full iteration.
        //      it might be better remove headers en masse and then just add the extras:
        // NOTE: If these headers come from a Servlet Filter we shouldn't override them here.
//        headers.remove(EnumSet.of(
//            HttpHeader.LAST_MODIFIED,
//            HttpHeader.CONTENT_LENGTH,
//            HttpHeader.CONTENT_TYPE,
//            HttpHeader.CONTENT_ENCODING,
//            HttpHeader.ETAG,
//            HttpHeader.ACCEPT_RANGES,
//            HttpHeader.CACHE_CONTROL
//            ));
//        HttpField lm = content.getLastModified();
//        if (lm != null)
//            headers.add(lm);
//        etc.

        HttpField lm = content.getLastModified();
        if (lm != null)
            response.getHeaders().put(lm);

        if (contentLength == USE_KNOWN_CONTENT_LENGTH)
        {
            response.getHeaders().put(content.getContentLength());
        }
        else if (contentLength > NO_CONTENT_LENGTH)
        {
            response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, contentLength);
        }

        HttpField ct = content.getContentType();
        if (ct != null)
            response.getHeaders().put(ct);

        HttpField ce = content.getContentEncoding();
        if (ce != null)
            response.getHeaders().put(ce);

        if (_etags)
        {
            HttpField et = content.getETag();
            if (et != null)
                response.getHeaders().put(et);
        }

        if (_acceptRanges && !response.getHeaders().contains(HttpHeader.ACCEPT_RANGES))
            response.getHeaders().put(new PreEncodedHttpField(HttpHeader.ACCEPT_RANGES, "bytes"));
        if (_cacheControl != null && !response.getHeaders().contains(HttpHeader.CACHE_CONTROL))
            response.getHeaders().put(_cacheControl);
    }

    /**
     * @return If true, range requests and responses are supported
     */
    public boolean isAcceptRanges()
    {
        return _acceptRanges;
    }

    /**
     * @return If true, directory listings are returned if no welcome file is found. Else 403 Forbidden.
     */
    public boolean isDirAllowed()
    {
        return _dirAllowed;
    }

    /**
     * @return True if ETag processing is done
     */
    public boolean isEtags()
    {
        return _etags;
    }

    /**
     * @return Precompressed resources formats that can be used to serve compressed variant of resources.
     */
    public List<CompressedContentFormat> getPrecompressedFormats()
    {
        return _precompressedFormats;
    }

    /**
     * @return If true, welcome files are redirected rather than forwarded to.
     */
    public boolean isRedirectWelcome()
    {
        return _redirectWelcome;
    }

    public WelcomeFactory getWelcomeFactory()
    {
        return _welcomeFactory;
    }

    /**
     * @param acceptRanges If true, range requests and responses are supported
     */
    public void setAcceptRanges(boolean acceptRanges)
    {
        _acceptRanges = acceptRanges;
    }

    /**
     * @param cacheControl the cacheControl header to set on all static content.
     */
    public void setCacheControl(String cacheControl)
    {
        _cacheControl = new PreEncodedHttpField(HttpHeader.CACHE_CONTROL, cacheControl);
    }

    /**
     * @param dirAllowed If true, directory listings are returned if no welcome file is found. Else 403 Forbidden.
     */
    public void setDirAllowed(boolean dirAllowed)
    {
        _dirAllowed = dirAllowed;
    }

    /**
     * @param etags True if ETag processing is done
     */
    public void setEtags(boolean etags)
    {
        _etags = etags;
    }

    /**
     * @param gzipEquivalentFileExtensions file extensions that signify that a file is gzip compressed. Eg ".svgz"
     */
    public void setGzipEquivalentFileExtensions(List<String> gzipEquivalentFileExtensions)
    {
        _gzipEquivalentFileExtensions = gzipEquivalentFileExtensions;
    }

    /**
     * @param precompressedFormats The list of precompresed formats to serve in encoded format if matching resource found.
     * For example serve gzip encoded file if ".gz" suffixed resource is found.
     */
    public void setPrecompressedFormats(List<CompressedContentFormat> precompressedFormats)
    {
        _precompressedFormats.clear();
        _precompressedFormats.addAll(precompressedFormats);
        _preferredEncodingOrder.clear();
        _preferredEncodingOrder.addAll(_precompressedFormats.stream().map(CompressedContentFormat::getEncoding).toList());
    }

    public void setEncodingCacheSize(int encodingCacheSize)
    {
        _encodingCacheSize = encodingCacheSize;
        if (encodingCacheSize > _preferredEncodingOrderCache.size())
            _preferredEncodingOrderCache.clear();
    }

    public int getEncodingCacheSize()
    {
        return _encodingCacheSize;
    }

    /**
     * @param redirectWelcome If true, welcome files are redirected rather than forwarded to.
     * redirection is always used if the ResourceHandler is not scoped by
     * a ContextHandler
     */
    public void setRedirectWelcome(boolean redirectWelcome)
    {
        _redirectWelcome = redirectWelcome;
    }

    public void setWelcomeFactory(WelcomeFactory welcomeFactory)
    {
        _welcomeFactory = welcomeFactory;
    }

    public interface WelcomeFactory
    {
        /**
         * Finds a matching welcome target URI path for the request.
         *
         * @param request the request to use to determine the matching welcome target from.
         * @return The URI path of the matching welcome target in context or null
         * (null means no welcome target was found)
         */
        String getWelcomeTarget(Request request) throws IOException;
    }

    private static class ContentWriterIteratingCallback extends IteratingCallback
    {
        private final ReadableByteChannel source;
        private final Content.Sink sink;
        private final Callback callback;
        private final ByteBuffer byteBuffer;

        public ContentWriterIteratingCallback(HttpContent content, Response target, Callback callback) throws IOException
        {
            // TODO: is it possible to do zero-copy transfer?
//            WritableByteChannel c = Response.asWritableByteChannel(target);
//            FileChannel fileChannel = (FileChannel) source;
//            fileChannel.transferTo(0, contentLength, c);

            this.source = Files.newByteChannel(content.getResource().getPath());
            this.sink = target;
            this.callback = callback;
            int outputBufferSize = target.getRequest().getConnectionMetaData().getHttpConfiguration().getOutputBufferSize();
            boolean useOutputDirectByteBuffers = target.getRequest().getConnectionMetaData().getHttpConfiguration().isUseOutputDirectByteBuffers();
            this.byteBuffer = useOutputDirectByteBuffers ? ByteBuffer.allocateDirect(outputBufferSize) : ByteBuffer.allocate(outputBufferSize); // TODO use pool
        }

        @Override
        protected Action process() throws Throwable
        {
            if (!source.isOpen())
                return Action.SUCCEEDED;
            byteBuffer.clear();
            int read = source.read(byteBuffer);
            if (read == -1)
            {
                IO.close(source);
                sink.write(true, BufferUtil.EMPTY_BUFFER, this);
                return Action.SCHEDULED;
            }
            byteBuffer.flip();
            sink.write(false, byteBuffer, this);
            return Action.SCHEDULED;
        }

        @Override
        protected void onCompleteSuccess()
        {
            callback.succeeded();
        }

        @Override
        protected void onCompleteFailure(Throwable x)
        {
            callback.failed(x);
        }
    }
}
