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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.http.ByteRange;
import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.EtagUtils;
import org.eclipse.jetty.http.HttpDateTime;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiPartByteRanges;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.http.QuotedQualityCSV;
import org.eclipse.jetty.http.content.HttpContent;
import org.eclipse.jetty.http.content.PreCompressedHttpContent;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.IOResources;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resource service, used by DefaultServlet and ResourceHandler
 */
public class ResourceService
{
    private static final Logger LOG = LoggerFactory.getLogger(ResourceService.class);
    private static final int NO_CONTENT_LENGTH = -1;
    private static final int USE_KNOWN_CONTENT_LENGTH = -2;
    private static final EnumSet<HttpHeader> CONTENT_HEADERS = EnumSet.of(
        HttpHeader.LAST_MODIFIED,
        HttpHeader.CONTENT_LENGTH,
        HttpHeader.CONTENT_TYPE
    );
    private static final PreEncodedHttpField ACCEPT_RANGES_BYTES = new PreEncodedHttpField(HttpHeader.ACCEPT_RANGES, "bytes");

    private final List<CompressedContentFormat> _precompressedFormats = new ArrayList<>();
    private final Map<String, List<String>> _preferredEncodingOrderCache = new ConcurrentHashMap<>();
    private final List<String> _preferredEncodingOrder = new ArrayList<>();
    private WelcomeFactory _welcomeFactory;
    private WelcomeMode _welcomeMode = WelcomeMode.SERVE;
    private boolean _etags = false;
    private List<String> _gzipEquivalentFileExtensions;
    private HttpContent.Factory _contentFactory;
    private int _encodingCacheSize = 100;
    private boolean _dirAllowed = true;
    private boolean _acceptRanges = true;
    private HttpField _cacheControl;

    public ResourceService()
    {
    }

    public HttpContent getContent(String path, Request request) throws IOException
    {
        HttpContent content = _contentFactory.getContent(path == null ? "" : path);
        if (content != null)
        {
            AliasCheck aliasCheck = ContextHandler.getContextHandler(request);
            if (aliasCheck != null && !aliasCheck.checkAlias(path, content.getResource()))
                return null;

            Collection<CompressedContentFormat> compressedContentFormats = (content.getPreCompressedContentFormats() == null)
                ? _precompressedFormats : content.getPreCompressedContentFormats();
            if (!compressedContentFormats.isEmpty())
            {
                List<String> preferredEncodingOrder = getPreferredEncodingOrder(request);
                if (!preferredEncodingOrder.isEmpty())
                {
                    for (String encoding : preferredEncodingOrder)
                    {
                        CompressedContentFormat contentFormat = isEncodingAvailable(encoding, compressedContentFormats);
                        if (contentFormat == null)
                            continue;

                        HttpContent preCompressedContent = _contentFactory.getContent(path + contentFormat.getExtension());
                        if (preCompressedContent == null)
                            continue;

                        if (aliasCheck != null && !aliasCheck.checkAlias(path, preCompressedContent.getResource()))
                            continue;

                        return new PreCompressedHttpContent(content, preCompressedContent, contentFormat);
                    }
                }
            }
        }

        return content;
    }

    public HttpContent.Factory getHttpContentFactory()
    {
        return _contentFactory;
    }

    public void setHttpContentFactory(HttpContent.Factory contentFactory)
    {
        _contentFactory = contentFactory;
    }

