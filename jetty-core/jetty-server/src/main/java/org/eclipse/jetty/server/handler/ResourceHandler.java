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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.DateParser;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.http.QuotedQualityCSV;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.ResourceService.WelcomeFactory;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resource Handler.
 *
 * This handle will serve static content and handle If-Modified-Since headers. No caching is done. Requests for resources that do not exist are let pass (Eg no
 * 404's).
 */
public class ResourceHandler extends Handler.Wrapper implements WelcomeFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(ResourceHandler.class);

    private static final int NO_CONTENT_LENGTH = -1;
    private static final int USE_KNOWN_CONTENT_LENGTH = -2;

    private ContextHandler _context;
    private Path _defaultStylesheet;
    private MimeTypes _mimeTypes;
    private Path _stylesheet;
    private String[] _welcomes = {"index.html"};
    private Path _baseResource;
    private boolean _pathInfoOnly = false;
    private CompressedContentFormat[] _precompressedFormats = new CompressedContentFormat[0];
    private WelcomeFactory _welcomeFactory;
    private boolean _redirectWelcome = false;
    private boolean _etags = false;
    private List<String> _gzipEquivalentFileExtensions;
    private HttpContent.ContentFactory _contentFactory = new FileContentFactory();
    private final Map<String, List<String>> _preferredEncodingOrderCache = new ConcurrentHashMap<>();
    private String[] _preferredEncodingOrder = new String[0];
    private int _encodingCacheSize = 100;
    private boolean _dirAllowed = true;
    private boolean _acceptRanges = true;
    private HttpField _cacheControl;

    public ResourceHandler()
    {
    }

    @Override
    public String getWelcomeFile(String pathInContext) throws IOException
    {
        if (_welcomes == null)
            return null;

        for (int i = 0; i < _welcomes.length; i++)
        {
            String welcomeInContext = URIUtil.addPaths(pathInContext, _welcomes[i]);
            Path welcome = Path.of(welcomeInContext);
            if (Files.exists(welcome))
                return welcomeInContext;
        }
        // not found
        return null;
    }

    @Override
    public void doStart() throws Exception
    {
        Context context = ContextHandler.getCurrentContext();
// TODO        _context = (context == null ? null : context.getContextHandler());
//        if (_mimeTypes == null)
//            _mimeTypes = _context == null ? new MimeTypes() : _context.getMimeTypes();

        //_resourceService.setContentFactory(new ResourceContentFactory(this, _mimeTypes, _resourceService.getPrecompressedFormats()));
        //_resourceService.setWelcomeFactory(this);

        super.doStart();
    }

    /**
     * @return Returns the resourceBase.
     */
    public Path getBaseResource()
    {
        return _baseResource;
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
        return null; //_resourceService.getGzipEquivalentFileExtensions();
    }

    public MimeTypes getMimeTypes()
    {
        return _mimeTypes;
    }

    /**
     * @return Returns the stylesheet as a Resource.
     */
    public Path getStylesheet()
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

    public static Path getDefaultStylesheet()
    {
        return Path.of("/jetty-dir.css");
    }

    public String[] getWelcomeFiles()
    {
        return _welcomes;
    }

    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        if (!HttpMethod.GET.is(request.getMethod()) && !HttpMethod.HEAD.is(request.getMethod()))
        {
            // try another handler
            return super.handle(request);
        }

        HttpContent content = _contentFactory.getContent(request.getPathInContext(), request.getHttpChannel().getHttpConfiguration().getOutputBufferSize());
        if (content == null)
        {
            // no content - try other handlers
            return super.handle(request);
        }
        else
        {
            return (rq, rs, cb) -> doGet(rq, rs, cb, content);
        }
    }

    private void doGet(Request request, Response response, Callback callback, HttpContent content) throws Exception
    {
        String servletPath = _pathInfoOnly ? "/" : request.getPathInContext();
        String pathInfo = request.getPathInContext();
        String pathInContext = URIUtil.addPaths(servletPath, pathInfo);

        // Is this a Range request?
        Enumeration<String> reqRanges = request.getHeaders().getValues(HttpHeader.RANGE.asString());
        if (!hasDefinedRange(reqRanges))
            reqRanges = null;


        boolean endsWithSlash = (pathInfo == null ? (_pathInfoOnly ? "" : servletPath) : pathInfo).endsWith(URIUtil.SLASH);
        boolean checkPrecompressedVariants = _precompressedFormats.length > 0 && !endsWithSlash && reqRanges == null;

        try
        {
            // Directory?
            if (Files.isDirectory(content.getResource()))
            {
                sendWelcome(content, pathInContext, endsWithSlash, request, response, callback);
                return;
            }

            // Strip slash?
            if (endsWithSlash && pathInContext.length() > 1)
            {
                String q = request.getHttpURI().getQuery();
                pathInContext = pathInContext.substring(0, pathInContext.length() - 1);
                if (q != null && q.length() != 0)
                    pathInContext += "?" + q;
                Response.sendRedirect(request, response, callback, URIUtil.addPaths(request.getContext().getContextPath(), pathInContext));
                return;
            }

            // Conditional response?
            if (!passConditionalHeaders(request, response, content, callback))
                return;

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
        catch (InvalidPathException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("InvalidPathException for pathInContext: {}", pathInContext, e);
            sendStatus(404, response, callback);
        }
        catch (IllegalArgumentException e)
        {
            LOG.warn("Failed to serve resource: {}", pathInContext, e);
            if (!response.isCommitted())
                sendStatus(500, response, callback);
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
                    if (etag != null)
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
                        sendStatus(412, response, callback);
                        return false;
                    }
                }

                if (ifnm != null && etag != null)
                {
                    // Handle special case of exact match OR gzip exact match
                    if (CompressedContentFormat.tagEquals(etag, ifnm) && ifnm.indexOf(',') < 0)
                    {
                        sendStatus(304, response, callback);
                        return false;
                    }

                    // Handle list of tags
                    QuotedCSV quoted = new QuotedCSV(true, ifnm);
                    for (String tag : quoted)
                    {
                        if (CompressedContentFormat.tagEquals(etag, tag))
                        {
                            sendStatus(304, response, callback);
                            return false;
                        }
                    }

                    // If etag requires content to be served, then do not check if-modified-since
                    callback.succeeded();
                    return true;
                }
            }

            // Handle if modified since
            if (ifms != null)
            {
                //Get jetty's Response impl
                String mdlm = content.getLastModifiedValue();
                if (ifms.equals(mdlm))
                {
                    sendStatus(304, response, Callback.NOOP);
                    return false;
                }

                long ifmsl = request.getHeaders().getDateField(HttpHeader.IF_MODIFIED_SINCE.asString());
                if (ifmsl != -1 && Files.getLastModifiedTime(content.getResource()).toMillis() / 1000 <= ifmsl / 1000)
                {
                    sendStatus(304, response, Callback.NOOP);
                    return false;
                }
            }

            // Parse the if[un]modified dates and compare to resource
            if (ifums != -1 && Files.getLastModifiedTime(content.getResource()).toMillis() / 1000 > ifums / 1000)
            {
                sendStatus(412, response, Callback.NOOP);
                return false;
            }
        }
        catch (IllegalArgumentException iae)
        {
            if (!response.isCommitted())
                sendStatus(400, response, callback);
            throw iae;
        }

        return true;
    }

    protected void sendWelcome(HttpContent content, String pathInContext, boolean endsWithSlash, Request request, Response response, Callback callback) throws Exception
    {
        // Redirect to directory
        if (!endsWithSlash)
        {
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
            response.setContentLength(0);
            Response.sendRedirect(request, response, callback, buf.toString());
            return;
        }

        // look for a welcome file
        String welcome = _welcomeFactory == null ? null : _welcomeFactory.getWelcomeFile(pathInContext);

        if (welcome != null)
        {
            String servletPath = request.getContext().getContextPath();

            if (_pathInfoOnly)
                welcome = URIUtil.addPaths(servletPath, welcome);

            if (LOG.isDebugEnabled())
                LOG.debug("welcome={}", welcome);

            if (_redirectWelcome)
            {
                // Redirect to the index
                response.setContentLength(0);

                String uri = URIUtil.encodePath(URIUtil.addPaths(request.getContext().getContextPath(), welcome));
                String q = request.getHttpURI().getQuery();
                if (q != null && !q.isEmpty())
                    uri += "?" + q;

                Response.sendRedirect(request, response, callback, uri);
                return;
            }

            return;
        }

        if (passConditionalHeaders(request, response, content, callback))
            sendDirectory(request, response, content.getResource(), callback, pathInContext);
    }

    protected void sendDirectory(Request request, Response response, Path resource, Callback callback, String pathInContext) throws IOException
    {
        if (!_dirAllowed)
        {
            sendStatus(403, response, callback);
            return;
        }

        String base = URIUtil.addEncodedPaths(request.getHttpURI().getPath(), URIUtil.SLASH);
        String dir = getListHTML(resource, base, pathInContext.length() > 1, request.getHttpURI().getQuery());
        if (dir == null)
        {
            sendStatus(403, response, callback);
            return;
        }

        byte[] data = dir.getBytes(StandardCharsets.UTF_8);
        response.setContentType("text/html;charset=utf-8");
        response.setContentLength(data.length);
        response.write(true, callback, ByteBuffer.wrap(data));
    }

    private String getListHTML(Path path, String base, boolean parent, String query) throws IOException
    {
        // This method doesn't check aliases, so it is OK to canonicalize here.
        base = URIUtil.canonicalPath(base);
        if (base == null || !Files.isDirectory(path))
            return null;

        String[] rawListing = list(path);
        if (rawListing == null)
        {
            return null;
        }

        boolean sortOrderAscending = true;
        String sortColumn = "N"; // name (or "M" for Last Modified, or "S" for Size)

        // check for query
        if (query != null)
        {
            MultiMap<String> params = new MultiMap<>();
            UrlEncoded.decodeUtf8To(query, 0, query.length(), params);

            String paramO = params.getString("O");
            String paramC = params.getString("C");
            if (StringUtil.isNotBlank(paramO))
            {
                if (paramO.equals("A"))
                {
                    sortOrderAscending = true;
                }
                else if (paramO.equals("D"))
                {
                    sortOrderAscending = false;
                }
            }
            if (StringUtil.isNotBlank(paramC))
            {
                if (paramC.equals("N") || paramC.equals("M") || paramC.equals("S"))
                {
                    sortColumn = paramC;
                }
            }
        }

        // Gather up entries
        List<Path> items = new ArrayList<>();
        for (String l : rawListing)
        {
            Path item = path.resolve(l);
            items.add(item);
        }

        // TODO Perform sort
//        if (sortColumn.equals("M"))
//        {
//            items.sort(ResourceCollators.byLastModified(sortOrderAscending));
//        }
//        else if (sortColumn.equals("S"))
//        {
//            items.sort(ResourceCollators.bySize(sortOrderAscending));
//        }
//        else
//        {
//            items.sort(ResourceCollators.byName(sortOrderAscending));
//        }

        String decodedBase = URIUtil.decodePath(base);
        String title = "Directory: " + StringUtil.sanitizeXmlString(decodedBase);

        StringBuilder buf = new StringBuilder(4096);

        // Doctype Declaration (HTML5)
        buf.append("<!DOCTYPE html>\n");
        buf.append("<html lang=\"en\">\n");

        // HTML Header
        buf.append("<head>\n");
        buf.append("<meta charset=\"utf-8\">\n");
        buf.append("<link href=\"jetty-dir.css\" rel=\"stylesheet\" />\n");
        buf.append("<title>");
        buf.append(title);
        buf.append("</title>\n");
        buf.append("</head>\n");

        // HTML Body
        buf.append("<body>\n");
        buf.append("<h1 class=\"title\">").append(title).append("</h1>\n");

        // HTML Table
        final String ARROW_DOWN = "&nbsp; &#8681;";
        final String ARROW_UP = "&nbsp; &#8679;";

        buf.append("<table class=\"listing\">\n");
        buf.append("<thead>\n");

        String arrow = "";
        String order = "A";
        if (sortColumn.equals("N"))
        {
            if (sortOrderAscending)
            {
                order = "D";
                arrow = ARROW_UP;
            }
            else
            {
                order = "A";
                arrow = ARROW_DOWN;
            }
        }

        buf.append("<tr><th class=\"name\"><a href=\"?C=N&O=").append(order).append("\">");
        buf.append("Name").append(arrow);
        buf.append("</a></th>");

        arrow = "";
        order = "A";
        if (sortColumn.equals("M"))
        {
            if (sortOrderAscending)
            {
                order = "D";
                arrow = ARROW_UP;
            }
            else
            {
                order = "A";
                arrow = ARROW_DOWN;
            }
        }

        buf.append("<th class=\"lastmodified\"><a href=\"?C=M&O=").append(order).append("\">");
        buf.append("Last Modified").append(arrow);
        buf.append("</a></th>");

        arrow = "";
        order = "A";
        if (sortColumn.equals("S"))
        {
            if (sortOrderAscending)
            {
                order = "D";
                arrow = ARROW_UP;
            }
            else
            {
                order = "A";
                arrow = ARROW_DOWN;
            }
        }
        buf.append("<th class=\"size\"><a href=\"?C=S&O=").append(order).append("\">");
        buf.append("Size").append(arrow);
        buf.append("</a></th></tr>\n");
        buf.append("</thead>\n");

        buf.append("<tbody>\n");

        String encodedBase = hrefEncodeURI(base);

        if (parent)
        {
            // Name
            buf.append("<tr><td class=\"name\"><a href=\"");
            buf.append(URIUtil.addPaths(encodedBase, "../"));
            buf.append("\">Parent Directory</a></td>");
            // Last Modified
            buf.append("<td class=\"lastmodified\">-</td>");
            // Size
            buf.append("<td>-</td>");
            buf.append("</tr>\n");
        }

        DateFormat dfmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM);
        for (Path item : items)
        {
            String name = item.getFileName().toString();
            if (StringUtil.isBlank(name))
            {
                continue; // skip
            }

            if (Files.isDirectory(item) && !name.endsWith("/"))
            {
                name += URIUtil.SLASH;
            }

            // Name
            buf.append("<tr><td class=\"name\"><a href=\"");
            String uriPath = URIUtil.addEncodedPaths(encodedBase, URIUtil.encodePath(name));
            buf.append(uriPath);
            buf.append("\">");
            buf.append(StringUtil.sanitizeXmlString(name));
            buf.append("&nbsp;");
            buf.append("</a></td>");

            // Last Modified
            buf.append("<td class=\"lastmodified\">");
            long lastModified = Files.getLastModifiedTime(item).toMillis();
            if (lastModified > 0)
            {
                buf.append(dfmt.format(new Date(lastModified)));
            }
            buf.append("&nbsp;</td>");

            // Size
            buf.append("<td class=\"size\">");
            long length = Files.size(item);
            if (length >= 0)
            {
                buf.append(String.format("%,d bytes", length));
            }
            buf.append("&nbsp;</td></tr>\n");
        }
        buf.append("</tbody>\n");
        buf.append("</table>\n");
        buf.append("</body></html>\n");

        return buf.toString();
    }

    private static String hrefEncodeURI(String raw)
    {
        StringBuilder buf = null;

        loop:
        for (int i = 0; i < raw.length(); i++)
        {
            char c = raw.charAt(i);
            switch (c)
            {
                case '\'':
                case '"':
                case '<':
                case '>':
                    buf = new StringBuilder(raw.length() << 1);
                    break loop;
                default:
                    break;
            }
        }
        if (buf == null)
            return raw;

        for (int i = 0; i < raw.length(); i++)
        {
            char c = raw.charAt(i);
            switch (c)
            {
                case '"' -> buf.append("%22");
                case '\'' -> buf.append("%27");
                case '<' -> buf.append("%3C");
                case '>' -> buf.append("%3E");
                default -> buf.append(c);
            }
        }

        return buf.toString();
    }

    private String[] list(Path path)
    {
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(path))
        {
            List<String> entries = new ArrayList<>();
            for (Path entry : dir)
            {
                String name = entry.getFileName().toString();

                if (Files.isDirectory(entry))
                {
                    name += "/";
                }

                entries.add(name);
            }
            int size = entries.size();
            return entries.toArray(new String[size]);
        }
        catch (DirectoryIteratorException e)
        {
            LOG.debug("Directory list failure", e);
        }
        catch (IOException e)
        {
            LOG.debug("Directory list access failure", e);
        }
        return null;
    }

    protected boolean sendData(Request request, Response response, Callback callback, final HttpContent content, Enumeration<String> reqRanges) throws IOException
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
            writeContent(response, callback, content);
        }
        else
        {
            throw new UnsupportedOperationException("TODO");
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

    private void writeContent(Response response, Callback callback, HttpContent content) throws IOException
    {
        new ContentWriterIteratingCallback(content, response, callback).iterate();
    }

    private void putHeaders(Response response, HttpContent content, long contentLength)
    {
        putHeaders(response.getHeaders(), content, contentLength, _etags);

        HttpFields.Mutable fields = response.getHeaders();
        if (_acceptRanges && !fields.contains(HttpHeader.ACCEPT_RANGES))
            fields.add(new PreEncodedHttpField(HttpHeader.ACCEPT_RANGES, "bytes"));
        if (_cacheControl != null && !fields.contains(HttpHeader.CACHE_CONTROL))
            fields.add(_cacheControl);
    }

    private void putHeaders(HttpFields.Mutable fields, HttpContent content, long contentLength, boolean etag)
    {
        HttpField lm = content.getLastModified();
        if (lm != null)
            fields.put(lm);

        if (contentLength == USE_KNOWN_CONTENT_LENGTH)
        {
            fields.put(content.getContentLength());
        }
        else if (contentLength > NO_CONTENT_LENGTH)
        {
            fields.putLongField(HttpHeader.CONTENT_LENGTH, contentLength);
        }

        HttpField ct = content.getContentType();
        if (ct != null)
            fields.put(ct);

        HttpField ce = content.getContentEncoding();
        if (ce != null)
            fields.put(ce);

        if (etag)
        {
            HttpField et = content.getETag();
            if (et != null)
                fields.put(et);
        }
    }

    private boolean hasDefinedRange(Enumeration<String> reqRanges)
    {
        return (reqRanges != null && reqRanges.hasMoreElements());
    }

    private void sendStatus(int status, Response response, Callback callback)
    {
        response.setStatus(status);
        callback.succeeded();
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

    /**
     * @param acceptRanges If true, range requests and responses are supported
     */
    public void setAcceptRanges(boolean acceptRanges)
    {
        _acceptRanges = acceptRanges;
    }

    /**
     * @param base The resourceBase to server content from. If null the
     * context resource base is used.
     */
    public void setBaseResource(Path base)
    {
        _baseResource = base;
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
        //_resourceService.setDirAllowed(dirAllowed);
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
    }

    public void setMimeTypes(MimeTypes mimeTypes)
    {
        _mimeTypes = mimeTypes;
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

    /**
     * @param resourceBase The base resource as a string.
     */
    public void setResourceBase(String resourceBase)
    {
        try
        {
            setBaseResource(Path.of(resourceBase));
        }
        catch (Exception e)
        {
            LOG.warn("Invalid Base Resource reference: {}", resourceBase, e);
            throw new IllegalArgumentException(resourceBase);
        }
    }

    /**
     * @param stylesheet The location of the stylesheet to be used as a String.
     */
    public void setStylesheet(String stylesheet)
    {
        try
        {
            _stylesheet = Path.of(stylesheet);
            if (!Files.exists(_stylesheet))
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

    public void setWelcomeFiles(String[] welcomeFiles)
    {
        _welcomes = welcomeFiles;
    }

    private class FileContentFactory implements HttpContent.ContentFactory
    {
        @Override
        public HttpContent getContent(String path, int maxBuffer) throws IOException
        {
            if (path.startsWith("/"))
                path = path.substring(1);
            Path resolved = _baseResource.resolve(path);
            return new FileHttpContent(resolved);
        }
    }

    private static class FileHttpContent implements HttpContent
    {
        private final Path _path;

        public FileHttpContent(Path path)
        {
            _path = path;
        }

        @Override
        public HttpField getContentType()
        {
            return null;
        }

        @Override
        public String getContentTypeValue()
        {
            return null;
        }

        @Override
        public String getCharacterEncoding()
        {
            return null;
        }

        @Override
        public MimeTypes.Type getMimeType()
        {
            return null;
        }

        @Override
        public HttpField getContentEncoding()
        {
            return null;
        }

        @Override
        public String getContentEncodingValue()
        {
            return null;
        }

        @Override
        public HttpField getContentLength()
        {
            return null;
        }

        @Override
        public long getContentLengthValue()
        {
            return 0;
        }

        @Override
        public HttpField getLastModified()
        {
            return null;
        }

        @Override
        public String getLastModifiedValue()
        {
            return null;
        }

        @Override
        public HttpField getETag()
        {
            return null;
        }

        @Override
        public String getETagValue()
        {
            return null;
        }

        @Override
        public ByteBuffer getIndirectBuffer()
        {
            return null;
        }

        @Override
        public ByteBuffer getDirectBuffer()
        {
            return null;
        }

        @Override
        public Path getResource()
        {
            return _path;
        }

        @Override
        public InputStream getInputStream() throws IOException
        {
            return null;
        }

        @Override
        public ReadableByteChannel getReadableByteChannel() throws IOException
        {
            return Files.newByteChannel(_path);
        }

        @Override
        public void release()
        {

        }

        @Override
        public Map<CompressedContentFormat, ? extends HttpContent> getPrecompressedContents()
        {
            return null;
        }
    }

    private static class ContentWriterIteratingCallback extends IteratingCallback
    {
        private final ReadableByteChannel source;
        private final Content.Writer target;
        private final Callback callback;
        private final ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4096); // TODO use pool

        public ContentWriterIteratingCallback(HttpContent content, Content.Writer target, Callback callback) throws IOException
        {
            this.source = content.getReadableByteChannel();
            this.target = target;
            this.callback = callback;
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
                target.write(true, this, BufferUtil.EMPTY_BUFFER);
                return Action.SCHEDULED;
            }
            byteBuffer.flip();
            target.write(false, this, byteBuffer);
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
