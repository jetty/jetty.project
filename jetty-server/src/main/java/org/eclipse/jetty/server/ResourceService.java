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

package org.eclipse.jetty.server;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.servlet.AsyncContext;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.DateParser;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.http.QuotedQualityCSV;
import org.eclipse.jetty.io.WriterOutputStream;
import org.eclipse.jetty.server.resource.HttpContentRangeWriter;
import org.eclipse.jetty.server.resource.RangeWriter;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.MultiPartOutputStream;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

import static java.util.Arrays.stream;
import static java.util.Collections.emptyList;
import static org.eclipse.jetty.http.HttpHeaderValue.IDENTITY;

/**
 * Abstract resource service, used by DefaultServlet and ResourceHandler
 */
public class ResourceService
{
    private static final Logger LOG = Log.getLogger(ResourceService.class);

    private static final PreEncodedHttpField ACCEPT_RANGES = new PreEncodedHttpField(HttpHeader.ACCEPT_RANGES, "bytes");

    private HttpContent.ContentFactory _contentFactory;
    private WelcomeFactory _welcomeFactory;
    private boolean _acceptRanges = true;
    private boolean _dirAllowed = true;
    private boolean _redirectWelcome = false;
    private CompressedContentFormat[] _precompressedFormats = new CompressedContentFormat[0];
    private String[] _preferredEncodingOrder = new String[0];
    private final Map<String, List<String>> _preferredEncodingOrderCache = new ConcurrentHashMap<>();
    private int _encodingCacheSize = 100;
    private boolean _pathInfoOnly = false;
    private boolean _etags = false;
    private HttpField _cacheControl;
    private List<String> _gzipEquivalentFileExtensions;

    public HttpContent.ContentFactory getContentFactory()
    {
        return _contentFactory;
    }

    public void setContentFactory(HttpContent.ContentFactory contentFactory)
    {
        _contentFactory = contentFactory;
    }

    public WelcomeFactory getWelcomeFactory()
    {
        return _welcomeFactory;
    }

    public void setWelcomeFactory(WelcomeFactory welcomeFactory)
    {
        _welcomeFactory = welcomeFactory;
    }

    public boolean isAcceptRanges()
    {
        return _acceptRanges;
    }

    public void setAcceptRanges(boolean acceptRanges)
    {
        _acceptRanges = acceptRanges;
    }

    public boolean isDirAllowed()
    {
        return _dirAllowed;
    }

    public void setDirAllowed(boolean dirAllowed)
    {
        _dirAllowed = dirAllowed;
    }

    public boolean isRedirectWelcome()
    {
        return _redirectWelcome;
    }

    public void setRedirectWelcome(boolean redirectWelcome)
    {
        _redirectWelcome = redirectWelcome;
    }

    public CompressedContentFormat[] getPrecompressedFormats()
    {
        return _precompressedFormats;
    }

    public void setPrecompressedFormats(CompressedContentFormat[] precompressedFormats)
    {
        _precompressedFormats = precompressedFormats;
        _preferredEncodingOrder = stream(_precompressedFormats).map(f -> f._encoding).toArray(String[]::new);
    }

    public void setEncodingCacheSize(int encodingCacheSize)
    {
        _encodingCacheSize = encodingCacheSize;
    }

    public int getEncodingCacheSize()
    {
        return _encodingCacheSize;
    }

    public boolean isPathInfoOnly()
    {
        return _pathInfoOnly;
    }

    public void setPathInfoOnly(boolean pathInfoOnly)
    {
        _pathInfoOnly = pathInfoOnly;
    }

    public boolean isEtags()
    {
        return _etags;
    }

    public void setEtags(boolean etags)
    {
        _etags = etags;
    }

    public HttpField getCacheControl()
    {
        return _cacheControl;
    }

    public void setCacheControl(HttpField cacheControl)
    {
        _cacheControl = cacheControl;
    }

    public List<String> getGzipEquivalentFileExtensions()
    {
        return _gzipEquivalentFileExtensions;
    }

    public void setGzipEquivalentFileExtensions(List<String> gzipEquivalentFileExtensions)
    {
        _gzipEquivalentFileExtensions = gzipEquivalentFileExtensions;
    }