    /**
     * Get the cacheControl header to set on all static content..
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

    public void doGet(Request request, Response response, Callback callback, HttpContent content)
    {
        String pathInContext = Request.getPathInContext(request);

        // Is this a Range request?
        List<String> reqRanges = request.getHeaders().getValuesList(HttpHeader.RANGE.asString());
        if (!_acceptRanges && !reqRanges.isEmpty())
        {
            reqRanges = List.of();
            response.getHeaders().add(HttpHeader.ACCEPT_RANGES.asString(), "none");
        }

        boolean endsWithSlash = pathInContext.endsWith("/");

        if (LOG.isDebugEnabled())
        {
            LOG.debug(".doGet(req={}, resp={}, callback={}, content={}) pathInContext={}, reqRanges={}, endsWithSlash={}",
                request, response, callback, content, pathInContext, reqRanges, endsWithSlash);
        }

        try
        {
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
                sendRedirect(request, response, callback, URIUtil.addPaths(request.getContext().getContextPath(), pathInContext));
                return;
            }

            // Conditional response?
            if (passConditionalHeaders(request, response, content, callback))
                return;

            if (content.getPreCompressedContentFormats() == null || !content.getPreCompressedContentFormats().isEmpty())
                response.getHeaders().put(HttpHeader.VARY, HttpHeader.ACCEPT_ENCODING.asString());

            HttpField contentEncoding = content.getContentEncoding();
            if (contentEncoding != null)
                response.getHeaders().put(contentEncoding);
            else if (isImplicitlyGzippedContent(pathInContext))
                response.getHeaders().put(HttpHeader.CONTENT_ENCODING, "gzip");

            // Send the data
            sendData(request, response, callback, content, reqRanges);
        }
        catch (Throwable t)
        {
            LOG.warn("Failed to serve resource: {}", pathInContext, t);
            if (!response.isCommitted())
                writeHttpError(request, response, callback, t);
            else
                callback.failed(t);
        }
    }

    protected void writeHttpError(Request request, Response response, Callback callback, int status)
    {
        Response.writeError(request, response, callback, status);
    }

    protected void writeHttpError(Request request, Response response, Callback callback, Throwable cause)
    {
        Response.writeError(request, response, callback, cause);
    }

    protected void writeHttpError(Request request, Response response, Callback callback, int status, String msg, Throwable cause)
    {
        Response.writeError(request, response, callback, status, msg, cause);
    }

    protected void sendRedirect(Request request, Response response, Callback callback, String target)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendRedirect(req={}, resp={}, callback={}, target={})",
                request, response, callback, target);
        }

        Response.sendRedirect(request, response, callback, target);
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

    private boolean isImplicitlyGzippedContent(String path)
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

    private CompressedContentFormat isEncodingAvailable(String encoding, Collection<CompressedContentFormat> availableFormats)
    {
        if (availableFormats.isEmpty())
            return null;

        for (CompressedContentFormat format : availableFormats)
        {
            if (format.getEncoding().equals(encoding))
                return format;
        }

        if ("*".equals(encoding))
            return availableFormats.iterator().next();
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
            String ifums = null;

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
                        case IF_UNMODIFIED_SINCE -> ifums = field.getValue();
                        default ->
                        {
                        }
                    }
                }
            }

            if (_etags)
            {
                String etag = content.getETagValue();
                if (etag != null)
                {
                    // TODO: this is a hack to get the etag of the non-preCompressed version.
                    etag = EtagUtils.rewriteWithSuffix(content.getETagValue(), "");
                    if (ifm != null)
                    {
                        String matched = matchesEtag(etag, ifm);
                        if (matched == null)
                        {
                            writeHttpError(request, response, callback, HttpStatus.PRECONDITION_FAILED_412);
                            return true;
                        }
                    }

                    if (ifnm != null)
                    {
                        String matched = matchesEtag(etag, ifnm);
                        if (matched != null)
                        {
                            response.getHeaders().put(HttpHeader.ETAG, matched);
                            putNotModifiedHeaders(response, content);
                            writeHttpError(request, response, callback, HttpStatus.NOT_MODIFIED_304);
                            return true;
                        }

                        // If etag requires content to be served, then do not check if-modified-since
                        return false;
                    }
                }
            }

            // Handle if modified since
            if (ifms != null && ifnm == null)
            {
                //Get jetty's Response impl
                String mdlm = content.getLastModifiedValue();
                if (ifms.equals(mdlm))
                {
                    putNotModifiedHeaders(response, content);
                    writeHttpError(request, response, callback, HttpStatus.NOT_MODIFIED_304);
                    return true;
                }

                long ifmsl = HttpDateTime.parseToEpoch(ifms);
                if (ifmsl != -1)
                {
                    long lm = content.getResource().lastModified().toEpochMilli();
                    if (lm != -1 && lm / 1000 <= ifmsl / 1000)
                    {
                        putNotModifiedHeaders(response, content);
                        writeHttpError(request, response, callback, HttpStatus.NOT_MODIFIED_304);
                        return true;
                    }
                }
            }

            // Parse the if[un]modified dates and compare to resource
            if (ifums != null && ifm == null)
            {
                long ifumsl = HttpDateTime.parseToEpoch(ifums);
                if (ifumsl != -1)
                {
                    long lm = content.getResource().lastModified().toEpochMilli();
                    if (lm != -1 && lm / 1000 > ifumsl / 1000)
                    {
                        writeHttpError(request, response, callback, HttpStatus.PRECONDITION_FAILED_412);
                        return true;
                    }
                }
            }
        }
        catch (IllegalArgumentException iae)
        {
            if (!response.isCommitted())
                writeHttpError(request, response, callback, HttpStatus.BAD_REQUEST_400, null, iae);
            throw iae;
        }

        return false;
    }

    /**
     * Find a matches between a Content ETag and a Request Field ETag reference.
     * @param contentETag the content etag to match against (can be null)
     * @param requestEtag the request etag (can be null, a single entry, or even a CSV list)
     * @return the matched etag, or null if no matches.
     */
    private String matchesEtag(String contentETag, String requestEtag)
    {
        if (contentETag == null || requestEtag == null)
        {
            return null;
        }

        // Per https://www.rfc-editor.org/rfc/rfc9110#section-8.8.3
        // An Etag header field value can contain a "," (comma) within itself.
        //   If-Match: W/"abc,xyz", "123456"
        // This means we have to parse with QuotedCSV all the time, as we cannot just
        // test for the existence of a "," (comma) in the value to know if it's delimited or not
        QuotedCSV quoted = new QuotedCSV(true, requestEtag);
        for (String tag : quoted)
        {
            if (EtagUtils.matches(contentETag, tag))
            {
                return tag;
            }
        }

        // no matches
        return null;
    }

