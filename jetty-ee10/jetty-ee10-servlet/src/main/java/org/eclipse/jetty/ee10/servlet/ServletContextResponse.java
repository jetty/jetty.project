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
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.ServletResponseWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.ee10.servlet.writer.ResponseWriter;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextResponse;
import org.eclipse.jetty.session.ManagedSession;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;

public class ServletContextResponse extends ContextResponse
{
    protected enum OutputType
    {
        NONE, STREAM, WRITER
    }

    private final HttpOutput _httpOutput;
    private final ServletChannel _servletChannel;
    private final ServletApiResponse _httpServletResponse;
    private String _characterEncoding;
    private String _contentType;
    private MimeTypes.Type _mimeType;
    private Locale _locale;
    private EncodingFrom _encodingFrom = EncodingFrom.NOT_SET;
    private OutputType _outputType = OutputType.NONE;
    private ResponseWriter _writer;
    private long _contentLength = -1;
    private Supplier<Map<String, String>> _trailers;

    public static ServletContextResponse getServletContextResponse(ServletResponse response)
    {
        if (response instanceof ServletApiResponse)
            return ((ServletApiResponse)response).getResponse();

        while (response instanceof ServletResponseWrapper)
        {
            response = ((ServletResponseWrapper)response).getResponse();
        }

        if (response instanceof ServletApiResponse)
            return ((ServletApiResponse)response).getResponse();

        throw new IllegalStateException("could not find %s for %s".formatted(ServletContextResponse.class.getSimpleName(), response));
    }

    public ServletContextResponse(ServletChannel servletChannel, ServletContextRequest request, Response response)
    {
        super(servletChannel.getContext(), request, response);
        _httpOutput = new HttpOutput(response, servletChannel);
        _servletChannel = servletChannel;
        _httpServletResponse = newServletApiResponse();
    }

    protected ResponseWriter getWriter()
    {
        return _writer;
    }

    protected void setWriter(ResponseWriter writer)
    {
        _writer = writer;
    }

    protected Locale getLocale()
    {
        return _locale;
    }

    protected void setLocale(Locale locale)
    {
        _locale = locale;
    }

    protected EncodingFrom getEncodingFrom()
    {
        return _encodingFrom;
    }

    protected MimeTypes.Type getMimeType()
    {
        return _mimeType;
    }

    protected void setMimeType(MimeTypes.Type mimeType)
    {
        this._mimeType = mimeType;
    }

    protected Supplier<Map<String, String>> getTrailers()
    {
        return _trailers;
    }

    public void setTrailers(Supplier<Map<String, String>> trailers)
    {
        this._trailers = trailers;
    }

    protected void setContentType(String contentType)
    {
        this._contentType = contentType;
    }

    protected String getCharacterEncoding()
    {
        return _characterEncoding;
    }

    protected void setCharacterEncoding(String value)
    {
        _characterEncoding = value;
    }

    protected void setOutputType(OutputType outputType)
    {
        _outputType = outputType;
    }

    protected String getContentType()
    {
        return _contentType;
    }

    protected OutputType getOutputType()
    {
        return _outputType;
    }

    protected ServletContextRequest getServletContextRequest()
    {
        return (ServletContextRequest)getRequest();
    }

    protected ServletApiResponse newServletApiResponse()
    {
        return new ServletApiResponse(this);
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
        _httpServletResponse.resetBuffer();
        _outputType = OutputType.NONE;
    }

    public void included()
    {
        if (_outputType == OutputType.WRITER)
            _writer.reopen();
        _httpOutput.reopen();
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
        setContentLength((long)len);
    }

    public void setContentLength(long len)
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

