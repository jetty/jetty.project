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
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.ServletResponseWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
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
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

/**
 * A core response wrapper that carries the servlet related response state,
 * which may be used directly by the associated {@link ServletApiResponse}.
 * Non servlet related state, is used indirectly via {@link ServletChannel#getResponse()}
 * which may be a wrapper of this response.
 */
public class ServletContextResponse extends ContextResponse implements ServletContextHandler.ServletResponseInfo
{
    protected enum OutputType
    {
        NONE, STREAM, WRITER
    }

    private final ServletChannel _servletChannel;
    private final ServletApiResponse _servletApiResponse;
    private final HttpFields.Mutable.Wrapper _headers;
    private String _characterEncoding;
    private String _contentType;
    private MimeTypes.Type _mimeType;
    private Locale _locale;
    private EncodingFrom _encodingFrom = EncodingFrom.NOT_SET;
    private OutputType _outputType = OutputType.NONE;
    private ResponseWriter _writer;
    private long _contentLength = -1;
    private Supplier<Map<String, String>> _trailers;
    private long _written;

    public static ServletContextResponse getServletContextResponse(ServletResponse response)
    {
        if (response instanceof ServletApiResponse servletApiResponse)
            return servletApiResponse.getServletRequestInfo().getServletChannel().getServletContextResponse();

        while (response instanceof ServletResponseWrapper)
        {
            response = ((ServletResponseWrapper)response).getResponse();
            if (response instanceof ServletApiResponse servletApiResponse)
                return servletApiResponse.getServletRequestInfo().getServletChannel().getServletContextResponse();
        }

        throw new IllegalStateException("could not find %s for %s".formatted(ServletContextResponse.class.getSimpleName(), response));
    }

    public ServletContextResponse(ServletChannel servletChannel, ServletContextRequest request, Response response)
    {
        super(servletChannel.getContext(), request, response);
        _servletChannel = servletChannel;
        _servletApiResponse = newServletApiResponse();
        _headers = new HttpFieldsWrapper(response.getHeaders());
    }

    @Override
    public Response getResponse()
    {
        return _servletChannel.getResponse();
    }

    @Override
    public ResponseWriter getWriter()
    {
        return _writer;
    }

    @Override
    public void setWriter(ResponseWriter writer)
    {
        _writer = writer;
    }

    @Override
    public Locale getLocale()
    {
        return _locale;
    }

    @Override
    public void setLocale(Locale locale)
    {
        _locale = locale;
    }

    @Override
    public EncodingFrom getEncodingFrom()
    {
        return _encodingFrom;
    }

    protected MimeTypes.Type getMimeType()
    {
        return _mimeType;
    }

    @Override
    public Supplier<Map<String, String>> getTrailers()
    {
        return _trailers;
    }

    @Override
    public void setTrailers(Supplier<Map<String, String>> trailers)
    {
        this._trailers = trailers;
    }

    @Override
    public String getCharacterEncoding()
    {
        return _characterEncoding;
    }

    @Override
    public void setOutputType(OutputType outputType)
    {
        _outputType = outputType;
    }

    @Override
    public String getContentType()
    {
        return _contentType;
    }

