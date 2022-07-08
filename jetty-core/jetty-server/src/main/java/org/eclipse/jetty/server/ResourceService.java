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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collection;
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
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Arrays.stream;

/**
 * Resource service, used by DefaultServlet and ResourceHandler
 */
public class ResourceService
{
    private static final Logger LOG = LoggerFactory.getLogger(ResourceService.class);

    private static final int NO_CONTENT_LENGTH = -1;
    private static final int USE_KNOWN_CONTENT_LENGTH = -2;

    private Resource _defaultStylesheet;
    private Resource _stylesheet;
    private boolean _pathInfoOnly = false;
    private CompressedContentFormat[] _precompressedFormats = new CompressedContentFormat[0];
    private WelcomeFactory _welcomeFactory;
    private boolean _redirectWelcome = false;
    private boolean _etags = false;
    private List<String> _gzipEquivalentFileExtensions;
    private HttpContent.ContentFactory _contentFactory;
    private final Map<String, List<String>> _preferredEncodingOrderCache = new ConcurrentHashMap<>();
    private String[] _preferredEncodingOrder = new String[0];
    private int _encodingCacheSize = 100;
    private boolean _dirAllowed = true;
    private boolean _acceptRanges = true;
    private HttpField _cacheControl;

    public ResourceService()
    {
    }