        _httpServletResponse.resetBuffer();
        _outputType = OutputType.NONE;
        _contentLength = -1;
        _contentType = null;
        _mimeType = null;
        _characterEncoding = null;
        _encodingFrom = EncodingFrom.NOT_SET;
        _trailers = null;

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
                    case CLOSE -> headers.put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.toString());
                    case KEEP_ALIVE ->
                    {
                        if (HttpVersion.HTTP_1_0.is(getRequest().getConnectionMetaData().getProtocol()))
                            headers.put(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.toString());
                    }
                    case TE -> headers.put(HttpHeader.CONNECTION, HttpHeaderValue.TE.toString());
                    default ->
                    {
                    }
                }
            }
        }

        // recreate session cookies
        HttpSession session = getServletContextRequest().getServletApiRequest().getSession(false);
        if (session != null && session.isNew())
        {
            SessionHandler sh = _servletChannel.getContextHandler().getSessionHandler();
            if (sh != null)
            {
                ManagedSession managedSession = SessionHandler.ServletSessionApi.getSession(session);
                if (managedSession != null)
                {
                    HttpCookie c = sh.getSessionCookie(managedSession, getRequest().isSecure());
                    if (c != null)
                        Response.addCookie(getWrapped(), c);
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
                case CONTENT_TYPE, CONTENT_LENGTH, CONTENT_ENCODING, CONTENT_LANGUAGE, CONTENT_RANGE, CONTENT_MD5,
                    CONTENT_LOCATION, TRANSFER_ENCODING, CACHE_CONTROL, LAST_MODIFIED, EXPIRES, VARY -> i.remove();
                case ETAG ->
                {
                    if (getStatus() != HttpStatus.NOT_MODIFIED_304)
                        i.remove();
                }
                default ->
                {
                }
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
        MimeTypes mimeTypes = getRequest().getContext().getMimeTypes();
        encoding = mimeTypes.getCharsetAssumedFromContentType(_contentType);
        if (encoding != null)
            return encoding;

        // Try char set inferred from content type.
        encoding = mimeTypes.getCharsetInferredFromContentType(_contentType);
        if (encoding != null)
        {
            if (setContentType)
                setCharacterEncoding(encoding, EncodingFrom.INFERRED);
            return encoding;
        }

        // Try any default char encoding for the context.
        ServletContext context = _servletChannel.getServletContextRequest().getContext().getServletContext();
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

    /**
     * Set the Character Encoding and EncodingFrom in the raw, with no manipulation
     * of the ContentType value, MimeType value, or headers.
     *
     * @param encoding the character encoding
     * @param from where encoding came from
     */
    protected void setRawCharacterEncoding(String encoding, EncodingFrom from)
    {
        _characterEncoding = encoding;
        _encodingFrom = from;
    }

    /**
     * Update the Content-Type, MimeType, and headers from the provided Character Encoding and
     * EncodingFrom.
     * @param encoding the character encoding
     * @param from where encoding came from
     */
    protected void setCharacterEncoding(String encoding, EncodingFrom from)
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
                    getWrapped().getHeaders().put(_mimeType.getContentTypeField());
                }
                else if (_contentType != null)
                {
                    _contentType = MimeTypes.getContentTypeWithoutCharset(_contentType);
                    getWrapped().getHeaders().put(HttpHeader.CONTENT_TYPE, _contentType);
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
                    getWrapped().getHeaders().put(HttpHeader.CONTENT_TYPE, _contentType);
                else
                    getWrapped().getHeaders().put(_mimeType.getContentTypeField());
            }
            else if (_contentType != null)
            {
                _contentType = MimeTypes.getContentTypeWithoutCharset(_contentType) + ";charset=" + _characterEncoding;
                getWrapped().getHeaders().put(HttpHeader.CONTENT_TYPE, _contentType);
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

    protected enum EncodingFrom
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
         * The character encoding has been explicitly set using the Content-Type charset parameter with {@link HttpServletResponse#setContentType(String)}.
         */
        SET_CONTENT_TYPE,

        /**
         * The character encoding has been explicitly set using {@link HttpServletResponse#setCharacterEncoding(String)}.
         */
        SET_CHARACTER_ENCODING
    }
}