    protected void sendWelcome(HttpContent content, String pathInContext, boolean endsWithSlash, Request request, Response response, Callback callback) throws Exception
    {
        if (!Objects.requireNonNull(content).getResource().isDirectory())
            throw new IllegalArgumentException("content must be a directory");

        if (LOG.isDebugEnabled())
            LOG.debug("sendWelcome(content={}, pathInContext={}, endsWithSlash={}, req={}, resp={}, callback={})",
                content, pathInContext, endsWithSlash, request, response, callback);

        // Redirect to directory
        if (!endsWithSlash)
        {
            HttpURI.Mutable uri = HttpURI.build(request.getHttpURI());
            if (!uri.getCanonicalPath().endsWith("/"))
            {
                uri.path(uri.getCanonicalPath() + "/");
                response.getHeaders().put(HttpFields.CONTENT_LENGTH_0);
                sendRedirect(request, response, callback, uri.getPathQuery());
                return;
            }
        }

        // process optional Welcome behaviors
        if (welcome(content, request, response, callback))
            return;

        if (!passConditionalHeaders(request, response, content, callback))
            sendDirectory(request, response, content, callback, pathInContext);
    }

    /**
     * <p>How welcome targets should be processed.</p>
     */
    public enum WelcomeMode
    {
        /**
         * The welcome target is used as the location for a redirect response,
         * sent by {@link #redirectWelcome(Request, Response, Callback, String)}.
         */
        REDIRECT,
        /**
         * The welcome target is served by
         * {@link #serveWelcome(Request, Response, Callback, String)}.
         */
        SERVE,
        /**
         * The welcome target is re-handled by
         * {@link #rehandleWelcome(Request, Response, Callback, String)}.
         */
        REHANDLE
    }

    /**
     * <p>A welcome target paired with how to process it.</p>
     *
     * @param target the welcome target
     * @param mode the welcome mode
     */
    public record WelcomeAction(String target, WelcomeMode mode)
    {
    }

    private boolean welcome(HttpContent content, Request request, Response response, Callback callback) throws Exception
    {
        WelcomeAction welcomeAction = processWelcome(content, request);
        if (LOG.isDebugEnabled())
            LOG.debug("welcome(req={}, rsp={}, cbk={}) welcomeAction={}", request, response, callback, welcomeAction);

        if (welcomeAction == null)
            return false;

        handleWelcomeAction(request, response, callback, welcomeAction);
        return true;
    }

    protected void handleWelcomeAction(Request request, Response response, Callback callback, WelcomeAction welcomeAction) throws Exception
    {
        switch (welcomeAction.mode)
        {
            case REDIRECT -> redirectWelcome(request, response, callback, welcomeAction.target);
            case SERVE ->
                // TODO : check conditional headers.
                serveWelcome(request, response, callback, welcomeAction.target);
            case REHANDLE -> rehandleWelcome(request, response, callback, welcomeAction.target);
        }
    }

    /**
     * <p>Redirects to the given welcome target.</p>
     * <p>Implementations should use HTTP redirect APIs to generate
     * a redirect response whose location is the welcome target.</p>
     *
     * @param request the request to redirect
     * @param response the response
     * @param callback the callback to complete
     * @param welcomeTarget the welcome target to redirect to
     * @throws Exception if the redirection fails
     */
    protected void redirectWelcome(Request request, Response response, Callback callback, String welcomeTarget) throws Exception
    {
        response.getHeaders().put(HttpFields.CONTENT_LENGTH_0);
        sendRedirect(request, response, callback, welcomeTarget);
    }