    @Override
    public OutputType getOutputType()
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
        return _servletChannel.getHttpOutput();
    }

    public ServletChannelState getServletRequestState()
    {
        return _servletChannel.getServletRequestState();
    }

    public ServletApiResponse getServletApiResponse()
    {
        return _servletApiResponse;
    }

    public void resetForForward()
    {
        _servletApiResponse.resetBuffer();
        _outputType = OutputType.NONE;
    }

    public void included()
    {
        if (_outputType == OutputType.WRITER)
            _writer.reopen();
        getHttpOutput().reopen();
    }

    public void completeOutput(Callback callback)
    {
        if (_outputType == OutputType.WRITER)
            _writer.markAsClosed();
        getHttpOutput().complete(callback);
    }

    public boolean isAllContentWritten(long written)
    {
        return (_contentLength >= 0 && written >= _contentLength);
    }

    public boolean isContentIncomplete(long written)
    {
        return (_contentLength >= 0 && written < _contentLength);
    }

    public void setContentLength(int len)
    {
        setContentLength((long)len);
    }

    @Override
    public HttpFields.Mutable getHeaders()
    {
        return _headers;
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
            long written = getHttpOutput().getWritten();
            if (written > len)
                throw new IllegalArgumentException("setContentLength(" + len + ") when already written " + written);

            _contentLength = len;
            getHeaders().put(HttpHeader.CONTENT_LENGTH, len);
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
            long written = getHttpOutput().getWritten();
            if (written > 0)
                throw new IllegalArgumentException("setContentLength(0) when already written " + written);
            _contentLength = len;
            getHeaders().put(HttpFields.CONTENT_LENGTH_0);
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

    @Override
    public void write(boolean last, ByteBuffer content, Callback callback)
    {
        _written += BufferUtil.length(content);
        super.write(last, content, callback);
    }

    /**
     * <p>Returns the number of bytes written via this class {@link #write(boolean, ByteBuffer, Callback)} method.</p>
     * <p>The number of bytes written to the network may be different.</p>
     *
     * @return the number of bytes written via this class {@link #write(boolean, ByteBuffer, Callback)} method.
     */
    long getContentBytesWritten()
    {
        return _written;
    }

    public void closeOutput() throws IOException
    {
        if (_outputType == OutputType.WRITER)
            _writer.close();
        else
            getHttpOutput().close();
    }

    @Override
    public void reset()
    {
        super.reset();

        _servletApiResponse.resetBuffer();
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
            SessionHandler sh = _servletChannel.getServletContextHandler().getSessionHandler();
            if (sh != null)
            {
                ManagedSession managedSession = SessionHandler.ServletSessionApi.getSession(session);
                if (managedSession != null)
                {
                    HttpCookie c = sh.getSessionCookie(managedSession, getRequest().isSecure());
                    if (c != null)
                        Response.putCookie(getWrapped(), c);
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
        if (isCommitted())
            throw new IllegalStateException("Committed");
        getHttpOutput().resetBuffer();
        _outputType = OutputType.NONE;
        _contentLength = -1;
        _contentType = null;
        _mimeType = null;
        _characterEncoding = null;
        _encodingFrom = EncodingFrom.NOT_SET;

        // remove the content related response headers and keep all others
        getHeaders().remove(getStatus() == HttpStatus.NOT_MODIFIED_304 ? HttpHeader.CONTENT_HEADERS_304 : HttpHeader.CONTENT_HEADERS);
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

    @Override
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
        ServletContext context = _servletChannel.getServletContextRequest().getServletContext().getServletContext();
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
        encoding = MimeTypes.ISO_8859_1;
        if (setContentType)
            setCharacterEncoding(encoding, EncodingFrom.DEFAULT);
        return encoding;
    }

    /**
     * Update the Content-Type, MimeType, and headers from the provided Character Encoding and
     * EncodingFrom.
     * @param encoding the character encoding
     * @param from where encoding came from
     */
    @Override
    public void setCharacterEncoding(String encoding, EncodingFrom from)
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
            _characterEncoding = HttpGenerator.__STRICT ? encoding : MimeTypes.normalizeCharset(encoding);
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

    @Override
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

    /**
     * Wrapper of the response HttpFields to allow specific values to be intercepted.
     */
    private class HttpFieldsWrapper extends HttpFields.Mutable.Wrapper
    {
        public HttpFieldsWrapper(Mutable fields)
        {
            super(fields);
        }

        @Override
        public HttpField onAddField(HttpField field)
        {
            if (isCommitted())
                return null;

            if (field.getHeader() == null)
                return super.onAddField(field);

            return switch (field.getHeader())
            {
                case CONTENT_LENGTH -> setContentLength(field);
                case CONTENT_TYPE -> setContentType(field);
                default -> super.onAddField(field);
            };
        }

        @Override
        public boolean onRemoveField(HttpField field)
        {
            if (isCommitted())
                return false;
            if (field.getHeader() == null)
                return true;
            switch (field.getHeader())
            {
                case CONTENT_LENGTH -> _contentLength = -1;
                case CONTENT_TYPE ->
                {
                    _contentType = null;
                    _mimeType = null;
                    if (!isWriting())
                    {
                        _characterEncoding = switch (_encodingFrom)
                        {
                            case SET_CHARACTER_ENCODING, SET_LOCALE -> _characterEncoding;
                            default ->
                            {
                                _encodingFrom = EncodingFrom.NOT_SET;
                                yield null;
                            }
                        };
                    }
                }
            }

            return true;
        }

        @Override
        public HttpField onReplaceField(HttpField oldField, HttpField newField)
        {
            assert oldField != null && newField != null;

            if (isCommitted())
                return oldField;

            if (newField.getHeader() == null)
                return newField;

            return switch (newField.getHeader())
            {
                case CONTENT_LENGTH -> setContentLength(newField);
                case CONTENT_TYPE -> setContentType(newField);
                default -> newField;
            };
        }

        private HttpField setContentLength(HttpField field)
        {
            long len = field.getLongValue();
            long written = _servletChannel.getHttpOutput().getWritten();

            if (len > 0 && written > len)
                throw new IllegalArgumentException("setContentLength(" + len + ") when already written " + written);
            if (len == 0 && written > 0)
                throw new IllegalArgumentException("setContentLength(0) when already written " + written);

            _contentLength = len;

            if (len > 0 && isAllContentWritten(written))
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

            return field;
        }

        private HttpField setContentType(HttpField field)
        {
            _contentType = field.getValue();
            _mimeType = MimeTypes.CACHE.get(_contentType);

            String charset = MimeTypes.getCharsetFromContentType(_contentType);
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
                        _contentType = _contentType + ";charset=" + _characterEncoding;
                        _mimeType = MimeTypes.CACHE.get(_contentType);
                        field = new HttpField(HttpHeader.CONTENT_TYPE, _contentType);
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
                if (_characterEncoding != null && (_mimeType == null || !_mimeType.isCharsetAssumed()))
                    _contentType = _contentType + ";charset=" + _characterEncoding;
                _mimeType = MimeTypes.CACHE.get(_contentType);
                field = new HttpField(HttpHeader.CONTENT_TYPE, _contentType);
            }
            else
            {
                _characterEncoding = charset;
                _encodingFrom = ServletContextResponse.EncodingFrom.SET_CONTENT_TYPE;
            }

            if (HttpGenerator.__STRICT || _mimeType == null)
                return field;

            _contentType = _mimeType.asString();
            return _mimeType.getContentTypeField();
        }
    }
}
