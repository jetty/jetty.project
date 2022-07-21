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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.channels.IllegalSelectorException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Locale;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.ServletResponseWrapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.ee10.servlet.ServletContextRequest.ServletApiRequest;
import org.eclipse.jetty.ee10.servlet.writer.EncodingHttpWriter;
import org.eclipse.jetty.ee10.servlet.writer.Iso88591HttpWriter;
import org.eclipse.jetty.ee10.servlet.writer.ResponseWriter;
import org.eclipse.jetty.ee10.servlet.writer.Utf8HttpWriter;
import org.eclipse.jetty.http.DateGenerator;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextResponse;
import org.eclipse.jetty.session.Session;
import org.eclipse.jetty.session.SessionManager;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.SharedBlockingCallback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;

public class ServletContextResponse extends ContextResponse
{
    private static final int __MIN_BUFFER_SIZE = 1;
    private static final HttpField __EXPIRES_01JAN1970 = new PreEncodedHttpField(HttpHeader.EXPIRES, DateGenerator.__01Jan1970);
    private static final EnumSet<EncodingFrom> __localeOverride = EnumSet.of(EncodingFrom.NOT_SET, EncodingFrom.DEFAULT, EncodingFrom.INFERRED, EncodingFrom.SET_LOCALE);

    public enum OutputType
    {
        NONE, STREAM, WRITER
    }

    private final Response _response;
    private final HttpOutput _httpOutput;
    private final ServletChannel _servletChannel;
    private final ServletApiResponse _httpServletResponse;
    private final ServletContextRequest _request;
    private String _characterEncoding;
    private String _contentType;
    private MimeTypes.Type _mimeType;
    private Locale _locale;
    private EncodingFrom _encodingFrom = EncodingFrom.NOT_SET;
    private OutputType _outputType = OutputType.NONE;
    private ResponseWriter _writer;

    private long _contentLength = -1;
    
    public static ServletContextResponse getBaseResponse(ServletResponse response)
    {
        if (response instanceof ServletApiResponse)
            return ((ServletApiResponse)response).getResponse();

        while (response instanceof ServletResponseWrapper)
        {
            response = ((ServletResponseWrapper)response).getResponse();
        }

        if (response instanceof ServletApiResponse)
            return ((ServletApiResponse)response).getResponse();

        return null;
    }

    public ServletContextResponse(ServletChannel servletChannel, ServletContextRequest request, Response response)
    {
        super(servletChannel.getContext(), response.getRequest(), response);
        _request = request;
        _response = response;
        _httpOutput = new HttpOutput(response, servletChannel);
        _servletChannel = servletChannel;
        _httpServletResponse = new ServletApiResponse(response);
    }

    public HttpOutput getHttpOutput()
    {
        return _httpOutput;
    }

    public ServletRequestState getState()
    {
        return _servletChannel.getState();
    }

    public HttpServletResponse getHttpServletResponse()
    {
        return _httpServletResponse;
    }
    
    public ServletApiResponse getServletApiResponse()
    {
        return _httpServletResponse;
    }

    public void resetForForward()
    {
        _httpOutput.resetBuffer();
        _httpOutput.reopen();
        _outputType = OutputType.NONE;
    }

    public void completeOutput(Callback callback)
    {
        if (_outputType == OutputType.WRITER)
            _writer.complete(callback);
        else
            _httpOutput.complete(callback);
    }

    public boolean isAllContentWritten(long written)
    {
        return (_contentLength >= 0 && written >= _contentLength);
    }

    public boolean isContentComplete(long written)
    {
        return (_contentLength < 0 || written >= _contentLength);
    }