    public boolean doGet(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException
    {
        String servletPath = null;
        String pathInfo = null;
        Enumeration<String> reqRanges = null;
        boolean included = request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI) != null;
        if (included)
        {
            servletPath = _pathInfoOnly ? "/" : (String)request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH);
            pathInfo = (String)request.getAttribute(RequestDispatcher.INCLUDE_PATH_INFO);
            if (servletPath == null)
            {
                servletPath = request.getServletPath();
                pathInfo = request.getPathInfo();
            }
        }
        else
        {
            servletPath = _pathInfoOnly ? "/" : request.getServletPath();
            pathInfo = request.getPathInfo();

            // Is this a Range request?
            reqRanges = request.getHeaders(HttpHeader.RANGE.asString());
            if (!hasDefinedRange(reqRanges))
                reqRanges = null;
        }

        String pathInContext = URIUtil.addPaths(servletPath, pathInfo);

        boolean endsWithSlash = (pathInfo == null ? (_pathInfoOnly ? "" : servletPath) : pathInfo).endsWith(URIUtil.SLASH);
        boolean checkPrecompressedVariants = _precompressedFormats.length > 0 && !endsWithSlash && !included && reqRanges == null;

        HttpContent content = null;
        boolean releaseContent = true;
        try
        {
            // Find the content
            content = _contentFactory.getContent(pathInContext, response.getBufferSize());
            if (LOG.isDebugEnabled())
                LOG.info("content={}", content);

            // Not found?
            if (content == null || !content.getResource().exists())
            {
                if (included)
                    throw new FileNotFoundException("!" + pathInContext);
                notFound(request, response);
                return response.isCommitted();
            }

            // Directory?
            if (content.getResource().isDirectory())
            {
                sendWelcome(content, pathInContext, endsWithSlash, included, request, response);
                return true;
            }

            // Strip slash?
            if (!included && endsWithSlash && pathInContext.length() > 1)
            {
                String q = request.getQueryString();
                pathInContext = pathInContext.substring(0, pathInContext.length() - 1);
                if (q != null && q.length() != 0)
                    pathInContext += "?" + q;
                response.sendRedirect(response.encodeRedirectURL(URIUtil.addPaths(request.getContextPath(), pathInContext)));
                return true;
            }

            // Conditional response?
            if (!included && !passConditionalHeaders(request, response, content))
                return true;

            // Precompressed variant available?
            Map<CompressedContentFormat, ? extends HttpContent> precompressedContents = checkPrecompressedVariants ? content.getPrecompressedContents() : null;
            if (precompressedContents != null && precompressedContents.size() > 0)
            {
                // Tell caches that response may vary by accept-encoding
                response.addHeader(HttpHeader.VARY.asString(), HttpHeader.ACCEPT_ENCODING.asString());

                List<String> preferredEncodings = getPreferredEncodingOrder(request);
                CompressedContentFormat precompressedContentEncoding = getBestPrecompressedContent(preferredEncodings, precompressedContents.keySet());
                if (precompressedContentEncoding != null)
                {
                    HttpContent precompressedContent = precompressedContents.get(precompressedContentEncoding);
                    if (LOG.isDebugEnabled())
                        LOG.debug("precompressed={}", precompressedContent);
                    content = precompressedContent;
                    response.setHeader(HttpHeader.CONTENT_ENCODING.asString(), precompressedContentEncoding._encoding);
                }
            }

            // TODO this should be done by HttpContent#getContentEncoding
            if (isGzippedContent(pathInContext))
                response.setHeader(HttpHeader.CONTENT_ENCODING.asString(), "gzip");

            // Send the data
            releaseContent = sendData(request, response, included, content, reqRanges);
        }
        catch (IllegalArgumentException e)
        {
            LOG.warn(Log.EXCEPTION, e);
            if (!response.isCommitted())
                response.sendError(500, e.getMessage());
        }
        finally
        {
            if (releaseContent)
            {
                if (content != null)
                    content.release();
            }
        }

