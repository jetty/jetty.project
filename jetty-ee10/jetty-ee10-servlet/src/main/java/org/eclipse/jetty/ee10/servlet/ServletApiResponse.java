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
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.session.ManagedSession;
import org.eclipse.jetty.session.SessionManager;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;

/**
 * The Jetty implementation of the ee10 {@link HttpServletResponse} object.
 * This provides the bridge from the Servlet {@link HttpServletResponse} to the Jetty Core {@link Response}
 * via the {@link ServletContextResponse}.
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

    private final ServletContextResponse _servletContextResponse;
    private final ServletChannel _servletChannel;

    protected ServletApiResponse(ServletContextResponse servletContextResponse)
    {
        _servletContextResponse = servletContextResponse;
        _servletChannel = getServletContextResponse().getState().getServletChannel();
    }
    
    private ServletContextRequest getServletContextRequest()
    {
        return getServletContextResponse().getServletContextRequest();
    }

    /**
     * @return The ServetContextResponse as wrapped by the {@link ServletContextHandler}.
     * @see #getResponse()
     */
    public ServletContextResponse getServletContextResponse()
    {
        return _servletContextResponse;
    }

    /**
     * @return The core {@link Response} associated with the API response.
     *         This may differ from {@link #getServletContextResponse()} if the response was wrapped by another handler
     *         after the {@link ServletContextHandler} and passed to {@link ServletChannel#associate(Request, Response, Callback)}.
     * @see #getServletContextResponse()
     * @see ServletChannel#associate(Request, Response, Callback)
     */
    public Response getResponse()
    {
        return _servletChannel.getResponse();
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
        Response.addCookie(_servletContextResponse, cookie);
    }

    @Override
    public boolean containsHeader(String name)
    {
        return getResponse().getHeaders().contains(name);
    }

    @Override
    public String encodeURL(String url)
    {
        SessionManager sessionManager = getServletContextRequest().getServletChannel().getContextHandler().getSessionHandler();
        if (sessionManager == null)
            return url;
        return sessionManager.encodeURI(getServletContextRequest(), url, getServletContextResponse().getServletContextRequest().getServletApiRequest().isRequestedSessionIdFromCookie());
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
            case -1 -> getServletContextRequest().getServletChannel().abort(new IOException(msg));
            case HttpStatus.PROCESSING_102, HttpStatus.EARLY_HINT_103 ->
            {
                if (!isCommitted())
                {
                    try (Blocker.Callback blocker = Blocker.callback())
                    {
                        CompletableFuture<Void> completable = getServletContextResponse().writeInterim(sc, getResponse().getHeaders().asImmutable());
                        blocker.completeWith(completable);
                        blocker.block();
                    }
                }
            }
            default ->
            {
                if (isCommitted())
                    throw new IllegalStateException("Committed");
                getServletContextResponse().getState().sendError(sc, msg);
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
        try (Blocker.Callback callback = Blocker.callback())
        {
            Response.sendRedirect(getServletContextRequest(), _servletContextResponse, callback, code, location, false);
            callback.block();
        }
    }

    @Override
    public void setDateHeader(String name, long date)
    {
        getResponse().getHeaders().putDate(name, date);
    }

    @Override
    public void addDateHeader(String name, long date)
    {
        getResponse().getHeaders().addDateField(name, date);
    }

    @Override
    public void setHeader(String name, String value)
    {
        getResponse().getHeaders().put(name, value);
    }

    @Override
    public void addHeader(String name, String value)
    {
        getResponse().getHeaders().add(name, value);
    }

    @Override
    public void setIntHeader(String name, int value)
    {
        // TODO do we need int versions?
        if (!isCommitted())
            getResponse().getHeaders().put(name, value);
    }

    @Override
    public void addIntHeader(String name, int value)
    {
        // TODO do we need a native version?
        if (!isCommitted())
            getResponse().getHeaders().add(name, Integer.toString(value));
    }

    @Override
    public void setStatus(int sc)
    {
        getResponse().setStatus(sc);
    }

    @Override
    public int getStatus()
    {
        return getResponse().getStatus();
    }

    @Override
    public String getHeader(String name)
    {
        return getResponse().getHeaders().get(name);
    }

    @Override
    public Collection<String> getHeaders(String name)
    {
        return getResponse().getHeaders().getValuesList(name);
    }

    @Override
    public Collection<String> getHeaderNames()
    {
        return getResponse().getHeaders().getFieldNamesCollection();
    }

    @Override
    public String getCharacterEncoding()
    {
        return getServletContextResponse().getCharacterEncoding(false);
    }

    @Override
    public String getContentType()
    {
        return getServletContextResponse().getContentType();
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException
    {
        if (getServletContextResponse().getOutputType() == ServletContextResponse.OutputType.WRITER)
            throw new IllegalStateException("WRITER");
        getServletContextResponse().setOutputType(ServletContextResponse.OutputType.STREAM);
        return _servletChannel.getHttpOutput();
    }

    @Override
    public PrintWriter getWriter() throws IOException
    {
        if (getServletContextResponse().getOutputType() == ServletContextResponse.OutputType.STREAM)
            throw new IllegalStateException("STREAM");

        ResponseWriter writer = getServletContextResponse().getWriter();
        if (getServletContextResponse().getOutputType() == ServletContextResponse.OutputType.NONE)
        {
            String encoding = getServletContextResponse().getCharacterEncoding(true);
            Locale locale = getLocale();
            if (writer != null && writer.isFor(locale, encoding))
                writer.reopen();
            else
            {
                if (StringUtil.__ISO_8859_1.equalsIgnoreCase(encoding))
                    getServletContextResponse().setWriter(writer = new ResponseWriter(new Iso88591HttpWriter(_servletChannel.getHttpOutput()), locale, encoding));
                else if (StringUtil.__UTF8.equalsIgnoreCase(encoding))
                    getServletContextResponse().setWriter(writer = new ResponseWriter(new Utf8HttpWriter(_servletChannel.getHttpOutput()), locale, encoding));
                else
                    getServletContextResponse().setWriter(writer = new ResponseWriter(new EncodingHttpWriter(_servletChannel.getHttpOutput(), encoding), locale, encoding));
            }

            // Set the output type at the end, because setCharacterEncoding() checks for it.
            getServletContextResponse().setOutputType(ServletContextResponse.OutputType.WRITER);
        }
        return writer;
    }

    @Override
    public void setCharacterEncoding(String encoding)
    {
        getServletContextResponse().setCharacterEncoding(encoding, ServletContextResponse.EncodingFrom.SET_CHARACTER_ENCODING);
    }

    @Override
    public void setContentLength(int len)
    {
        setContentLengthLong(len);
    }

    @Override
    public void setContentLengthLong(long len)
    {
        // Protect from setting after committed as default handling
        // of a servlet HEAD request ALWAYS sets _content length, even
        // if the getHandling committed the response!
        if (isCommitted())
            return;

        if (len > 0)
            getResponse().getHeaders().put(HttpHeader.CONTENT_LENGTH, len);
        else if (len == 0)
            getResponse().getHeaders().put(HttpFields.CONTENT_LENGTH_0);
        else
            getResponse().getHeaders().remove(HttpHeader.CONTENT_LENGTH);
    }

    @Override
    public void setContentType(String contentType)
    {
        if (isCommitted())
            return;

        if (contentType == null)
        {
            if (_servletContextResponse.isWriting() && getServletContextResponse().getCharacterEncoding() != null)
                throw new IllegalStateException();

            getResponse().getHeaders().remove(HttpHeader.CONTENT_TYPE);
        }
        else
        {
            getResponse().getHeaders().put(HttpHeader.CONTENT_TYPE, contentType);
        }
    }

    public long getContentCount()
    {
        return _servletChannel.getHttpOutput().getWritten();
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
        _servletChannel.getHttpOutput().setBufferSize(size);
    }

    @Override
    public int getBufferSize()
    {
        return _servletChannel.getHttpOutput().getBufferSize();
    }

    @Override
    public void flushBuffer() throws IOException
    {
        if (!_servletChannel.getHttpOutput().isClosed())
            _servletChannel.getHttpOutput().flush();
    }

    @Override
    public void resetBuffer()
    {
        if (isCommitted())
            throw new IllegalStateException("Committed");
        _servletChannel.getHttpOutput().resetBuffer();
        _servletChannel.getHttpOutput().reopen();
    }

    @Override
    public boolean isCommitted()
    {
        // If we are in sendError state, we pretend to be committed
        if (getServletContextRequest().getServletChannel().isSendError())
            return true;
        return getServletContextRequest().getServletChannel().isCommitted();
    }

    @Override
    public void reset()
    {
        if (isCommitted())
            throw new IllegalStateException("Committed");

        getResponse().reset();


        ServletApiRequest servletApiRequest = getServletContextRequest().getServletApiRequest();
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
            getServletContextResponse().setLocale(null);
            getResponse().getHeaders().remove(HttpHeader.CONTENT_LANGUAGE);
            if (getServletContextResponse().getEncodingFrom() == ServletContextResponse.EncodingFrom.SET_LOCALE)
                getServletContextResponse().setCharacterEncoding(null, ServletContextResponse.EncodingFrom.NOT_SET);
        }
        else
        {
            getServletContextResponse().setLocale(locale);
            getResponse().getHeaders().put(HttpHeader.CONTENT_LANGUAGE, StringUtil.replace(locale.toString(), '_', '-'));

            if (getServletContextResponse().getOutputType() != ServletContextResponse.OutputType.NONE)
                return;

            ServletContextHandler.ServletScopedContext context = getServletContextRequest().getServletChannel().getContext();
            if (context == null)
                return;

            String charset = context.getServletContextHandler().getLocaleEncoding(locale);
            if (!StringUtil.isEmpty(charset) && LOCALE_OVERRIDE.contains(getServletContextResponse().getEncodingFrom()))
                getServletContextResponse().setCharacterEncoding(charset, ServletContextResponse.EncodingFrom.SET_LOCALE);
        }
    }

    @Override
    public Locale getLocale()
    {
        if (getServletContextResponse().getLocale() == null)
            return Locale.getDefault();
        return getServletContextResponse().getLocale();
    }

    @Override
    public Supplier<Map<String, String>> getTrailerFields()
    {
        return getServletContextResponse().getTrailers();
    }

    @Override
    public void setTrailerFields(Supplier<Map<String, String>> trailers)
    {
        if (isCommitted())
            throw new IllegalStateException("Committed");
        HttpVersion version = HttpVersion.fromString(getServletContextRequest().getConnectionMetaData().getProtocol());
        if (version == null || version.compareTo(HttpVersion.HTTP_1_1) < 0)
            throw new IllegalStateException("Trailers not supported in " + version);

        getServletContextResponse().setTrailers(trailers);

        getServletContextResponse().setTrailersSupplier(() ->
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

    @Override
    public String toString()
    {
        return "%s@%x{%s,%s}".formatted(this.getClass().getSimpleName(), hashCode(), getResponse(), getServletContextResponse());
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
            return obj instanceof HttpCookie && HttpCookie.equals(this, obj);
        }

        @Override
        public String toString()
        {
            return HttpCookie.toString(this);
        }
    }
}