    public void setContentLength(int len)
    {
        // Protect from setting after committed as default handling
        // of a servlet HEAD request ALWAYS sets _content length, even
        // if the getHandling committed the response!
        if (isCommitted())
            return;

        if (len > 0)
        {
            long written = _httpOutput.getWritten();
            if (written > len)
                throw new IllegalArgumentException("setContentLength(" + len + ") when already written " + written);

            _contentLength = len;
            getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, len);
            if (isAllContentWritten(written))
            {
                try
                {
                    closeOutput();
                }
                catch (IOException e)
                {
                    throw new RuntimeIOException(e);
                }
            }
        }
        else if (len == 0)
        {
            long written = _httpOutput.getWritten();
            if (written > 0)
                throw new IllegalArgumentException("setContentLength(0) when already written " + written);
            _contentLength = len;
            getHeaders().put(HttpHeader.CONTENT_LENGTH, "0");
        }
        else
        {
            _contentLength = len;
            getHeaders().remove(HttpHeader.CONTENT_LENGTH);
        }
    }

    public long getContentLength()
    {
        return _contentLength;
    }

    public void closeOutput() throws IOException
    {
        if (_outputType == OutputType.WRITER)
            _writer.close();
        else
            _httpOutput.close();
    }

    @Override
    public void reset()
    {
        super.reset();

        _httpOutput.resetBuffer();
        _outputType = OutputType.NONE;
        _contentLength = -1;
        _contentType = null;
        _mimeType = null;
        _characterEncoding = null;
        _encodingFrom = EncodingFrom.NOT_SET;

        // Clear all response headers
        HttpFields.Mutable headers = getHeaders();
        headers.clear();

        // recreate necessary connection related fields
        for (String value : getRequest().getHeaders().getCSV(HttpHeader.CONNECTION, false))
        {
            HttpHeaderValue cb = HttpHeaderValue.CACHE.get(value);
            if (cb != null)
            {
                switch (cb)
                {
                    case CLOSE:
                        headers.put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.toString());
                        break;
                    case KEEP_ALIVE:
                        if (HttpVersion.HTTP_1_0.is(_request.getConnectionMetaData().getProtocol()))
                            headers.put(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.toString());
                        break;
                    case TE:
                        headers.put(HttpHeader.CONNECTION, HttpHeaderValue.TE.toString());
                        break;
                    default:
                }
            }
        }

        // recreate session cookies
        HttpSession session = _request.getHttpServletRequest().getSession(false);
        if (session != null && session.isNew())
        {
            SessionHandler sh = _servletChannel.getContextHandler().getSessionHandler();
            if (sh != null)
            {
                // TODO: use Jan's new static method.
                if (session instanceof SessionHandler.ServletAPISession apiSession)
                {
                    HttpCookie c = sh.getSessionCookie(apiSession.getCoreSession(), _request.getContext().getContextPath(), _request.isSecure());
                    if (c != null)
                        Response.addCookie(_response, c);
                }
                else
                {
                    throw new IllegalStateException();
                }
            }
        }
    }

    public void resetContent()
    {
        _httpOutput.resetBuffer();
        _outputType = OutputType.NONE;
        _contentLength = -1;
        _contentType = null;
        _mimeType = null;
        _characterEncoding = null;
        _encodingFrom = EncodingFrom.NOT_SET;

        // remove the content related response headers and keep all others
        for (Iterator<HttpField> i = getHeaders().iterator(); i.hasNext(); )
        {
            HttpField field = i.next();
            if (field.getHeader() == null)
                continue;

            switch (field.getHeader())
            {
                case CONTENT_TYPE:
                case CONTENT_LENGTH:
                case CONTENT_ENCODING:
                case CONTENT_LANGUAGE:
                case CONTENT_RANGE:
                case CONTENT_MD5:
                case CONTENT_LOCATION:
                case TRANSFER_ENCODING:
                case CACHE_CONTROL:
                case LAST_MODIFIED:
                case EXPIRES:
                case ETAG:
                case DATE:
                case VARY:
                    i.remove();
                    continue;
                default:
            }
        }
    }

    /**
     * Get the raw value of the Character Encoding field.
     * <p>
     *     This is only the value as set, not from any other discovered source
     *     (eg: mimetypes, Content-Type, ServletContext, etc)
     * </p>
     *
     * @return the raw character encoding
     */
    public String getRawCharacterEncoding()
    {
        return _characterEncoding;
    }

    public String getCharacterEncoding(boolean setContentType)
    {
        // First try explicit char encoding.
        if (_characterEncoding != null)
            return _characterEncoding;

        String encoding;

        // Try charset from mime type.
        if (_mimeType != null && _mimeType.isCharsetAssumed())
            return _mimeType.getCharsetString();

        // Try charset assumed from content type (assumed charsets are not added to content type header).
        encoding = MimeTypes.getCharsetAssumedFromContentType(_contentType);
        if (encoding != null)
            return encoding;

        // Try char set inferred from content type.
        encoding = MimeTypes.getCharsetInferredFromContentType(_contentType);
        if (encoding != null)
        {
            if (setContentType)
                setCharacterEncoding(encoding, EncodingFrom.INFERRED);
            return encoding;
        }

        // Try any default char encoding for the context.
        ServletContext context = _servletChannel.getRequest().getContext().getServletContext();
        if (context != null)
        {
            encoding = context.getResponseCharacterEncoding();
            if (encoding != null)
            {
                if (setContentType)
                    setCharacterEncoding(encoding, EncodingFrom.DEFAULT);
                return encoding;
            }
        }

        // Fallback to last resort iso-8859-1.
        encoding = StringUtil.__ISO_8859_1;
        if (setContentType)
            setCharacterEncoding(encoding, EncodingFrom.DEFAULT);
        return encoding;
    }

    private void setCharacterEncoding(String encoding, EncodingFrom from)
    {
        if (isWriting() || isCommitted())
            return;

        if (encoding == null)
        {
            _encodingFrom = EncodingFrom.NOT_SET;
            if (_characterEncoding != null)
            {
                _characterEncoding = null;
                if (_mimeType != null)
                {
                    _mimeType = _mimeType.getBaseType();
                    _contentType = _mimeType.asString();
                    _response.getHeaders().put(_mimeType.getContentTypeField());
                }
                else if (_contentType != null)
                {
                    _contentType = MimeTypes.getContentTypeWithoutCharset(_contentType);
                    _response.getHeaders().put(HttpHeader.CONTENT_TYPE, _contentType);
                }
            }
        }
        else
        {
            _encodingFrom = from;
            _characterEncoding = HttpGenerator.__STRICT ? encoding : StringUtil.normalizeCharset(encoding);
            if (_mimeType != null)
            {
                _contentType = _mimeType.getBaseType().asString() + ";charset=" + _characterEncoding;
                _mimeType = MimeTypes.CACHE.get(_contentType);
                if (_mimeType == null || HttpGenerator.__STRICT)
                    _response.getHeaders().put(HttpHeader.CONTENT_TYPE, _contentType);
                else
                    _response.getHeaders().put(_mimeType.getContentTypeField());
            }
            else if (_contentType != null)
            {
                _contentType = MimeTypes.getContentTypeWithoutCharset(_contentType) + ";charset=" + _characterEncoding;
                _response.getHeaders().put(HttpHeader.CONTENT_TYPE, _contentType);
            }
        }
    }

    public boolean isWriting()
    {
        return _outputType == OutputType.WRITER;
    }

    public boolean isStreaming()
    {
        return _outputType == OutputType.STREAM;
    }

    public boolean isWritingOrStreaming()
    {
        return isWriting() || isStreaming();
    }

    private enum EncodingFrom
    {
        /**
         * Character encoding was not set, or the encoding was cleared with {@code setCharacterEncoding(null)}.
         */
        NOT_SET,

        /**
         * Using the default character encoding from the context otherwise iso-8859-1.
         */
        DEFAULT,

        /**
         * Character encoding was inferred from the Content-Type and will be added as a parameter to the Content-Type.
         */
        INFERRED,

        /**
         * The default character encoding of the locale was used after a call to {@link HttpServletResponse#setLocale(Locale)}.
         */
        SET_LOCALE,

        /**
         * The character encoding has been explicitly set using the Content-Type charset parameter with {@link #setContentType(String)}.
         */
        SET_CONTENT_TYPE,

        /**
         * The character encoding has been explicitly set using {@link HttpServletResponse#setCharacterEncoding(String)}.
         */
        SET_CHARACTER_ENCODING
    }

    public class ServletApiResponse implements HttpServletResponse
    {
        private final SharedBlockingCallback _blocker = new SharedBlockingCallback();
        private final Response _response;

        ServletApiResponse(Response response)
        {
            _response = response;
        }
        
        public ServletContextResponse getResponse()
        {
            return ServletContextResponse.this;
        }

        @Override
        public void addCookie(Cookie cookie)
        {
            //Servlet Spec 9.3 Include method: cannot set a cookie if handling an include
            if (StringUtil.isBlank(cookie.getName()))
                throw new IllegalArgumentException("Cookie.name cannot be blank/null");

            String comment = cookie.getComment();
            // HttpOnly was supported as a comment in cookie flags before the java.net.HttpCookie implementation so need to check that
            boolean httpOnly = cookie.isHttpOnly() || HttpCookie.isHttpOnlyInComment(comment);
            HttpCookie.SameSite sameSite = HttpCookie.getSameSiteFromComment(comment);
            comment = HttpCookie.getCommentWithoutAttributes(comment);

            addCookie(new HttpCookie(
                cookie.getName(),
                cookie.getValue(),
                cookie.getDomain(),
                cookie.getPath(),
                cookie.getMaxAge(),
                httpOnly,
                cookie.getSecure(),
                comment,
                cookie.getVersion(),
                sameSite));
        }

        public void addCookie(HttpCookie cookie)
        {
            Response.addCookie(ServletContextResponse.this, cookie);
        }

        @Override
        public boolean containsHeader(String name)
        {
            return _response.getHeaders().contains(name);
        }

        @Override
        public String encodeURL(String url)
        {
            SessionManager sessionManager = _servletChannel.getContextHandler().getSessionHandler();
            if (sessionManager == null)
                return url;

            ServletContextRequest request = _request;
            HttpServletRequest httpServletRequest = request.getHttpServletRequest();

            HttpURI uri = null;
            if (sessionManager.isCheckingRemoteSessionIdEncoding() && URIUtil.hasScheme(url))
            {
                uri = HttpURI.from(url);
                String path = uri.getPath();
                path = (path == null ? "" : path);
                int port = uri.getPort();
                if (port < 0)
                    port = HttpScheme.getDefaultPort(uri.getScheme());

                // Is it the same server?
                if (!Request.getServerName(request).equalsIgnoreCase(uri.getHost()))
                    return url;
                if (Request.getServerPort(request) != port)
                    return url;
                if (request.getContext() != null && !path.startsWith(request.getContext().getContextPath()))
                    return url;
            }

            String sessionURLPrefix = sessionManager.getSessionIdPathParameterNamePrefix();
            if (sessionURLPrefix == null)
                return url;

            if (url == null)
                return null;

            // should not encode if cookies in evidence
            if ((sessionManager.isUsingCookies() && httpServletRequest.isRequestedSessionIdFromCookie()) || !sessionManager.isUsingURLs())
            {
                int prefix = url.indexOf(sessionURLPrefix);
                if (prefix != -1)
                {
                    int suffix = url.indexOf("?", prefix);
                    if (suffix < 0)
                        suffix = url.indexOf("#", prefix);

                    if (suffix <= prefix)
                        return url.substring(0, prefix);
                    return url.substring(0, prefix) + url.substring(suffix);
                }
                return url;
            }

            // get session;
            HttpSession session = httpServletRequest.getSession(false);

            // no session
            if (session == null || !(session instanceof Session.APISession))
                return url;

            // invalid session
            Session.APISession apiSession = (Session.APISession)session;

            if (!apiSession.getCoreSession().isValid())
                return url;

            String id = apiSession.getCoreSession().getExtendedId();

            if (uri == null)
                uri = HttpURI.from(url);

            // Already encoded
            int prefix = url.indexOf(sessionURLPrefix);
            if (prefix != -1)
            {
                int suffix = url.indexOf("?", prefix);
                if (suffix < 0)
                    suffix = url.indexOf("#", prefix);

                if (suffix <= prefix)
                    return url.substring(0, prefix + sessionURLPrefix.length()) + id;
                return url.substring(0, prefix + sessionURLPrefix.length()) + id +
                    url.substring(suffix);
            }

            // edit the session
            int suffix = url.indexOf('?');
            if (suffix < 0)
                suffix = url.indexOf('#');
            if (suffix < 0)
            {
                return url +
                    ((HttpScheme.HTTPS.is(uri.getScheme()) || HttpScheme.HTTP.is(uri.getScheme())) && uri.getPath() == null ? "/" : "") + //if no path, insert the root path
                    sessionURLPrefix + id;
            }

            return url.substring(0, suffix) +
                ((HttpScheme.HTTPS.is(uri.getScheme()) || HttpScheme.HTTP.is(uri.getScheme())) && uri.getPath() == null ? "/" : "") + //if no path so insert the root path
                sessionURLPrefix + id + url.substring(suffix);
        }

        @Override
        public String encodeRedirectURL(String url)
        {
            return encodeURL(url);
        }

        @Override
        public void sendError(int sc, String msg) throws IOException
        {
            // TODO
            switch (sc)
            {
                case -1:
                {
                    _servletChannel.abort(new IOException(msg));
                    break;
                }
                case HttpStatus.PROCESSING_102:
                {
                    // TODO: should we check whether an Expect: 102 header is present?
                    if (!isCommitted())
                    {
                        try (Blocker.Callback blocker = Blocker.callback())
                        {
                            _response.setStatus(HttpStatus.PROCESSING_102);
                            _response.write(true, null, blocker);
                            blocker.block();
                        }
                    }
                    break;
                }
                case HttpStatus.EARLY_HINT_103:
                {
                    if (!isCommitted())
                    {
                        try (Blocker.Callback blocker = Blocker.callback())
                        {
                            _response.setStatus(HttpStatus.EARLY_HINT_103);
                            _response.write(true, null, blocker);
                            blocker.block();
                        }
                    }
                    break;
                }
                default:
                {
                    // This is just a state change
                    getState().sendError(sc, msg);
                    break;
                }
            }
        }

        @Override
        public void sendError(int sc) throws IOException
        {
            sendError(sc, null);
        }

        @Override
        public void sendRedirect(String location) throws IOException
        {
            sendRedirect(HttpServletResponse.SC_MOVED_TEMPORARILY, location);
        }

        /**
         * Sends a response with one of the 300 series redirection codes.
         *
         * @param code the redirect status code
         * @param location the location to send in {@code Location} headers
         * @throws IOException if unable to send the redirect
         */
        public void sendRedirect(int code, String location) throws IOException
        {
            FutureCallback callback = new FutureCallback();
            Response.sendRedirect(_request, ServletContextResponse.this, callback, code, location, false);
            callback.block();
        }

        @Override
        public void setDateHeader(String name, long date)
        {
            _response.getHeaders().putDateField(name, date);
        }

        @Override
        public void addDateHeader(String name, long date)
        {
            _response.getHeaders().addDateField(name, date);
        }

        @Override
        public void setHeader(String name, String value)
        {
            _response.getHeaders().put(name, value);
        }

        @Override
        public void addHeader(String name, String value)
        {
            _response.getHeaders().add(name, value);
        }

        @Override
        public void setIntHeader(String name, int value)
        {
            // TODO do we need int versions?
            _response.getHeaders().putLongField(name, value);
        }

        @Override
        public void addIntHeader(String name, int value)
        {
            // TODO do we need a native version?
            _response.getHeaders().add(name, Integer.toString(value));
        }

        @Override
        public void setStatus(int sc)
        {
            _response.setStatus(sc);
        }

        @Override
        public int getStatus()
        {
            return _response.getStatus();
        }

        @Override
        public String getHeader(String name)
        {
            return _response.getHeaders().get(name);
        }

        @Override
        public Collection<String> getHeaders(String name)
        {
            return _response.getHeaders().getValuesList(name);
        }

        @Override
        public Collection<String> getHeaderNames()
        {
            return _response.getHeaders().getFieldNamesCollection();
        }

        @Override
        public String getCharacterEncoding()
        {
            return ServletContextResponse.this.getCharacterEncoding(false);
        }

        @Override
        public String getContentType()
        {
            return _contentType;
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException
        {
            if (_outputType == OutputType.WRITER)
                throw new IllegalStateException("WRITER");
            _outputType = OutputType.STREAM;
            return _httpOutput;
        }

        @Override
        public PrintWriter getWriter() throws IOException
        {
            if (_outputType == OutputType.STREAM)
                throw new IllegalStateException("STREAM");

            if (_outputType == OutputType.NONE)
            {
                String encoding = ServletContextResponse.this.getCharacterEncoding(true);
                Locale locale = getLocale();
                if (_writer != null && _writer.isFor(locale, encoding))
                    _writer.reopen();
                else
                {
                    if (StringUtil.__ISO_8859_1.equalsIgnoreCase(encoding))
                        _writer = new ResponseWriter(new Iso88591HttpWriter(_httpOutput), locale, encoding);
                    else if (StringUtil.__UTF8.equalsIgnoreCase(encoding))
                        _writer = new ResponseWriter(new Utf8HttpWriter(_httpOutput), locale, encoding);
                    else
                        _writer = new ResponseWriter(new EncodingHttpWriter(_httpOutput, encoding), locale, encoding);
                }

                // Set the output type at the end, because setCharacterEncoding() checks for it.
                _outputType = OutputType.WRITER;
            }
            return _writer;
        }

        @Override
        public void setCharacterEncoding(String encoding)
        {
            ServletContextResponse.this.setCharacterEncoding(encoding, EncodingFrom.SET_CHARACTER_ENCODING);
        }

        @Override
        public void setContentLength(int len)
        {
            // Protect from setting after committed as default handling
            // of a servlet HEAD request ALWAYS sets _content length, even
            // if the getHandling committed the response!
            if (isCommitted())
                return;

            if (len > 0)
            {
                long written = _httpOutput.getWritten();
                if (written > len)
                    throw new IllegalArgumentException("setContentLength(" + len + ") when already written " + written);

                _contentLength = len;
                _response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, len);
                if (isAllContentWritten(written))
                {
                    try
                    {
                        closeOutput();
                    }
                    catch (IOException e)
                    {
                        throw new RuntimeIOException(e);
                    }
                }
            }
            else if (len == 0)
            {
                long written = _httpOutput.getWritten();
                if (written > 0)
                    throw new IllegalArgumentException("setContentLength(0) when already written " + written);
                _contentLength = len;
                _response.getHeaders().put(HttpHeader.CONTENT_LENGTH, "0");
            }
            else
            {
                _contentLength = len;
                _response.getHeaders().remove(HttpHeader.CONTENT_LENGTH);
            }
        }

        @Override
        public void setContentLengthLong(long len)
        {
            // Protect from setting after committed as default handling
            // of a servlet HEAD request ALWAYS sets _content length, even
            // if the getHandling committed the response!
            if (isCommitted())
                return;
            _contentLength = len;
            _response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH.toString(), len);
        }

        @Override
        public void setContentType(String contentType)
        {
            if (isCommitted())
                return;

            if (contentType == null)
            {
                if (isWriting() && _characterEncoding != null)
                    throw new IllegalSelectorException();

                if (_locale == null)
                    _characterEncoding = null;
                _mimeType = null;
                _contentType = null;
                _response.getHeaders().remove(HttpHeader.CONTENT_TYPE);
            }
            else
            {
                _contentType = contentType;
                _mimeType = MimeTypes.CACHE.get(contentType);

                String charset = MimeTypes.getCharsetFromContentType(contentType);
                if (charset == null && _mimeType != null && _mimeType.isCharsetAssumed())
                    charset = _mimeType.getCharsetString();

                if (charset == null)
                {
                    switch (_encodingFrom)
                    {
                        case NOT_SET:
                            break;
                        case DEFAULT:
                        case INFERRED:
                        case SET_CONTENT_TYPE:
                        case SET_LOCALE:
                        case SET_CHARACTER_ENCODING:
                        {
                            _contentType = contentType + ";charset=" + _characterEncoding;
                            _mimeType = MimeTypes.CACHE.get(_contentType);
                            break;
                        }
                        default:
                            throw new IllegalStateException(_encodingFrom.toString());
                    }
                }
                else if (isWriting() && !charset.equalsIgnoreCase(_characterEncoding))
                {
                    // too late to change the character encoding;
                    _contentType = MimeTypes.getContentTypeWithoutCharset(_contentType);
                    if (_characterEncoding != null  && (_mimeType == null || !_mimeType.isCharsetAssumed()))
                        _contentType = _contentType + ";charset=" + _characterEncoding;
                    _mimeType = MimeTypes.CACHE.get(_contentType);
                }
                else
                {
                    _characterEncoding = charset;
                    _encodingFrom = EncodingFrom.SET_CONTENT_TYPE;
                }

                if (HttpGenerator.__STRICT || _mimeType == null)
                    _response.getHeaders().put(HttpHeader.CONTENT_TYPE, _contentType);
                else
                {
                    _contentType = _mimeType.asString();
                    _response.getHeaders().put(_mimeType.getContentTypeField());
                }
            }
        }

        public long getContentCount()
        {
            return _httpOutput.getWritten();
        }

        @Override
        public void setBufferSize(int size)
        {
            if (isCommitted())
                throw new IllegalStateException("cannot set buffer size after response is in committed state");
            if (getContentCount() > 0)
                throw new IllegalStateException("cannot set buffer size after response has " + getContentCount() + " bytes already written");
            if (size < __MIN_BUFFER_SIZE)
                size = __MIN_BUFFER_SIZE;
            _httpOutput.setBufferSize(size);
        }

        @Override
        public int getBufferSize()
        {
            return _httpOutput.getBufferSize();
        }

        @Override
        public void flushBuffer() throws IOException
        {
            if (!_httpOutput.isClosed())
                _httpOutput.flush();
        }

        @Override
        public void resetBuffer()
        {
            _httpOutput.resetBuffer();
            _httpOutput.reopen();
        }

        @Override
        public boolean isCommitted()
        {
            // If we are in sendError state, we pretend to be committed
            if (_servletChannel.isSendError())
                return true;
            return _servletChannel.isCommitted();
        }

        @Override
        public void reset()
        {
            //TODO
            if (!_response.isCommitted())
                _response.reset();

            Session session = _servletChannel.getRequest().getServletApiRequest().getCoreSession();
            if (session != null && session.isNew())
            {
                ServletApiRequest servletApiRequest = _servletChannel.getRequest().getServletApiRequest();
                SessionManager sessionManager = servletApiRequest.getSessionManager();
                if (sessionManager != null)
                {
                    HttpCookie cookie = sessionManager.getSessionCookie(session, servletApiRequest.getContextPath(), servletApiRequest.getServletConnection().isSecure());
                    if (cookie != null)
                        addCookie(cookie);
                }
            }
        }

        @Override
        public void setLocale(Locale locale)
        {
            if (isCommitted())
                return;

            if (locale == null)
            {
                _locale = null;
                _response.getHeaders().remove(HttpHeader.CONTENT_LANGUAGE);
                if (_encodingFrom == EncodingFrom.SET_LOCALE)
                    ServletContextResponse.this.setCharacterEncoding(null, EncodingFrom.NOT_SET);
            }
            else
            {
                _locale = locale;
                _response.getHeaders().put(HttpHeader.CONTENT_LANGUAGE, StringUtil.replace(locale.toString(), '_', '-'));

                if (_outputType != OutputType.NONE)
                    return;

                ServletContextHandler.Context context = _servletChannel.getContext();
                if (context == null)
                    return;

                String charset = context.getServletContextHandler().getLocaleEncoding(locale);
                if (!StringUtil.isEmpty(charset) && __localeOverride.contains(_encodingFrom))
                    ServletContextResponse.this.setCharacterEncoding(charset, EncodingFrom.SET_LOCALE);
            }
        }

        @Override
        public Locale getLocale()
        {
            if (_locale == null)
                return Locale.getDefault();
            return _locale;
        }
    }
}