    /**
     * <p>Serves the given welcome target.</p>
     * <p>Implementations should write the welcome
     * target bytes over the network to the client.</p>
     *
     * @param request the request
     * @param response the response
     * @param callback the callback to complete
     * @param welcomeTarget the welcome target to serve
     * @throws Exception if serving the welcome target fails
     */
    protected void serveWelcome(Request request, Response response, Callback callback, String welcomeTarget) throws Exception
    {
        HttpContent c = _contentFactory.getContent(welcomeTarget);
        sendData(request, response, callback, c, List.of());
    }

    /**
     * <p>Rehandles the given welcome target.</p>
     * <p>Implementations should call {@link Handler#handle(Request, Response, Callback)}
     * on a {@code Handler} that may handle the welcome target
     * differently from the original request.</p>
     * <p>For example, a request for {@code /ctx/} may be rewritten
     * as {@code /ctx/index.jsp} and rehandled from the {@code Server}.
     * In this example, the rehandling of {@code /ctx/index.jsp} may
     * trigger a different code path so that the rewritten request
     * is handled by a different {@code Handler}, in this example
     * one that knows how to handle JSP resources.</p>
     *
     * @param request the request
     * @param response the response
     * @param callback the callback to complete
     * @param welcomeTarget the welcome target to rehandle to
     * @throws Exception if the rehandling fails
     */
    protected void rehandleWelcome(Request request, Response response, Callback callback, String welcomeTarget) throws Exception
    {
        Response.writeError(request, response, callback, HttpStatus.INTERNAL_SERVER_ERROR_500);
    }

    private WelcomeAction processWelcome(HttpContent content, Request request) throws IOException
    {
        String welcomeTarget = getWelcomeFactory().getWelcomeTarget(content, request);
        if (welcomeTarget == null)
            return null;

        String contextPath = request.getContext().getContextPath();
        WelcomeMode welcomeMode = getWelcomeMode();

        welcomeTarget = switch (welcomeMode)
        {
            case REDIRECT, REHANDLE -> HttpURI.build(request.getHttpURI())
                .path(URIUtil.addPaths(contextPath, welcomeTarget))
                .getPathQuery();
            case SERVE -> welcomeTarget;
        };

        if (LOG.isDebugEnabled())
            LOG.debug("welcome {} {}", welcomeMode, welcomeTarget);

        return new WelcomeAction(welcomeTarget, welcomeMode);
    }

