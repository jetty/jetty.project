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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.channels.IllegalSelectorException;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.writer.EncodingHttpWriter;
import org.eclipse.jetty.ee10.servlet.writer.Iso88591HttpWriter;
import org.eclipse.jetty.ee10.servlet.writer.ResponseWriter;
import org.eclipse.jetty.ee10.servlet.writer.Utf8HttpWriter;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.session.ManagedSession;
import org.eclipse.jetty.session.SessionManager;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.StringUtil;

/**
 * The Jetty low level implementation of the ee10 {@link HttpServletResponse} object.
 *
 * <p>
 *     This provides the bridges from Servlet {@link HttpServletResponse} to the Jetty Core {@link Response} concepts (provided by the {@link ServletContextResponse})
 * </p>
 */
public class ServletApiResponse implements HttpServletResponse
{
    private static final int MIN_BUFFER_SIZE = 1;
    private static final EnumSet<ServletContextResponse.EncodingFrom> LOCALE_OVERRIDE = EnumSet.of(
        ServletContextResponse.EncodingFrom.NOT_SET,
        ServletContextResponse.EncodingFrom.DEFAULT,
        ServletContextResponse.EncodingFrom.INFERRED,
        ServletContextResponse.EncodingFrom.SET_LOCALE
    );

    private final ServletContextResponse _response;

    protected ServletApiResponse(ServletContextResponse response)
    {
        _response = response;
    }

    public ServletContextResponse getResponse()
    {
        return _response;
    }

    @Override
    public void addCookie(Cookie cookie)
    {
        if (StringUtil.isBlank(cookie.getName()))
            throw new IllegalArgumentException("Cookie.name cannot be blank/null");

        addCookie(new HttpCookieFacade(cookie));
    }

    public void addCookie(HttpCookie cookie)
    {
        Response.addCookie(_response, cookie);
    }

    @Override
    public boolean containsHeader(String name)
    {
        return _response.getHeaders().contains(name);
    }

    @Override
    public String encodeURL(String url)
    {
        SessionManager sessionManager = _response.getServletContextRequest().getServletChannel().getContextHandler().getSessionHandler();
        if (sessionManager == null)
            return url;
        return sessionManager.encodeURI(_response.getServletContextRequest(), url, getResponse().getServletContextRequest().getServletApiRequest().isRequestedSessionIdFromCookie());
    }

    @Override
    public String encodeRedirectURL(String url)
    {
        return encodeURL(url);
    }