        return true;
    }

    private List<String> getPreferredEncodingOrder(HttpServletRequest request)
    {
        Enumeration<String> headers = request.getHeaders(HttpHeader.ACCEPT_ENCODING.asString());
        if (!headers.hasMoreElements())
            return emptyList();

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

    private CompressedContentFormat getBestPrecompressedContent(List<String> preferredEncodings, Collection<CompressedContentFormat> availableFormats)
    {
        if (availableFormats.isEmpty())
            return null;

        for (String encoding : preferredEncodings)
        {
            for (CompressedContentFormat format : availableFormats)
            {
                if (format._encoding.equals(encoding))
                    return format;
            }

            if ("*".equals(encoding))
                return availableFormats.iterator().next();

            if (IDENTITY.asString().equals(encoding))
                return null;
        }
        return null;
    }

    protected void sendWelcome(HttpContent content, String pathInContext, boolean endsWithSlash, boolean included, HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException
    {
        // Redirect to directory
        if (!endsWithSlash)
        {
            StringBuffer buf = request.getRequestURL();
            synchronized (buf)
            {
                int param = buf.lastIndexOf(";");
                if (param < 0)
                    buf.append('/');
                else
                    buf.insert(param, '/');
                String q = request.getQueryString();
                if (q != null && q.length() != 0)
                {
                    buf.append('?');
                    buf.append(q);
                }
                response.setContentLength(0);
                response.sendRedirect(response.encodeRedirectURL(buf.toString()));
            }
            return;
        }

        // look for a welcome file
        String welcome = _welcomeFactory == null ? null : _welcomeFactory.getWelcomeFile(pathInContext);

        if (welcome != null)
        {
            String servletPath = included ? (String)request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH)
                    : request.getServletPath();

            if (_pathInfoOnly)
                welcome = URIUtil.addPaths(servletPath, welcome);

            if (LOG.isDebugEnabled())
                LOG.debug("welcome={}", welcome);

            ServletContext context = request.getServletContext();

            if (_redirectWelcome || context == null)
            {
                // Redirect to the index
                response.setContentLength(0);

                String uri = URIUtil.encodePath(URIUtil.addPaths(request.getContextPath(), welcome));
                String q = request.getQueryString();
                if (q != null && !q.isEmpty())
                    uri += "?" + q;

                response.sendRedirect(response.encodeRedirectURL(uri));
                return;
            }

            RequestDispatcher dispatcher = context.getRequestDispatcher(welcome);
            if (dispatcher != null)
            {
                // Forward to the index
                if (included)
                    dispatcher.include(request, response);
                else
                {
                    request.setAttribute("org.eclipse.jetty.server.welcome", welcome);
                    dispatcher.forward(request, response);
                }
            }
            return;
        }

        if (included || passConditionalHeaders(request, response, content))
            sendDirectory(request, response, content.getResource(), pathInContext);
    }

    protected boolean isGzippedContent(String path)
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

    private boolean hasDefinedRange(Enumeration<String> reqRanges)
    {
        return (reqRanges != null && reqRanges.hasMoreElements());
    }

    protected void notFound(HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    protected void sendStatus(HttpServletResponse response, int status, Supplier<String> etag) throws IOException
    {
        response.setStatus(status);
        if (_etags && etag != null)
            response.setHeader(HttpHeader.ETAG.asString(), etag.get());
        response.flushBuffer();
    }

    /* Check modification date headers.
     */
    protected boolean passConditionalHeaders(HttpServletRequest request, HttpServletResponse response, HttpContent content)
        throws IOException
    {
        try
        {
            String ifm = null;
            String ifnm = null;
            String ifms = null;
            long ifums = -1;

            if (request instanceof Request)
            {
                // Find multiple fields by iteration as an optimization 
                HttpFields fields = ((Request)request).getHttpFields();
                for (int i = fields.size(); i-- > 0; )
                {
                    HttpField field = fields.getField(i);
                    if (field.getHeader() != null)
                    {
                        switch (field.getHeader())
                        {
                            case IF_MATCH:
                                ifm = field.getValue();
                                break;
                            case IF_NONE_MATCH:
                                ifnm = field.getValue();
                                break;
                            case IF_MODIFIED_SINCE:
                                ifms = field.getValue();
                                break;
                            case IF_UNMODIFIED_SINCE:
                                ifums = DateParser.parseDate(field.getValue());
                                break;
                            default:
                        }
                    }
                }
            }
            else
            {
                ifm = request.getHeader(HttpHeader.IF_MATCH.asString());
                ifnm = request.getHeader(HttpHeader.IF_NONE_MATCH.asString());
                ifms = request.getHeader(HttpHeader.IF_MODIFIED_SINCE.asString());
                ifums = request.getDateHeader(HttpHeader.IF_UNMODIFIED_SINCE.asString());
            }

            if (_etags)
            {
                String etag = content.getETagValue();
                if (ifm != null)
                {
                    boolean match = false;
                    if (etag != null)
                    {
                        QuotedCSV quoted = new QuotedCSV(true, ifm);
                        for (String tag : quoted)
                        {
                            if (CompressedContentFormat.tagEquals(etag, tag))
                            {
                                match = true;
                                break;
                            }
                        }
                    }

                    if (!match)
                    {
                        sendStatus(response, HttpServletResponse.SC_PRECONDITION_FAILED, null);
                        return false;
                    }
                }

                if (ifnm != null && etag != null)
                {
                    // Handle special case of exact match OR gzip exact match
                    if (CompressedContentFormat.tagEquals(etag, ifnm) && ifnm.indexOf(',') < 0)
                    {
                        sendStatus(response, HttpServletResponse.SC_NOT_MODIFIED, ifnm::toString);
                        return false;
                    }

                    // Handle list of tags
                    QuotedCSV quoted = new QuotedCSV(true, ifnm);
                    for (String tag : quoted)
                    {
                        if (CompressedContentFormat.tagEquals(etag, tag))
                        {
                            sendStatus(response, HttpServletResponse.SC_NOT_MODIFIED, tag::toString);
                            return false;
                        }
                    }

                    // If etag requires content to be served, then do not check if-modified-since
                    return true;
                }
            }

            // Handle if modified since
            if (ifms != null)
            {
                //Get jetty's Response impl
                String mdlm = content.getLastModifiedValue();
                if (mdlm != null && ifms.equals(mdlm))
                {
                    sendStatus(response, HttpServletResponse.SC_NOT_MODIFIED, content::getETagValue);
                    return false;
                }

                long ifmsl = request.getDateHeader(HttpHeader.IF_MODIFIED_SINCE.asString());
                if (ifmsl != -1 && content.getResource().lastModified() / 1000 <= ifmsl / 1000)
                {
                    sendStatus(response, HttpServletResponse.SC_NOT_MODIFIED, content::getETagValue);
                    return false;
                }
            }

            // Parse the if[un]modified dates and compare to resource
            if (ifums != -1 && content.getResource().lastModified() / 1000 > ifums / 1000)
            {
                response.sendError(HttpServletResponse.SC_PRECONDITION_FAILED);
                return false;
            }
        }
        catch (IllegalArgumentException iae)
        {
            if (!response.isCommitted())
                response.sendError(400, iae.getMessage());
            throw iae;
        }

        return true;
    }

    protected void sendDirectory(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Resource resource,
                                 String pathInContext)
        throws IOException
    {
        if (!_dirAllowed)
        {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        byte[] data = null;
        String base = URIUtil.addEncodedPaths(request.getRequestURI(), URIUtil.SLASH);
        String dir = resource.getListHTML(base, pathInContext.length() > 1, request.getQueryString());
        if (dir == null)
        {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                "No directory");
            return;
        }

        data = dir.getBytes(StandardCharsets.UTF_8);
        response.setContentType("text/html;charset=utf-8");
        response.setContentLength(data.length);
        response.getOutputStream().write(data);
    }

    protected boolean sendData(HttpServletRequest request,
                               HttpServletResponse response,
                               boolean include,
                               final HttpContent content,
                               Enumeration<String> reqRanges)
        throws IOException
    {
        final long content_length = content.getContentLengthValue();

        // Get the output stream (or writer)
        OutputStream out;
        boolean written;
        try
        {
            out = response.getOutputStream();

            // has something already written to the response?
            written = !(out instanceof HttpOutput) || ((HttpOutput)out).isWritten();
        }
        catch (IllegalStateException e)
        {
            out = new WriterOutputStream(response.getWriter());
            written = true; // there may be data in writer buffer, so assume written
        }

        if (LOG.isDebugEnabled())
            LOG.debug(String.format("sendData content=%s out=%s async=%b", content, out, request.isAsyncSupported()));

        if (reqRanges == null || !reqRanges.hasMoreElements() || content_length < 0)
        {
            //  if there were no ranges, send entire entity
            if (include)
            {
                // write without headers
                content.getResource().writeTo(out, 0, content_length);
            }
            // else if we can't do a bypass write because of wrapping
            else if (written || !(out instanceof HttpOutput))
            {
                // write normally
                putHeaders(response, content, written ? -1 : 0);
                ByteBuffer buffer = content.getIndirectBuffer();
                if (buffer != null)
                    BufferUtil.writeTo(buffer, out);
                else
                    content.getResource().writeTo(out, 0, content_length);
            }
            // else do a bypass write
            else
            {
                // write the headers
                putHeaders(response, content, 0);

                // write the content asynchronously if supported
                if (request.isAsyncSupported() && content.getContentLengthValue() > response.getBufferSize())
                {
                    final AsyncContext context = request.startAsync();
                    context.setTimeout(0);

                    ((HttpOutput)out).sendContent(content, new Callback()
                    {
                        @Override
                        public void succeeded()
                        {
                            context.complete();
                            content.release();
                        }

                        @Override
                        public void failed(Throwable x)
                        {
                            if (x instanceof IOException)
                                LOG.debug(x);
                            else
                                LOG.warn(x);
                            context.complete();
                            content.release();
                        }

                        @Override
                        public String toString()
                        {
                            return String.format("ResourceService@%x$CB", ResourceService.this.hashCode());
                        }
                    });
                    return false;
                }
                // otherwise write content blocking
                ((HttpOutput)out).sendContent(content);
            }
        }
        else
        {
            // Parse the satisfiable ranges
            List<InclusiveByteRange> ranges = InclusiveByteRange.satisfiableRanges(reqRanges, content_length);

            //  if there are no satisfiable ranges, send 416 response
            if (ranges == null || ranges.size() == 0)
            {
                putHeaders(response, content, 0);
                response.setHeader(HttpHeader.CONTENT_RANGE.asString(),
                    InclusiveByteRange.to416HeaderRangeString(content_length));
                sendStatus(response, HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE, null);
                return true;
            }

            //  if there is only a single valid range (must be satisfiable
            //  since were here now), send that range with a 216 response
            if (ranges.size() == 1)
            {
                InclusiveByteRange singleSatisfiableRange = ranges.iterator().next();
                long singleLength = singleSatisfiableRange.getSize();
                putHeaders(response, content, singleLength);
                response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                if (!response.containsHeader(HttpHeader.DATE.asString()))
                    response.addDateHeader(HttpHeader.DATE.asString(), System.currentTimeMillis());
                response.setHeader(HttpHeader.CONTENT_RANGE.asString(),
                    singleSatisfiableRange.toHeaderRangeString(content_length));
                content.getResource().writeTo(out, singleSatisfiableRange.getFirst(), singleLength);
                return true;
            }

            //  multiple non-overlapping valid ranges cause a multipart
            //  216 response which does not require an overall
            //  content-length header
            //
            putHeaders(response, content, -1);
            String mimetype = (content == null ? null : content.getContentTypeValue());
            if (mimetype == null)
                LOG.warn("Unknown mimetype for " + request.getRequestURI());
            MultiPartOutputStream multi = new MultiPartOutputStream(out);
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            if (!response.containsHeader(HttpHeader.DATE.asString()))
                response.addDateHeader(HttpHeader.DATE.asString(), System.currentTimeMillis());

            // If the request has a "Request-Range" header then we need to
            // send an old style multipart/x-byteranges Content-Type. This
            // keeps Netscape and acrobat happy. This is what Apache does.
            String ctp;
            if (request.getHeader(HttpHeader.REQUEST_RANGE.asString()) != null)
                ctp = "multipart/x-byteranges; boundary=";
            else
                ctp = "multipart/byteranges; boundary=";
            response.setContentType(ctp + multi.getBoundary());

            // calculate the content-length
            int length = 0;
            String[] header = new String[ranges.size()];
            int i = 0;
            for (InclusiveByteRange ibr : ranges)
            {
                header[i] = ibr.toHeaderRangeString(content_length);
                length +=
                    ((i > 0) ? 2 : 0) +
                        2 + multi.getBoundary().length() + 2 +
                        (mimetype == null ? 0 : HttpHeader.CONTENT_TYPE.asString().length() + 2 + mimetype.length()) + 2 +
                        HttpHeader.CONTENT_RANGE.asString().length() + 2 + header[i].length() + 2 +
                        2 +
                        (ibr.getLast() - ibr.getFirst()) + 1;
                i++;
            }
            length += 2 + 2 + multi.getBoundary().length() + 2 + 2;
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
        }
        return true;
    }

    protected void putHeaders(HttpServletResponse response, HttpContent content, long contentLength)
    {
        if (response instanceof Response)
        {
            Response r = (Response)response;
            r.putHeaders(content, contentLength, _etags);
            HttpFields f = r.getHttpFields();
            if (_acceptRanges)
                f.put(ACCEPT_RANGES);

            if (_cacheControl != null)
                f.put(_cacheControl);
        }
        else
        {
            Response.putHeaders(response, content, contentLength, _etags);
            if (_acceptRanges)
                response.setHeader(ACCEPT_RANGES.getName(), ACCEPT_RANGES.getValue());

            if (_cacheControl != null)
                response.setHeader(_cacheControl.getName(), _cacheControl.getValue());
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
        String getWelcomeFile(String pathInContext);
    }
}