    private void sendDirectory(Request request, Response response, HttpContent httpContent, Callback callback, String pathInContext)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendDirectory(req={}, resp={}, content={}, callback={}, pathInContext={})",
                request, response, httpContent, callback, pathInContext);
        }
        if (!_dirAllowed)
        {
            writeHttpError(request, response, callback, HttpStatus.FORBIDDEN_403);
            return;
        }

        String base = URIUtil.addEncodedPaths(request.getHttpURI().getPath(), "/");
        String listing = ResourceListing.getAsXHTML(httpContent.getResource(), base, pathInContext.length() > 1, request.getHttpURI().getQuery());
        if (listing == null)
        {
            writeHttpError(request, response, callback, HttpStatus.FORBIDDEN_403);
            return;
        }

        String characterEncoding = httpContent.getCharacterEncoding();
        Charset charset = characterEncoding == null ? StandardCharsets.UTF_8 : Charset.forName(characterEncoding);
        byte[] data = listing.getBytes(charset);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/html;charset=" + charset.name());
        response.getHeaders().put(HttpHeader.CONTENT_LENGTH, data.length);
        response.write(true, ByteBuffer.wrap(data), callback);
    }

    private void sendData(Request request, Response response, Callback callback, HttpContent content, List<String> reqRanges) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendData(req={}, resp={}, callback={}) content={}, reqRanges={})",
                request, response, callback, content, reqRanges);
        }

        long contentLength = content.getContentLengthValue();
        callback = Callback.from(callback, content::release);

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

            // TODO use a buffer pool
            IOResources.copy(content.getResource(), response, null, 0, false, range.first(), range.getLength(), callback);
            return;
        }

        // There are multiple non-overlapping ranges, send a multipart/byteranges 206 response.
        response.setStatus(HttpStatus.PARTIAL_CONTENT_206);
        String contentType = "multipart/byteranges; boundary=";
        String boundary = MultiPart.generateBoundary(null, 24);
        MultiPartByteRanges.ContentSource byteRanges = new MultiPartByteRanges.ContentSource(boundary);
        ranges.forEach(range -> byteRanges.addPart(new MultiPartByteRanges.Part(content.getContentTypeValue(), content.getResource(), range, contentLength, request.getComponents().getByteBufferPool()))); // TODO use a sized pool
        byteRanges.close();
        long partsContentLength = byteRanges.getLength();
        putHeaders(response, content, partsContentLength);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, contentType + boundary);
        Content.copy(byteRanges, response, callback);
    }

    protected void writeHttpContent(Request request, Response response, Callback callback, HttpContent content)
    {
        try
        {
            ByteBuffer buffer = content.getByteBuffer(); // this buffer is going to be consumed by response.write()
            if (buffer != null)
            {
                response.write(true, buffer, callback);
            }
            else
            {
                IOResources.copy(
                    content.getResource(),
                    response, request.getComponents().getByteBufferPool(),
                    request.getConnectionMetaData().getHttpConfiguration().getOutputBufferSize(),
                    request.getConnectionMetaData().getHttpConfiguration().isUseOutputDirectByteBuffers(),
                    callback);
            }
        }
        catch (Throwable x)
        {
            content.release();
            callback.failed(x);
        }
    }

    protected void putHeaders(Response response, HttpContent content, long contentLength)
    {
        HttpFields.Mutable headers = response.getHeaders();

        // Existing etags have priority over content etags (often set by compression handler)
        if (_etags && !headers.contains(HttpHeader.ETAG))
        {
            HttpField et = content.getETag();
            if (et != null)
                headers.add(et);
        }

        // Existing content encoding is kept only if not set for content.
        HttpField ce = content.getContentEncoding();
        if (ce != null)
            headers.put(ce);

        // Remove content headers and re-add if we have them
        headers.remove(CONTENT_HEADERS);
        HttpField lm = content.getLastModified();
        if (lm != null)
            headers.add(lm);
        if (contentLength == USE_KNOWN_CONTENT_LENGTH)
            headers.add(content.getContentLength());
        else if (contentLength > NO_CONTENT_LENGTH)
            headers.add(HttpHeader.CONTENT_LENGTH, contentLength);
        HttpField ct = content.getContentType();
        if (ct != null)
            headers.add(ct);

        putHeaders(response);
    }

    protected void putNotModifiedHeaders(Response response, HttpContent content)
    {
        HttpFields.Mutable headers = response.getHeaders();
        // send only lastModified, as it is too difficult to determine the etag with compression
        HttpField lm = content.getLastModified();
        if (lm != null)
            headers.put(lm);

        putHeaders(response);
    }

    protected void putHeaders(Response response)
    {
        HttpFields.Mutable headers = response.getHeaders();
        if (_acceptRanges && !headers.contains(HttpHeader.ACCEPT_RANGES))
            headers.add(ACCEPT_RANGES_BYTES);
        if (_cacheControl != null && !headers.contains(HttpHeader.CACHE_CONTROL))
            headers.add(_cacheControl);
    }

    /**
     * @return If true, range requests and responses are supported
     */
    public boolean isAcceptRanges()
    {
        return _acceptRanges;
    }

    /**
     * @return If true, directory listings are returned if no welcome target is found. Else 403 Forbidden.
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

    public WelcomeMode getWelcomeMode()
    {
        return _welcomeMode;
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
     * Set the cacheControl header to set on all static content..
     * @param cacheControl the cacheControl header to set on all static content.
     */
    public void setCacheControl(String cacheControl)
    {
        _cacheControl = new PreEncodedHttpField(HttpHeader.CACHE_CONTROL, cacheControl);
    }

    /**
     * @param dirAllowed If true, directory listings are returned if no welcome target is found. Else 403 Forbidden.
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
     * Set file extensions that signify that a file is gzip compressed. Eg ".svgz".
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
        // TODO: this preferred encoding order should be a separate configurable
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

    public void setWelcomeMode(WelcomeMode welcomeMode)
    {
        _welcomeMode = Objects.requireNonNull(welcomeMode);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x(contentFactory=%s, dirAllowed=%b, welcomeMode=%s)", this.getClass().getName(), this.hashCode(), this._contentFactory, this._dirAllowed, this._welcomeMode);
    }

    public void setWelcomeFactory(WelcomeFactory welcomeFactory)
    {
        _welcomeFactory = welcomeFactory;
    }

    public interface WelcomeFactory
    {
        /**
         * Finds a matching welcome target for the request.
         *
         * @param request the request to use to determine the matching welcome target
         * @return The URI path of the matching welcome target in context or null
         * if no welcome target was found
         */
        String getWelcomeTarget(HttpContent content, Request request) throws IOException;
    }
}
