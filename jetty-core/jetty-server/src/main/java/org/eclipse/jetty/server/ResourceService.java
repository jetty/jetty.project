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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.DateParser;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.http.QuotedQualityCSV;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.URIUtil;
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

    public ResourceService()
    {
    }

    public HttpContent getContent(String path, int outputBufferSize) throws IOException
    {
        return _contentFactory.getContent(path == null ? "" : path, outputBufferSize);
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

        Enumeration<String> reqRanges = request.getHeaders().getValues(HttpHeader.RANGE.asString());
        if (!hasDefinedRange(reqRanges))
            reqRanges = null;

        boolean endsWithSlash = pathInContext.endsWith(URIUtil.SLASH);
        boolean checkPrecompressedVariants = _precompressedFormats.size() > 0 && !endsWithSlash && reqRanges == null;

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
     *     For {@link WelcomeActionType#REDIRECT} this is the resulting `Location` response header.
     *     For {@link WelcomeActionType#SERVE} this is the resulting path to for welcome serve, note that
     *     this is just a path, and can point to a real file, or a dynamic handler for
     *     welcome processing (such as Jetty core Handler, or EE Servlet), it's up
     *     to the implementation of {@link ResourceService#welcome(Request, Response, Callback)}
     *     to handle the various action types.
     * </p>
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
                // TODO output buffer size????
                HttpContent c = _contentFactory.getContent(welcomeAction.target, 16 * 1024);
                sendData(request, response, callback, c, null);
            }
        };
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
        String dir = httpContent.getResource().getListHTML(base, pathInContext.length() > 1, request.getHttpURI().getQuery());
        if (dir == null)
        {
            Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403);
            return;
        }

        byte[] data = dir.getBytes(StandardCharsets.UTF_8);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/html;charset=utf-8");
        response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, data.length);
        response.write(true, ByteBuffer.wrap(data), callback);
    }

    private boolean sendData(Request request, Response response, Callback callback, HttpContent content, Enumeration<String> reqRanges) throws IOException
    {
        long contentLength = content.getContentLengthValue();

        if (LOG.isDebugEnabled())
            LOG.debug(String.format("sendData content=%s", content));

        if (reqRanges == null || !reqRanges.hasMoreElements())
        {
            // if there were no ranges, send entire entity

            // write the headers
            if (contentLength >= 0)
                putHeaders(response, content, USE_KNOWN_CONTENT_LENGTH);
            else
                putHeaders(response, content, NO_CONTENT_LENGTH);

            // write the content
            writeHttpContent(request, response, callback, content);
        }
        else
        {
            throw new UnsupportedOperationException("TODO ranges not yet supported");
            // TODO rewrite with ByteChannel only which should simplify HttpContentRangeWriter as HttpContent's Path always provides a SeekableByteChannel
            //      but MultiPartOutputStream also needs to be rewritten.
/*
            // Parse the satisfiable ranges
            List<InclusiveByteRange> ranges = InclusiveByteRange.satisfiableRanges(reqRanges, contentLength);

            //  if there are no satisfiable ranges, send 416 response
            if (ranges == null || ranges.size() == 0)
            {
                putHeaders(response, content, USE_KNOWN_CONTENT_LENGTH);
                response.getHeaders().put(HttpHeader.CONTENT_RANGE,
                    InclusiveByteRange.to416HeaderRangeString(contentLength));
                writeHttpError(request, response, callback, HttpStatus.RANGE_NOT_SATISFIABLE_416);
                return true;
            }

            //  if there is only a single valid range (must be satisfiable
            //  since were here now), send that range with a 216 response
            if (ranges.size() == 1)
            {
                InclusiveByteRange singleSatisfiableRange = ranges.iterator().next();
                long singleLength = singleSatisfiableRange.getSize();
                putHeaders(response, content, singleLength);
                response.setStatus(206);
                if (!response.getHeaders().contains(HttpHeader.DATE.asString()))
                    response.getHeaders().addDateField(HttpHeader.DATE.asString(), System.currentTimeMillis());
                response.getHeaders().put(HttpHeader.CONTENT_RANGE,
                    singleSatisfiableRange.toHeaderRangeString(contentLength));
                writeHttpPartialContent(request, response, callback, content, singleSatisfiableRange);
                return true;
            }

            //  multiple non-overlapping valid ranges cause a multipart
            //  216 response which does not require an overall
            //  content-length header
            //
            putHeaders(response, content, NO_CONTENT_LENGTH);
            String mimetype = content.getContentTypeValue();
            if (mimetype == null)
                LOG.warn("Unknown mimetype for {}", request.getHttpURI());
            response.setStatus(206);
            if (!response.getHeaders().contains(HttpHeader.DATE.asString()))
                response.getHeaders().addDateField(HttpHeader.DATE.asString(), System.currentTimeMillis());

            // If the request has a "Request-Range" header then we need to
            // send an old style multipart/x-byteranges Content-Type. This
            // keeps Netscape and acrobat happy. This is what Apache does.
            String ctp;
            if (request.getHeaders().get(HttpHeader.REQUEST_RANGE.asString()) != null)
                ctp = "multipart/x-byteranges; boundary=";
            else
                ctp = "multipart/byteranges; boundary=";
            MultiPartOutputStream multi = new MultiPartOutputStream(out);
            response.setContentType(ctp + multi.getBoundary());

            // calculate the content-length
            int length = 0;
            String[] header = new String[ranges.size()];
            int i = 0;
            final int CRLF = "\r\n".length();
            final int DASHDASH = "--".length();
            final int BOUNDARY = multi.getBoundary().length();
            final int FIELD_SEP = ": ".length();
            for (InclusiveByteRange ibr : ranges)
            {
                header[i] = ibr.toHeaderRangeString(contentLength);
                if (i > 0) // in-part
                    length += CRLF;
                length += DASHDASH + BOUNDARY + CRLF;
                if (mimetype != null)
                    length += HttpHeader.CONTENT_TYPE.asString().length() + FIELD_SEP + mimetype.length() + CRLF;
                length += HttpHeader.CONTENT_RANGE.asString().length() + FIELD_SEP + header[i].length() + CRLF;
                length += CRLF;
                length += ibr.getSize();
                i++;
            }
            length += CRLF + DASHDASH + BOUNDARY + DASHDASH + CRLF;
            response.setContentLength(length);

            try (RangeWriter rangeWriter = HttpContentRangeWriter.newRangeWriter(content))
            {
                i = 0;
                for (InclusiveByteRange ibr : ranges)
                {
                    multi.startPart(mimetype, new String[]{HttpHeader.CONTENT_RANGE + ": " + header[i]});
                    rangeWriter.writeTo(multi, ibr.getFirst(), ibr.getSize());
                    i++;
                }
            }

            multi.close();
             */
        }
        return true;
    }

    protected void writeHttpPartialContent(Request request, Response response, Callback callback, HttpContent content, InclusiveByteRange singleSatisfiableRange)
    {
        // TODO: implement this
    }

    protected void writeHttpError(Request request, Response response, Callback callback, int statusCode)
    {
        Response.writeError(request, response, callback, statusCode);
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

    private boolean hasDefinedRange(Enumeration<String> reqRanges)
    {
        return (reqRanges != null && reqRanges.hasMoreElements());
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
         *    (null means no welcome target was found)
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
