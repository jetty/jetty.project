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
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.eclipse.jetty.http.CachingContentFactory;
import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.DateGenerator;
import org.eclipse.jetty.http.DateParser;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.http.QuotedQualityCSV;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.resource.PathCollators;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Arrays.stream;

/**
 * Resource Handler.
 *
 * This handle will serve static content and handle If-Modified-Since headers. No caching is done. Requests for resources that do not exist are let pass (Eg no
 * 404's).
 * TODO there is a lot of URI manipulation, this should be factored out in a utility class.
 *
 * TODO GW: Work out how this logic can be reused by the DefaultServlet... potentially for wrapped output streams
 *
 * Missing:
 *  - current context' mime types
 *  - getContent in HttpContent should go
 *  - Default stylesheet (needs Path impl for classpath resources)
 *  - request ranges
 *  - a way to configure caching or not
 */
public class ResourceHandler extends Handler.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(ResourceHandler.class);

    private static final int NO_CONTENT_LENGTH = -1;
    private static final int USE_KNOWN_CONTENT_LENGTH = -2;

    private ContextHandler _context;
    private Path _defaultStylesheet;
    private MimeTypes _mimeTypes;
    private Path _stylesheet;
    private List<String> _welcomes = List.of("index.html");
    private Path _baseResource;
    private boolean _pathInfoOnly = false;
    private CompressedContentFormat[] _precompressedFormats = new CompressedContentFormat[0];
    private Welcomer _welcomer;
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

    public ResourceHandler()
    {
    }

    @Override
    public void doStart() throws Exception
    {
        Context context = ContextHandler.getCurrentContext();
// TODO        _context = (context == null ? null : context.getContextHandler());
//        if (_mimeTypes == null)
//            _mimeTypes = _context == null ? new MimeTypes() : _context.getMimeTypes();

        _mimeTypes = new MimeTypes();
        //_contentFactory = new PathContentFactory();
        // TODO make caching configurable and disabled by default
        _contentFactory = new CachingContentFactory(new PathContentFactory());
        _welcomer = new DefaultWelcomer();

        super.doStart();
    }

    // for testing only
    HttpContent.ContentFactory getContentFactory()
    {
        return _contentFactory;
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
        return _gzipEquivalentFileExtensions;
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
        // TODO the returned path should point to the classpath.
        // This points to a non-existent file '/jetty-dir.css'.
        return Path.of("/jetty-dir.css");
    }

    public List<String> getWelcomeFiles()
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

        HttpContent content = _contentFactory.getContent(request.getPathInContext(), request.getConnectionMetaData().getHttpConfiguration().getOutputBufferSize());
        if (content == null)
        {
            // no content - try other handlers
            return super.handle(request);
        }
        else
        {
            // TODO is it possible to get rid of the lambda allocation?
            // TODO GW: perhaps HttpContent can extend Request.Processor?
            return (rq, rs, cb) -> doGet(rq, rs, cb, content);
        }
    }

    private void doGet(Request request, Response response, Callback callback, HttpContent content) throws Exception
    {
        String pathInContext = request.getPathInContext();

        // Is this a Range request?
        Enumeration<String> reqRanges = request.getHeaders().getValues(HttpHeader.RANGE.asString());
        if (!hasDefinedRange(reqRanges))
            reqRanges = null;


        boolean endsWithSlash = pathInContext.endsWith(URIUtil.SLASH);
        boolean checkPrecompressedVariants = _precompressedFormats.length > 0 && !endsWithSlash && reqRanges == null;

        try
        {
            // Directory?
            if (Files.isDirectory(content.getPath()))
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
            Response.writeError(request, response, callback, HttpStatus.NOT_FOUND_404);
        }
        catch (IllegalArgumentException e)
        {
            LOG.warn("Failed to serve resource: {}", pathInContext, e);
            if (!response.isCommitted())
                Response.writeError(request, response, callback, HttpStatus.INTERNAL_SERVER_ERROR_500);
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
    private boolean passConditionalHeaders(Request request, Response response, HttpContent content, Callback callback) throws IOException
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

                long ifmsl = request.getHeaders().getDateField(HttpHeader.IF_MODIFIED_SINCE.asString());
                if (ifmsl != -1 && Files.getLastModifiedTime(content.getPath()).toMillis() / 1000 <= ifmsl / 1000)
                {
                    Response.writeError(request, response, callback, HttpStatus.NOT_MODIFIED_304);
                    return true;
                }
            }

            // Parse the if[un]modified dates and compare to resource
            if (ifums != -1 && Files.getLastModifiedTime(content.getPath()).toMillis() / 1000 > ifums / 1000)
            {
                Response.writeError(request, response, callback, HttpStatus.PRECONDITION_FAILED_412);
                return true;
            }
        }
        catch (IllegalArgumentException iae)
        {
            if (!response.isCommitted())
                Response.writeError(request, response, callback, HttpStatus.BAD_REQUEST_400);
            throw iae;
        }

        return false;
    }

    protected void sendWelcome(HttpContent content, String pathInContext, boolean endsWithSlash, Request request, Response response, Callback callback) throws Exception
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
            response.setContentLength(0);
            Response.sendRedirect(request, response, callback, buf.toString());
            return;
        }

        // look for a welcome file
        if (_welcomer.welcome(request, response, callback))
            return;

        if (!passConditionalHeaders(request, response, content, callback))
            sendDirectory(request, response, content, callback, pathInContext);
    }

    private void sendDirectory(Request request, Response response, HttpContent httpContent, Callback callback, String pathInContext) throws IOException
    {
        Path resource = httpContent.getPath();
        if (!_dirAllowed)
        {
            Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403);
            return;
        }

        String base = URIUtil.addEncodedPaths(request.getHttpURI().getPath(), URIUtil.SLASH);
        String dir = getListHTML(resource, base, pathInContext.length() > 1, request.getHttpURI().getQuery());
        if (dir == null)
        {
            Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403);
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

        List<Path> items;
        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(path))
        {
            Stream<Path> stream = StreamSupport.stream(directoryStream.spliterator(), false);
            items = stream.collect(Collectors.toCollection(ArrayList::new));
        }
        catch (IOException e)
        {
            LOG.debug("Directory list access failure", e);
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

        // Perform sort
        if (sortColumn.equals("M"))
            items.sort(PathCollators.byLastModified(sortOrderAscending));
        else if (sortColumn.equals("S"))
            items.sort(PathCollators.bySize(sortOrderAscending));
        else
            items.sort(PathCollators.byName(sortOrderAscending));

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

            if (Files.isDirectory(item))
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

    private boolean sendData(Request request, Response response, Callback callback, HttpContent content, Enumeration<String> reqRanges) throws IOException
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

    private void writeContent(Response response, Callback callback, HttpContent content) throws IOException
    {
        ByteBuffer buffer = content.getBuffer();
        if (buffer != null)
            response.write(true, callback, buffer);
        else
            new ContentWriterIteratingCallback(content, response, callback).iterate();
    }

    private void putHeaders(Response response, HttpContent content, long contentLength)
    {
        HttpFields.Mutable headers = response.getHeaders();

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
            headers.put(lm);

        if (contentLength == USE_KNOWN_CONTENT_LENGTH)
        {
            headers.put(content.getContentLength());
        }
        else if (contentLength > NO_CONTENT_LENGTH)
        {
            headers.putLongField(HttpHeader.CONTENT_LENGTH, contentLength);
        }

        HttpField ct = content.getContentType();
        if (ct != null)
            headers.put(ct);

        HttpField ce = content.getContentEncoding();
        if (ce != null)
            headers.put(ce);

        if (_etags)
        {
            HttpField et = content.getETag();
            if (et != null)
                headers.put(et);
        }

        HttpFields.Mutable fields = response.getHeaders();
        if (_acceptRanges && !fields.contains(HttpHeader.ACCEPT_RANGES))
            fields.add(new PreEncodedHttpField(HttpHeader.ACCEPT_RANGES, "bytes"));
        if (_cacheControl != null && !fields.contains(HttpHeader.CACHE_CONTROL))
            fields.add(_cacheControl);
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
     * @param stylesheet The location of the stylesheet to be used as a String.
     */
    // TODO accept a Path instead of a String?
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

    public void setWelcomeFiles(List<String> welcomeFiles)
    {
        _welcomes = welcomeFiles;
    }

    private class PathContentFactory implements HttpContent.ContentFactory
    {
        @Override
        public HttpContent getContent(String path, int maxBuffer) throws IOException
        {
            if (_precompressedFormats.length > 0)
            {
                // Is the precompressed content cached?
                Map<CompressedContentFormat, HttpContent> compressedContents = new HashMap<>();
                for (CompressedContentFormat format : _precompressedFormats)
                {
                    String compressedPathInContext = path + format.getExtension();

                    // Is there a precompressed resource?
                    PathHttpContent compressedContent = load(compressedPathInContext, null);
                    if (compressedContent != null)
                        compressedContents.put(format, compressedContent);
                }
                if (!compressedContents.isEmpty())
                    return load(path, compressedContents);
            }

            return load(path, null);
        }

        private PathHttpContent load(String path, Map<CompressedContentFormat, HttpContent> compressedEquivalents)
        {
            if (path.startsWith("/"))
                path = path.substring(1);
            // TODO cache _baseResource.toUri()
            Path resolved = Path.of(_baseResource.toUri().resolve(path));
            // TODO call alias checker
            if (!Files.exists(resolved))
                return null;
            String mimeType = _mimeTypes.getMimeByExtension(resolved.getFileName().toString());
            return new PathHttpContent(resolved, mimeType, compressedEquivalents);
        }
    }

    private static class PathHttpContent implements HttpContent
    {
        private final Path _path;
        private final PreEncodedHttpField _contentType;
        private final String _characterEncoding;
        private final MimeTypes.Type _mimeType;
        private final Map<CompressedContentFormat, HttpContent> _compressedContents;

        public PathHttpContent(Path path, String contentType, Map<CompressedContentFormat, HttpContent> compressedEquivalents)
        {
            _path = path;
            _contentType = contentType == null ? null : new PreEncodedHttpField(HttpHeader.CONTENT_TYPE, contentType);
            _characterEncoding = _contentType == null ? null : MimeTypes.getCharsetFromContentType(contentType);
            _mimeType = _contentType == null ? null : MimeTypes.CACHE.get(MimeTypes.getContentTypeWithoutCharset(contentType));
            _compressedContents = compressedEquivalents;
        }

        @Override
        public HttpField getContentType()
        {
            return _contentType;
        }

        @Override
        public String getContentTypeValue()
        {
            return _contentType.getValue();
        }

        @Override
        public String getCharacterEncoding()
        {
            return _characterEncoding;
        }

        @Override
        public MimeTypes.Type getMimeType()
        {
            return _mimeType;
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
            long cl = getContentLengthValue();
            return cl >= 0 ? new HttpField.LongValueHttpField(HttpHeader.CONTENT_LENGTH, cl) : null;
        }

        @Override
        public long getContentLengthValue()
        {
            try
            {
                if (Files.isDirectory(_path))
                    return NO_CONTENT_LENGTH;
                return Files.size(_path);
            }
            catch (IOException e)
            {
                return NO_CONTENT_LENGTH;
            }
        }

        @Override
        public HttpField getLastModified()
        {
            String lm = getLastModifiedValue();
            return lm != null ? new HttpField(HttpHeader.LAST_MODIFIED, lm) : null;
        }

        @Override
        public String getLastModifiedValue()
        {
            try
            {
                long lm = Files.getLastModifiedTime(_path).toMillis();
                return DateGenerator.formatDate(lm);
            }
            catch (IOException e)
            {
                return null;
            }
        }

        @Override
        public HttpField getETag()
        {
            String weakETag = getWeakETag();
            return weakETag == null ? null : new HttpField(HttpHeader.ETAG, weakETag);
        }

        @Override
        public String getETagValue()
        {
            return getWeakETag();
        }

        private String getWeakETag()
        {
            StringBuilder b = new StringBuilder(32);
            b.append("W/\"");

            String name = _path.toAbsolutePath().toString();
            int length = name.length();
            long lhash = 0;
            for (int i = 0; i < length; i++)
            {
                lhash = 31 * lhash + name.charAt(i);
            }

            Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
            try
            {
                long lastModifiedTime = Files.getLastModifiedTime(_path).toMillis();
                b.append(encoder.encodeToString(longToBytes(lastModifiedTime ^ lhash)));
            }
            catch (IOException e)
            {
                LOG.debug("Unable to get last modified time of {}", _path, e);
                return null;
            }
            try
            {
                long contentLengthValue = Files.size(_path);
                b.append(encoder.encodeToString(longToBytes(contentLengthValue ^ lhash)));
            }
            catch (IOException e)
            {
                LOG.debug("Unable to get size of {}", _path, e);
                return null;
            }
            b.append('"');
            return b.toString();
        }

        private static byte[] longToBytes(long value)
        {
            byte[] result = new byte[Long.BYTES];
            for (int i = Long.BYTES - 1; i >= 0; i--)
            {
                result[i] = (byte)(value & 0xFF);
                value >>= 8;
            }
            return result;
        }

        @Override
        public Path getPath()
        {
            return _path;
        }

        @Override
        public Resource getResource()
        {
            // TODO cache or create in constructor?
            return new PathResource(_path);
        }

        @Override
        public Map<CompressedContentFormat, ? extends HttpContent> getPrecompressedContents()
        {
            return _compressedContents;
        }

        @Override
        public ByteBuffer getBuffer()
        {
            return null;
        }

        @Override
        public void release()
        {
        }
    }

    // TODO a ReadableByteChannel IteratingCallback that writes to a Response looks generic enough to be moved to some util module
    private static class ContentWriterIteratingCallback extends IteratingCallback
    {
        private final ReadableByteChannel source;
        private final Content.Writer target;
        private final Callback callback;
        private final ByteBuffer byteBuffer;

        public ContentWriterIteratingCallback(HttpContent content, Response target, Callback callback) throws IOException
        {
            // TODO: is it possible to do zero-copy transfer?
//            WritableByteChannel c = Response.asWritableByteChannel(target);
//            FileChannel fileChannel = (FileChannel) source;
//            fileChannel.transferTo(0, contentLength, c);

            this.source = Files.newByteChannel(content.getPath());
            this.target = target;
            this.callback = callback;
            HttpConfiguration httpConfiguration = target.getRequest().getConnectionMetaData().getHttpConfiguration();
            int outputBufferSize = httpConfiguration.getOutputBufferSize();
            boolean useOutputDirectByteBuffers = httpConfiguration.isUseOutputDirectByteBuffers();
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

    public interface Welcomer
    {
        /**
         * @return true if the request was processed, false otherwise.
         */
        boolean welcome(Request request, Response response, Callback callback) throws Exception;
    }

    private class DefaultWelcomer implements Welcomer
    {
        @Override
        public boolean welcome(Request request, Response response, Callback callback) throws Exception
        {
            String pathInContext = request.getPathInContext();
            String welcome = getWelcomeFile(pathInContext);
            if (welcome != null)
            {
                String contextPath = request.getContext().getContextPath();

                if (_pathInfoOnly)
                    welcome = URIUtil.addPaths(contextPath, welcome);

                if (LOG.isDebugEnabled())
                    LOG.debug("welcome={}", welcome);

                if (_redirectWelcome)
                {
                    // Redirect to the index
                    response.setContentLength(0);

                    // TODO need helper code to edit URIs
                    String uri = URIUtil.encodePath(URIUtil.addPaths(request.getContext().getContextPath(), welcome));
                    String q = request.getHttpURI().getQuery();
                    if (q != null && !q.isEmpty())
                        uri += "?" + q;

                    Response.sendRedirect(request, response, callback, uri);
                    return true;
                }

                // Serve welcome file
                HttpContent c = _contentFactory.getContent(welcome, request.getConnectionMetaData().getHttpConfiguration().getOutputBufferSize());
                sendData(request, response, callback, c, null);
                return true;
            }
            return false;
        }

        private String getWelcomeFile(String pathInContext)
        {
            if (_welcomes == null)
                return null;

            for (String welcome : _welcomes)
            {
                // TODO this logic is similar to the one in PathContentFactory.getContent()
                // TODO GW: This logic needs to be extensible so that a welcome file may be a servlet (yeah I know it shouldn't
                //          be called a welcome file then.   So for example if /foo/index.jsp is the welcome file, we can't
                //          serve it's contents - rather we have to let the servlet layer to either a redirect or a RequestDispatcher to it.
                //          Worse yet, if there was a servlet mapped to /foo/index.html, then we need to be able to dispatch to it
                //          EVEN IF the file does not exist.
                String welcomeInContext = URIUtil.addPaths(pathInContext, welcome);
                String path = pathInContext;
                if (path.startsWith("/"))
                    path = path.substring(1);
                Path welcomePath = Path.of(_baseResource.toUri().resolve(path).resolve(welcome));
                if (Files.exists(welcomePath))
                    return welcomeInContext;
            }
            // not found
            return null;
        }
    }
}
