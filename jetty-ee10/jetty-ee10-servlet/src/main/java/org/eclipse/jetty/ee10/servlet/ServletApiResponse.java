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
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler.ServletRequestInfo;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler.ServletResponseInfo;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.WriteThroughWriter;
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

    private final ServletChannel _servletChannel;
    private final ServletContextHandler.ServletRequestInfo _servletRequestInfo;
    private final ServletResponseInfo _servletResponseInfo;

    protected ServletApiResponse(ServletContextResponse servletContextResponse)
    {
        _servletChannel = servletContextResponse.getServletContextRequest().getServletChannel();
        _servletRequestInfo = servletContextResponse.getServletContextRequest();
        _servletResponseInfo = servletContextResponse;
    }

    public ServletChannel getServletChannel()
    {
        return _servletChannel;
    }

    public ServletRequestInfo getServletRequestInfo()
    {
        return _servletRequestInfo;
    }

    /**
     * @return The {@link ServletResponseInfo} for the request as provided by
     * {@link ServletContextResponse} when wrapped by the {@link ServletContextHandler}.
     */
    public ServletResponseInfo getServletResponseInfo()
    {
        return _servletResponseInfo;
    }

    /**
     * @return The core {@link Response} associated with the API response.
     *         This may differ from the {@link ServletContextResponse} as wrapped by the
     *         {@link ServletContextHandler} as it may have subsequently been wrapped before
     *         being passed to {@link ServletChannel#associate(Request, Response, Callback)}.
     */
    public Response getResponse()
    {
        return getServletChannel().getResponse();
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
        Response.addCookie(getResponse(), cookie);
    }

    @Override
    public boolean containsHeader(String name)
    {
        return getResponse().getHeaders().contains(name);
    }

    @Override
    public String encodeURL(String url)
    {
        SessionManager sessionManager = getServletChannel().getServletContextHandler().getSessionHandler();
        if (sessionManager == null)
            return url;
        return sessionManager.encodeURI(getServletChannel().getRequest(), url,
            getServletChannel().getServletContextRequest().getServletApiRequest().isRequestedSessionIdFromCookie());
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
            case -1 -> getServletChannel().abort(new Request.Handler.AbortException(msg));
            case HttpStatus.PROCESSING_102, HttpStatus.EARLY_HINTS_103 ->
            {
                if (!isCommitted())
                {
                    try (Blocker.Callback blocker = Blocker.callback())
                    {
                        CompletableFuture<Void> completable = getServletChannel().getServletContextResponse().writeInterim(sc, getResponse().getHeaders().asImmutable());
                        blocker.completeWith(completable);
                        blocker.block();
                    }
                }
            }
            default ->
            {
                if (isCommitted())
                    throw new IllegalStateException("Committed");
                getServletRequestInfo().getServletRequestState().sendError(sc, msg);
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
            Response.sendRedirect(getServletRequestInfo().getRequest(), getResponse(), callback, code, location, false);
            callback.block();

            // Close the HttpOutput.
            ServletResponseInfo info = getServletResponseInfo();
            if (info.getOutputType() == ServletContextResponse.OutputType.WRITER)
                info.getWriter().close();
            else
                _servletChannel.getHttpOutput().close();
        }
    }

    @Override
    public void setDateHeader(String name, long date)
    {
        if (name == null)
            return; // Spec is to do nothing

        getResponse().getHeaders().putDate(name, date);
    }

    @Override
    public void addDateHeader(String name, long date)
    {
        if (name == null)
            return; // Spec is to do nothing

        getResponse().getHeaders().addDateField(name, date);
    }

    @Override
    public void setHeader(String name, String value)
    {
        if (name == null)
            return; // Spec is to do nothing

        getResponse().getHeaders().put(name, value);
    }

    @Override
    public void addHeader(String name, String value)
    {
        if (name == null)
            return; // Spec is to do nothing

        getResponse().getHeaders().add(name, value);
    }

    @Override
    public void setIntHeader(String name, int value)
    {
        if (name == null)
            return; // Spec is to do nothing

        // TODO do we need int versions?
        if (!isCommitted())
            getResponse().getHeaders().put(name, value);
    }

    @Override
    public void addIntHeader(String name, int value)
    {
        if (name == null)
            return; // Spec is to do nothing

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
        int status = getResponse().getStatus();
        return status == 0 ? 200 : status;
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
        return getServletResponseInfo().getCharacterEncoding(false);
    }

    @Override
    public String getContentType()
    {
        return getServletResponseInfo().getContentType();
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException
    {
        if (getServletResponseInfo().getOutputType() == ServletContextResponse.OutputType.WRITER)
            throw new IllegalStateException("WRITER");
        getServletResponseInfo().setOutputType(ServletContextResponse.OutputType.STREAM);
        return getServletChannel().getHttpOutput();
    }

    @Override
    public PrintWriter getWriter() throws IOException
    {
        if (getServletResponseInfo().getOutputType() == ServletContextResponse.OutputType.STREAM)
            throw new IllegalStateException("STREAM");

        ResponseWriter writer = getServletResponseInfo().getWriter();
        if (getServletResponseInfo().getOutputType() == ServletContextResponse.OutputType.NONE)
        {
            String encoding = getServletResponseInfo().getCharacterEncoding(true);
            Locale locale = getLocale();
            if (writer != null && writer.isFor(locale, encoding))
                writer.reopen();
            else
            {
                // We must use an implementation of AbstractOutputStreamWriter here as we rely on the non cached characters
                // in the writer implementation for flush and completion operations.
                WriteThroughWriter outputStreamWriter = WriteThroughWriter.newWriter(getServletChannel().getHttpOutput(), encoding);
                getServletResponseInfo().setWriter(writer = new ResponseWriter(
                    outputStreamWriter, locale, encoding));
            }

            // Set the output type at the end, because setCharacterEncoding() checks for it.
            getServletResponseInfo().setOutputType(ServletContextResponse.OutputType.WRITER);
        }
        return writer;
    }

    @Override
    public void setCharacterEncoding(String encoding)
    {
        getServletResponseInfo().setCharacterEncoding(encoding, ServletContextResponse.EncodingFrom.SET_CHARACTER_ENCODING);
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
            if (getServletResponseInfo().isWriting() && getServletResponseInfo().getCharacterEncoding() != null)
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
        return getServletChannel().getHttpOutput().getWritten();
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
        getServletChannel().getHttpOutput().setBufferSize(size);
    }

    @Override
    public int getBufferSize()
    {
        return getServletChannel().getHttpOutput().getBufferSize();
    }

    @Override
    public void flushBuffer() throws IOException
    {
        if (!getServletChannel().getHttpOutput().isClosed())
            getServletChannel().getHttpOutput().flush();
    }

    @Override
    public void resetBuffer()
    {
        if (isCommitted())
            throw new IllegalStateException("Committed");
        getServletChannel().getHttpOutput().resetBuffer();
        getServletChannel().getHttpOutput().reopen();
    }

    @Override
    public boolean isCommitted()
    {
        // If we are in sendError state, we pretend to be committed
        if (getServletChannel().isSendError())
            return true;
        return getServletChannel().isCommitted();
    }

    @Override
    public void reset()
    {
        if (isCommitted())
            throw new IllegalStateException("Committed");

        getResponse().reset();

        ServletApiRequest servletApiRequest = getServletChannel().getServletContextRequest().getServletApiRequest();
        ManagedSession session = servletApiRequest.getServletRequestInfo().getManagedSession();
        if (session != null && session.isNew())
        {
            SessionManager sessionManager = servletApiRequest.getServletRequestInfo().getSessionManager();
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
            getServletResponseInfo().setLocale(null);
            getResponse().getHeaders().remove(HttpHeader.CONTENT_LANGUAGE);
            if (getServletResponseInfo().getEncodingFrom() == ServletContextResponse.EncodingFrom.SET_LOCALE)
                getServletResponseInfo().setCharacterEncoding(null, ServletContextResponse.EncodingFrom.NOT_SET);
        }
        else
        {
            getServletResponseInfo().setLocale(locale);
            getResponse().getHeaders().put(HttpHeader.CONTENT_LANGUAGE, StringUtil.replace(locale.toString(), '_', '-'));

            if (getServletResponseInfo().getOutputType() != ServletContextResponse.OutputType.NONE)
                return;

            ServletContextHandler.ServletScopedContext context = getServletChannel().getContext();
            if (context == null)
                return;

            String charset = context.getServletContextHandler().getLocaleEncoding(locale);
            if (!StringUtil.isEmpty(charset) && LOCALE_OVERRIDE.contains(getServletResponseInfo().getEncodingFrom()))
                getServletResponseInfo().setCharacterEncoding(charset, ServletContextResponse.EncodingFrom.SET_LOCALE);
        }
    }

    @Override
    public Locale getLocale()
    {
        if (getServletResponseInfo().getLocale() == null)
            return Locale.getDefault();
        return getServletResponseInfo().getLocale();
    }

    @Override
    public Supplier<Map<String, String>> getTrailerFields()
    {
        return getServletResponseInfo().getTrailers();
    }

    @Override
    public void setTrailerFields(Supplier<Map<String, String>> trailers)
    {
        if (isCommitted())
            throw new IllegalStateException("Committed");
        HttpVersion version = HttpVersion.fromString(getServletRequestInfo().getRequest().getConnectionMetaData().getProtocol());
        if (version == null || version.compareTo(HttpVersion.HTTP_1_1) < 0)
            throw new IllegalStateException("Trailers not supported in " + version);

        getServletResponseInfo().setTrailers(trailers);
        getResponse().setTrailersSupplier(() ->
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
        return "%s@%x{%s,%s}".formatted(this.getClass().getSimpleName(), hashCode(), getResponse(), getServletResponseInfo());
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
        public boolean isPartitioned()
        {
            return Boolean.parseBoolean(getAttributes().get(HttpCookie.PARTITIONED_ATTRIBUTE));
        }

        @Override
        public Map<String, String> getAttributes()
        {
            return _cookie.getAttributes();
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