    @Override
    public void sendError(int sc, String msg) throws IOException
    {
        switch (sc)
        {
            case -1 -> _response.getServletContextRequest().getServletChannel().abort(new IOException(msg));
            case HttpStatus.PROCESSING_102, HttpStatus.EARLY_HINT_103 ->
            {
                if (!isCommitted())
                {
                    try (Blocker.Callback blocker = Blocker.callback())
                    {
                        CompletableFuture<Void> completable = _response.writeInterim(sc, _response.getHeaders().asImmutable());
                        blocker.completeWith(completable);
                        blocker.block();
                    }
                }
            }
            default ->
            {
                if (isCommitted())
                    throw new IllegalStateException("Committed");
                _response.getState().sendError(sc, msg);
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
        resetBuffer();
        FutureCallback callback = new FutureCallback();
        Response.sendRedirect(_response.getServletContextRequest(), _response, callback, code, location, false);
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
        return _response.getCharacterEncoding(false);
    }

    @Override
    public String getContentType()
    {
        return _response.getContentType();
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException
    {
        if (_response.getOutputType() == ServletContextResponse.OutputType.WRITER)
            throw new IllegalStateException("WRITER");
        _response.setOutputType(ServletContextResponse.OutputType.STREAM);
        return _response.getHttpOutput();
    }

    @Override
    public PrintWriter getWriter() throws IOException
    {
        if (_response.getOutputType() == ServletContextResponse.OutputType.STREAM)
            throw new IllegalStateException("STREAM");

        if (_response.getOutputType() == ServletContextResponse.OutputType.NONE)
        {
            String encoding = _response.getCharacterEncoding(true);
            Locale locale = getLocale();
            if (_response.getWriter() != null && _response.getWriter().isFor(locale, encoding))
                _response.getWriter().reopen();
            else
            {
                if (StringUtil.__ISO_8859_1.equalsIgnoreCase(encoding))
                    _response.setWriter(new ResponseWriter(new Iso88591HttpWriter(_response.getHttpOutput()), locale, encoding));
                else if (StringUtil.__UTF8.equalsIgnoreCase(encoding))
                    _response.setWriter(new ResponseWriter(new Utf8HttpWriter(_response.getHttpOutput()), locale, encoding));
                else
                    _response.setWriter(new ResponseWriter(new EncodingHttpWriter(_response.getHttpOutput(), encoding), locale, encoding));
            }

            // Set the output type at the end, because setCharacterEncoding() checks for it.
            _response.setOutputType(ServletContextResponse.OutputType.WRITER);
        }
        return _response.getWriter();
    }

    @Override
    public void setCharacterEncoding(String encoding)
    {
        _response.setCharacterEncoding(encoding, ServletContextResponse.EncodingFrom.SET_CHARACTER_ENCODING);
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
            long written = _response.getHttpOutput().getWritten();
            if (written > len)
                throw new IllegalArgumentException("setContentLength(" + len + ") when already written " + written);

            _response.setContentLength(len);
            _response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, len);
            if (_response.isAllContentWritten(written))
            {
                try
                {
                    _response.closeOutput();
                }
                catch (IOException e)
                {
                    throw new RuntimeIOException(e);
                }
            }
        }
        else if (len == 0)
        {
            long written = _response.getHttpOutput().getWritten();
            if (written > 0)
                throw new IllegalArgumentException("setContentLength(0) when already written " + written);
            _response.setContentLength(len);
            _response.getHeaders().put(HttpHeader.CONTENT_LENGTH, "0");
        }
        else
        {
            _response.setContentLength(len);
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
        _response.setContentLength(len);
        _response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH.toString(), len);
    }

    @Override
    public void setContentType(String contentType)
    {
        if (isCommitted())
            return;

        if (contentType == null)
        {
            if (_response.isWriting() && _response.getCharacterEncoding() != null)
                throw new IllegalSelectorException();

            if (_response.getLocale() == null)
                _response.setCharacterEncoding(null);
            _response.setMimeType(null);
            _response.setContentType(null);
            _response.getHeaders().remove(HttpHeader.CONTENT_TYPE);
        }
        else
        {
            _response.setContentType(contentType);
            _response.setMimeType(MimeTypes.CACHE.get(contentType));

            String charset = MimeTypes.getCharsetFromContentType(contentType);
            if (charset == null && _response.getMimeType() != null && _response.getMimeType().isCharsetAssumed())
                charset = _response.getMimeType().getCharsetString();

            if (charset == null)
            {
                switch (_response.getEncodingFrom())
                {
                    case NOT_SET:
                        break;
                    case DEFAULT:
                    case INFERRED:
                    case SET_CONTENT_TYPE:
                    case SET_LOCALE:
                    case SET_CHARACTER_ENCODING:
                    {
                        _response.setContentType(contentType + ";charset=" + _response.getCharacterEncoding());
                        _response.setMimeType(MimeTypes.CACHE.get(_response.getContentType()));
                        break;
                    }
                    default:
                        throw new IllegalStateException(_response.getEncodingFrom().toString());
                }
            }
            else if (_response.isWriting() && !charset.equalsIgnoreCase(_response.getCharacterEncoding()))
            {
                // too late to change the character encoding;
                _response.setContentType(MimeTypes.getContentTypeWithoutCharset(_response.getContentType()));
                if (_response.getCharacterEncoding() != null && (_response.getMimeType() == null || !_response.getMimeType().isCharsetAssumed()))
                    _response.setContentType(_response.getContentType() + ";charset=" + _response.getCharacterEncoding());
                _response.setMimeType(MimeTypes.CACHE.get(_response.getContentType()));
            }
            else
            {
                _response.setRawCharacterEncoding(charset, ServletContextResponse.EncodingFrom.SET_CONTENT_TYPE);
            }

            if (HttpGenerator.__STRICT || _response.getMimeType() == null)
                _response.getHeaders().put(HttpHeader.CONTENT_TYPE, _response.getContentType());
            else
            {
                _response.setContentType(_response.getMimeType().asString());
                _response.getHeaders().put(_response.getMimeType().getContentTypeField());
            }
        }
    }

    public long getContentCount()
    {
        return _response.getHttpOutput().getWritten();
    }

    @Override
    public void setBufferSize(int size)
    {
        if (isCommitted())
            throw new IllegalStateException("cannot set buffer size after response is in committed state");
        if (getContentCount() > 0)
            throw new IllegalStateException("cannot set buffer size after response has " + getContentCount() + " bytes already written");
        if (size < MIN_BUFFER_SIZE)
            size = MIN_BUFFER_SIZE;
        _response.getHttpOutput().setBufferSize(size);
    }

    @Override
    public int getBufferSize()
    {
        return _response.getHttpOutput().getBufferSize();
    }

    @Override
    public void flushBuffer() throws IOException
    {
        if (!_response.getHttpOutput().isClosed())
            _response.getHttpOutput().flush();
    }

    @Override
    public void resetBuffer()
    {
        _response.getHttpOutput().resetBuffer();
        _response.getHttpOutput().reopen();
    }

    @Override
    public boolean isCommitted()
    {
        // If we are in sendError state, we pretend to be committed
        if (_response.getServletContextRequest().getServletChannel().isSendError())
            return true;
        return _response.getServletContextRequest().getServletChannel().isCommitted();
    }

    @Override
    public void reset()
    {
        if (isCommitted())
            throw new IllegalStateException("Committed");

        _response.reset();


        ServletApiRequest servletApiRequest = _response.getServletContextRequest().getServletApiRequest();
        ManagedSession session = servletApiRequest.getServletContextRequest().getManagedSession();
        if (session != null && session.isNew())
        {
            SessionManager sessionManager = servletApiRequest.getServletContextRequest().getSessionManager();
            if (sessionManager != null)
            {
                HttpCookie cookie = sessionManager.getSessionCookie(session, servletApiRequest.getServletConnection().isSecure());
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
            _response.setLocale(null);
            _response.getHeaders().remove(HttpHeader.CONTENT_LANGUAGE);
            if (_response.getEncodingFrom() == ServletContextResponse.EncodingFrom.SET_LOCALE)
                _response.setCharacterEncoding(null, ServletContextResponse.EncodingFrom.NOT_SET);
        }
        else
        {
            _response.setLocale(locale);
            _response.getHeaders().put(HttpHeader.CONTENT_LANGUAGE, StringUtil.replace(locale.toString(), '_', '-'));

            if (_response.getOutputType() != ServletContextResponse.OutputType.NONE)
                return;

            ServletContextHandler.ServletScopedContext context = _response.getServletContextRequest().getServletChannel().getContext();
            if (context == null)
                return;

            String charset = context.getServletContextHandler().getLocaleEncoding(locale);
            if (!StringUtil.isEmpty(charset) && LOCALE_OVERRIDE.contains(_response.getEncodingFrom()))
                _response.setCharacterEncoding(charset, ServletContextResponse.EncodingFrom.SET_LOCALE);
        }
    }

    @Override
    public Locale getLocale()
    {
        if (_response.getLocale() == null)
            return Locale.getDefault();
        return _response.getLocale();
    }

    @Override
    public Supplier<Map<String, String>> getTrailerFields()
    {
        return _response.getTrailers();
    }

    @Override
    public void setTrailerFields(Supplier<Map<String, String>> trailers)
    {
        if (isCommitted())
            throw new IllegalStateException("Committed");
        HttpVersion version = HttpVersion.fromString(_response.getServletContextRequest().getConnectionMetaData().getProtocol());
        if (version == null || version.compareTo(HttpVersion.HTTP_1_1) < 0)
            throw new IllegalStateException("Trailers not supported in " + version);

        _response.setTrailers(trailers);

        _response.setTrailersSupplier(() ->
        {
            Map<String, String> map = trailers.get();
            if (map == null)
                return null;
            HttpFields.Mutable fields = HttpFields.build(map.size());
            for (Map.Entry<String, String> e : map.entrySet())
            {
                fields.add(e.getKey(), e.getValue());
            }
            return fields;
        });
    }

    static class HttpCookieFacade implements HttpCookie
    {
        private final Cookie _cookie;

        public HttpCookieFacade(Cookie cookie)
        {
            _cookie = cookie;
        }

        @Override
        public String getComment()
        {
            return _cookie.getComment();
        }

        @Override
        public String getDomain()
        {
            return _cookie.getDomain();
        }

        @Override
        public long getMaxAge()
        {
            return _cookie.getMaxAge();
        }

        @Override
        public String getPath()
        {
            return _cookie.getPath();
        }

        @Override
        public boolean isSecure()
        {
            return _cookie.getSecure();
        }

        @Override
        public String getName()
        {
            return _cookie.getName();
        }

        @Override
        public String getValue()
        {
            return _cookie.getValue();
        }

        @Override
        public int getVersion()
        {
            return _cookie.getVersion();
        }

        @Override
        public SameSite getSameSite()
        {
            return SameSite.from(getAttributes().get(HttpCookie.SAME_SITE_ATTRIBUTE));
        }

        @Override
        public boolean isHttpOnly()
        {
            return _cookie.isHttpOnly();
        }

        @Override
        public Map<String, String> getAttributes()
        {
            return Collections.emptyMap();
        }

        @Override
        public int hashCode()
        {
            return HttpCookie.hashCode(this);
        }

        @Override
        public boolean equals(Object obj)
        {
            return HttpCookie.equals(this, obj);
        }

        @Override
        public String toString()
        {
            return HttpCookie.toString(this);
        }
    }
}