    public HttpContent getContent(String servletPath, int outputBufferSize) throws IOException
    {
        return _contentFactory.getContent(servletPath, outputBufferSize);
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

    /**
     * @return Returns the stylesheet as a Resource.
     */
    public Resource getStylesheet()
    {
        if (_stylesheet != null)
        {
            return _stylesheet;
        }
        else
        {
            if (_defaultStylesheet == null)
            {
                _defaultStylesheet = getDefaultStylesheet();
            }
            return _defaultStylesheet;
        }
    }

    public static Resource getDefaultStylesheet()
    {
        // TODO the returned path should point to the classpath.
        // This points to a non-existent file '/jetty-dir.css'.
        return Resource.newResource(Path.of("/jetty-dir.css"));
    }

    public void doGet(GenericRequest request, GenericResponse response, Callback callback, HttpContent content) throws Exception
    {
        String pathInContext = request.getPathInContext();

        // Is this a Range request?
        Enumeration<String> reqRanges = request.getHeaderValues(HttpHeader.RANGE.asString());
        if (!hasDefinedRange(reqRanges))
            reqRanges = null;

        boolean endsWithSlash = pathInContext.endsWith(URIUtil.SLASH);
        boolean checkPrecompressedVariants = _precompressedFormats.length > 0 && !endsWithSlash && reqRanges == null;

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
                response.sendRedirect(callback, URIUtil.addPaths(request.getContextPath(), pathInContext));
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
                response.putHeader(HttpHeader.VARY, HttpHeader.ACCEPT_ENCODING.asString());

                List<String> preferredEncodings = getPreferredEncodingOrder(request);
                CompressedContentFormat precompressedContentEncoding = getBestPrecompressedContent(preferredEncodings, precompressedContents.keySet());
                if (precompressedContentEncoding != null)
                {
                    HttpContent precompressedContent = precompressedContents.get(precompressedContentEncoding);
                    if (LOG.isDebugEnabled())
                        LOG.debug("precompressed={}", precompressedContent);
                    content = precompressedContent;
                    response.putHeader(HttpHeader.CONTENT_ENCODING, precompressedContentEncoding.getEncoding());
                }
            }

            // TODO this should be done by HttpContent#getContentEncoding
            if (isGzippedContent(pathInContext))
                response.putHeader(HttpHeader.CONTENT_ENCODING, "gzip");

            // Send the data
            sendData(request, response, callback, content, reqRanges);
        }
        // Can be thrown from contentFactory.getContent() call when using invalid characters
        catch (InvalidPathException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("InvalidPathException for pathInContext: {}", pathInContext, e);
            response.writeError(callback, HttpStatus.NOT_FOUND_404);
        }
        catch (IllegalArgumentException e)
        {
            LOG.warn("Failed to serve resource: {}", pathInContext, e);
            if (!response.isCommitted())
                response.writeError(callback, HttpStatus.INTERNAL_SERVER_ERROR_500);
        }
    }

    private List<String> getPreferredEncodingOrder(GenericRequest request)
    {
        Enumeration<String> headers = request.getHeaderValues(HttpHeader.ACCEPT_ENCODING.asString());
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
    protected boolean passConditionalHeaders(GenericRequest request, GenericResponse response, HttpContent content, Callback callback) throws IOException
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
                        response.writeError(callback, HttpStatus.PRECONDITION_FAILED_412);
                        return true;
                    }
                }

                if (ifnm != null && etag != null)
                {
                    // Handle special case of exact match OR gzip exact match
                    if (CompressedContentFormat.tagEquals(etag, ifnm) && ifnm.indexOf(',') < 0)
                    {
                        response.writeError(callback, HttpStatus.NOT_MODIFIED_304);
                        return true;
                    }

                    // Handle list of tags
                    QuotedCSV quoted = new QuotedCSV(true, ifnm);
                    for (String tag : quoted)
                    {
                        if (CompressedContentFormat.tagEquals(etag, tag))
                        {
                            response.writeError(callback, HttpStatus.NOT_MODIFIED_304);
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
                    response.writeError(callback, HttpStatus.NOT_MODIFIED_304);
                    return true;
                }

                long ifmsl = request.getHeaderDate(HttpHeader.IF_MODIFIED_SINCE.asString());
                if (ifmsl != -1 && Files.getLastModifiedTime(content.getResource().getPath()).toMillis() / 1000 <= ifmsl / 1000)
                {
                    response.writeError(callback, HttpStatus.NOT_MODIFIED_304);
                    return true;
                }
            }

            // Parse the if[un]modified dates and compare to resource
            if (ifums != -1 && Files.getLastModifiedTime(content.getResource().getPath()).toMillis() / 1000 > ifums / 1000)
            {
                response.writeError(callback, HttpStatus.PRECONDITION_FAILED_412);
                return true;
            }
        }
        catch (IllegalArgumentException iae)
        {
            if (!response.isCommitted())
                response.writeError(callback, HttpStatus.BAD_REQUEST_400);
            throw iae;
        }

        return false;
    }

    protected void sendWelcome(HttpContent content, String pathInContext, boolean endsWithSlash, GenericRequest request, GenericResponse response, Callback callback) throws Exception
    {
        // Redirect to directory
        if (!endsWithSlash)
        {
            // TODO need helper code to edit URIs
            StringBuilder buf = new StringBuilder(request.getHttpURI().asString());
            int param = buf.lastIndexOf(";");
            if (param < 0 || buf.lastIndexOf("/", param) > 0)
                buf.append('/');
            else
                buf.insert(param, '/');
            String q = request.getHttpURI().getQuery();
            if (q != null && q.length() != 0)
            {
                buf.append('?');
                buf.append(q);
            }
            response.putHeaderLong(HttpHeader.CONTENT_LENGTH, 0);
            response.sendRedirect(callback, buf.toString());
            return;
        }

        // look for a welcome file
        if (welcome(request, response, callback))
            return;

        if (!passConditionalHeaders(request, response, content, callback))
            sendDirectory(request, response, content, callback, pathInContext);
    }

    protected boolean welcome(GenericRequest request, GenericResponse response, Callback callback) throws IOException
    {
        String pathInContext = request.getPathInContext();
        String welcome = _welcomeFactory == null ? null : _welcomeFactory.getWelcomeFile(pathInContext);
        if (welcome != null)
        {
            String contextPath = request.getContextPath();

            if (_pathInfoOnly)
                welcome = URIUtil.addPaths(contextPath, welcome);

            if (LOG.isDebugEnabled())
                LOG.debug("welcome={}", welcome);

            if (_redirectWelcome)
            {
                // Redirect to the index
                response.putHeaderLong(HttpHeader.CONTENT_LENGTH, 0);

                // TODO need helper code to edit URIs
                String uri = URIUtil.encodePath(URIUtil.addPaths(request.getContextPath(), welcome));
                String q = request.getHttpURI().getQuery();
                if (q != null && !q.isEmpty())
                    uri += "?" + q;

                response.sendRedirect(callback, uri);
                return true;
            }

            // Serve welcome file
            HttpContent c = _contentFactory.getContent(welcome, response.getOutputBufferSize());
            sendData(request, response, callback, c, null);
            return true;
        }
        return false;
    }

    private void sendDirectory(GenericRequest request, GenericResponse response, HttpContent httpContent, Callback callback, String pathInContext) throws IOException
    {
        if (!_dirAllowed)
        {
            response.writeError(callback, HttpStatus.FORBIDDEN_403);
            return;
        }

        String base = URIUtil.addEncodedPaths(request.getHttpURI().getPath(), URIUtil.SLASH);
        String dir = httpContent.getResource().getListHTML(base, pathInContext.length() > 1, request.getHttpURI().getQuery());
        if (dir == null)
        {
            response.writeError(callback, HttpStatus.FORBIDDEN_403);
            return;
        }

        byte[] data = dir.getBytes(StandardCharsets.UTF_8);
        response.putHeader(HttpHeader.CONTENT_TYPE, "text/html;charset=utf-8");
        response.putHeaderLong(HttpHeader.CONTENT_LENGTH, data.length);
        response.writeLast(ByteBuffer.wrap(data), callback);
    }

    private boolean sendData(GenericRequest request, GenericResponse response, Callback callback, HttpContent content, Enumeration<String> reqRanges) throws IOException
    {
        long contentLength = content.getContentLengthValue();

        if (LOG.isDebugEnabled())
            LOG.debug(String.format("sendData content=%s", content));

        if (reqRanges == null || !reqRanges.hasMoreElements() || contentLength < 0)
        {
            // if there were no ranges, send entire entity

            // write the headers
            putHeaders(response, content, USE_KNOWN_CONTENT_LENGTH);

            // write the content
            response.write(content, callback);
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
                sendStatus(416, response, callback);
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
                writeContent(content, out, singleSatisfiableRange.getFirst(), singleLength);
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

    private void putHeaders(GenericResponse response, HttpContent content, long contentLength)
    {
        // TODO it is very inefficient to do many put's to a HttpFields, as each put is a full iteration.
        //      it might be better remove headers en masse and then just add the extras:
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
            response.putHeader(lm);

        if (contentLength == USE_KNOWN_CONTENT_LENGTH)
        {
            response.putHeader(content.getContentLength());
        }
        else if (contentLength > NO_CONTENT_LENGTH)
        {
            response.putHeaderLong(HttpHeader.CONTENT_LENGTH, contentLength);
        }

        HttpField ct = content.getContentType();
        if (ct != null)
            response.putHeader(ct);

        HttpField ce = content.getContentEncoding();
        if (ce != null)
            response.putHeader(ce);

        if (_etags)
        {
            HttpField et = content.getETag();
            if (et != null)
                response.putHeader(et);
        }

        if (_acceptRanges && !response.containsHeader(HttpHeader.ACCEPT_RANGES))
            response.putHeader(new PreEncodedHttpField(HttpHeader.ACCEPT_RANGES, "bytes"));
        if (_cacheControl != null && !response.containsHeader(HttpHeader.CACHE_CONTROL))
            response.putHeader(_cacheControl);
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
    public CompressedContentFormat[] getPrecompressedFormats()
    {
        return _precompressedFormats;
    }

    /**
     * @return true, only the path info will be applied to the resourceBase
     */
    public boolean isPathInfoOnly()
    {
        return _pathInfoOnly;
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
    public void setPrecompressedFormats(CompressedContentFormat[] precompressedFormats)
    {
        _precompressedFormats = precompressedFormats;
        _preferredEncodingOrder = stream(_precompressedFormats).map(CompressedContentFormat::getEncoding).toArray(String[]::new);
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
     * @param pathInfoOnly true, only the path info will be applied to the resourceBase
     */
    public void setPathInfoOnly(boolean pathInfoOnly)
    {
        _pathInfoOnly = pathInfoOnly;
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

    /**
     * @param stylesheet The location of the stylesheet to be used as a String.
     */
    // TODO accept a Resource instead of a String?
    public void setStylesheet(String stylesheet)
    {
        try
        {
            _stylesheet = Resource.newResource(Path.of(stylesheet));
            if (!_stylesheet.exists())
            {
                LOG.warn("unable to find custom stylesheet: {}", stylesheet);
                _stylesheet = null;
            }
        }
        catch (Exception e)
        {
            LOG.warn("Invalid StyleSheet reference: {}", stylesheet, e);
            throw new IllegalArgumentException(stylesheet);
        }
    }

    public interface WelcomeFactory
    {

        /**
         * Finds a matching welcome file for the supplied {@link Resource}.
         *
         * @param pathInContext the path of the request
         * @return The path of the matching welcome file in context or null.
         */
        String getWelcomeFile(String pathInContext) throws IOException;
    }

    public interface GenericRequest
    {
        Collection<HttpField> getHeaders();
        Enumeration<String> getHeaderValues(String name);
        long getHeaderDate(String name);

        HttpURI getHttpURI();

        String getPathInContext();
        String getContextPath();
    }

    public interface GenericResponse
    {
        boolean containsHeader(HttpHeader header);
        void putHeader(HttpField header);
        void putHeader(HttpHeader header, String value);
        void putHeaderLong(HttpHeader name, long value);

        boolean isCommitted();

        int getOutputBufferSize();
        boolean isUseOutputDirectByteBuffers();

        void sendRedirect(Callback callback, String uri);
        void writeError(Callback callback, int status);

        void write(HttpContent content, Callback callback);
        void writeLast(ByteBuffer byteBuffer, Callback callback);
    }
}
