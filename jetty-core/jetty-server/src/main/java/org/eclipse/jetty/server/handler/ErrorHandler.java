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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes.Type;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.QuotedQualityCSV;
import org.eclipse.jetty.io.ByteBufferOutputStream;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for Error pages
 * An ErrorHandler is registered with {@link Server#setErrorHandler(Request.Handler)}.
 * It is called by the {@link Response#writeError(Request, Response, Callback, int, String)}
 * to generate an error page.
 */
@ManagedObject
public class ErrorHandler implements Request.Handler
{
    private static final Logger LOG = LoggerFactory.getLogger(ErrorHandler.class);
    public static final String ERROR_STATUS = "org.eclipse.jetty.server.error_status";
    public static final String ERROR_MESSAGE = "org.eclipse.jetty.server.error_message";
    public static final String ERROR_EXCEPTION = "org.eclipse.jetty.server.error_exception";
    public static final String ERROR_CONTEXT = "org.eclipse.jetty.server.error_context";
    public static final Set<String> ERROR_METHODS = Set.of("GET", "POST", "HEAD");
    public static final HttpField ERROR_CACHE_CONTROL = new PreEncodedHttpField(HttpHeader.CACHE_CONTROL, "must-revalidate,no-cache,no-store");

    boolean _showStacks = false;
    boolean _showCauses = false;
    boolean _showMessageInTitle = true;
    int _bufferSize = -1;
    String _defaultResponseMimeType = Type.TEXT_HTML.asString();
    HttpField _cacheControl = new PreEncodedHttpField(HttpHeader.CACHE_CONTROL, "must-revalidate,no-cache,no-store");

    public ErrorHandler()
    {
    }

    public boolean errorPageForMethod(String method)
    {
        return ERROR_METHODS.contains(method);
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("handle({}, {}, {})", request, response, callback);
        if (_cacheControl != null)
            response.getHeaders().put(_cacheControl);

        int code = response.getStatus();
        String message = (String)request.getAttribute(ERROR_MESSAGE);
        Throwable cause = (Throwable)request.getAttribute(ERROR_EXCEPTION);
        if (cause instanceof HttpException httpException)
        {
            code = httpException.getCode();
            response.setStatus(code);
            if (message == null)
                message = httpException.getReason();
        }

        if (!errorPageForMethod(request.getMethod()) || HttpStatus.hasNoBody(code))
        {
            callback.succeeded();
        }
        else
        {
            if (message == null)
                message = cause == null ? HttpStatus.getMessage(code) : cause.toString();
            generateResponse(request, response, code, message, cause, callback);
        }
        return true;
    }

    protected void generateResponse(Request request, Response response, int code, String message, Throwable cause, Callback callback) throws IOException
    {
        List<String> acceptable = request.getHeaders().getQualityCSV(HttpHeader.ACCEPT, QuotedQualityCSV.MOST_SPECIFIC_MIME_ORDERING);
        if (acceptable.isEmpty())
        {
            if (request.getHeaders().contains(HttpHeader.ACCEPT))
            {
                callback.succeeded();
                return;
            }
            acceptable = Collections.singletonList(_defaultResponseMimeType);
        }
        List<Charset> charsets = request.getHeaders().getQualityCSV(HttpHeader.ACCEPT_CHARSET).stream()
            .map(s ->
            {
                try
                {
                    if ("*".equals(s))
                        return StandardCharsets.UTF_8;
                    return Charset.forName(s);
                }
                catch (Throwable t)
                {
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        if (charsets.isEmpty())
        {
            charsets = List.of(StandardCharsets.ISO_8859_1, StandardCharsets.UTF_8);
            if (request.getHeaders().contains(HttpHeader.ACCEPT_CHARSET))
            {
                callback.succeeded();
                return;
            }
        }

        for (String mimeType : acceptable)
        {
            if (generateAcceptableResponse(request, response, callback, mimeType, charsets, code, message, cause))
                return;
        }
        callback.succeeded();
    }

    protected boolean generateAcceptableResponse(Request request, Response response, Callback callback, String contentType, List<Charset> charsets, int code, String message, Throwable cause) throws IOException
    {
        Type type;
        Charset charset;
        switch (contentType)
        {
            case "text/html":
            case "text/*":
            case "*/*":
                type = Type.TEXT_HTML;
                charset = charsets.stream().findFirst().orElse(StandardCharsets.ISO_8859_1);
                break;

            case "text/json":
            case "application/json":
                if (charsets.contains(StandardCharsets.UTF_8))
                    charset = StandardCharsets.UTF_8;
                else if (charsets.contains(StandardCharsets.ISO_8859_1))
                    charset = StandardCharsets.ISO_8859_1;
                else
                    return false;
                type = Type.TEXT_JSON.is(contentType) ? Type.TEXT_JSON : Type.APPLICATION_JSON;
                break;

            case "text/plain":
                type = Type.TEXT_PLAIN;
                charset = charsets.stream().findFirst().orElse(StandardCharsets.ISO_8859_1);
                break;

            default:
                return false;
        }

        int bufferSize = getBufferSize() <= 0 ? computeBufferSize(request) : getBufferSize();
        ByteBufferPool byteBufferPool = request.getComponents().getByteBufferPool();
        RetainableByteBuffer buffer = byteBufferPool.acquire(bufferSize, false);

        try
        {
            // write into the response aggregate buffer and flush it asynchronously.
            // Looping to reduce size if buffer overflows
            boolean showStacks = isShowStacks();
            while (true)
            {
                try
                {
                    buffer.clear();
                    ByteBufferOutputStream out = new ByteBufferOutputStream(buffer.getByteBuffer());
                    PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, charset));

                    switch (type)
                    {
                        case TEXT_HTML -> writeErrorHtml(request, writer, charset, code, message, cause, showStacks);
                        case TEXT_JSON, APPLICATION_JSON -> writeErrorJson(request, writer, code, message, cause, showStacks);
                        case TEXT_PLAIN -> writeErrorPlain(request, writer, code, message, cause, showStacks);
                        default -> throw new IllegalStateException();
                    }

                    writer.flush();
                    break;
                }
                catch (BufferOverflowException e)
                {
                    if (showStacks)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Disable stacks for " + e);

                        showStacks = false;
                        continue;
                    }
                    if (LOG.isDebugEnabled())
                        LOG.warn("Error page too large: >{} {} {} {}", bufferSize, code, message, request, e);
                    else
                        LOG.warn("Error page too large: >{} {} {} {}", bufferSize, code, message, request);

                    break;
                }
            }

            if (!buffer.hasRemaining())
            {
                buffer.release();
                callback.succeeded();
                return true;
            }

            response.getHeaders().put(type.getContentTypeField(charset));
            response.write(true, buffer.getByteBuffer(), new WriteErrorCallback(callback, byteBufferPool, buffer));

            return true;
        }
        catch (Throwable x)
        {
            if (buffer != null)
                byteBufferPool.removeAndRelease(buffer);
            throw x;
        }
    }

    protected int computeBufferSize(Request request)
    {
        int bufferSize = request.getConnectionMetaData().getHttpConfiguration().getOutputBufferSize();
        bufferSize = Math.min(8192, bufferSize);
        return bufferSize;
    }

    protected void writeErrorHtml(Request request, Writer writer, Charset charset, int code, String message, Throwable cause, boolean showStacks) throws IOException
    {
        if (message == null)
            message = HttpStatus.getMessage(code);

        writer.write("<html>\n<head>\n");
        writeErrorHtmlMeta(request, writer, charset);
        writeErrorHtmlHead(request, writer, code, message);
        writer.write("</head>\n<body>\n");
        writeErrorHtmlBody(request, writer, code, message, cause, showStacks);
        writer.write("\n</body>\n</html>\n");
    }

    protected void writeErrorHtmlMeta(Request request, Writer writer, Charset charset) throws IOException
    {
        writer.write("<meta http-equiv=\"Content-Type\" content=\"text/html;charset=");
        writer.write(charset.name());
        writer.write("\"/>\n");
    }

    protected void writeErrorHtmlHead(Request request, Writer writer, int code, String message) throws IOException
    {
        writer.write("<title>Error ");
        String status = Integer.toString(code);
        writer.write(status);
        if (isShowMessageInTitle() && message != null && !message.equals(status))
        {
            writer.write(' ');
            writer.write(StringUtil.sanitizeXmlString(message));
        }
        writer.write("</title>\n");
    }

    protected void writeErrorHtmlBody(Request request, Writer writer, int code, String message, Throwable cause, boolean showStacks) throws IOException
    {
        String uri = request.getHttpURI().toString();

        writeErrorHtmlMessage(request, writer, code, message, cause, uri);
        if (showStacks)
            writeErrorHtmlStacks(request, writer);

        request.getConnectionMetaData().getHttpConfiguration()
            .writePoweredBy(writer, "<hr/>", "<hr/>\n");
    }

    protected void writeErrorHtmlMessage(Request request, Writer writer, int code, String message, Throwable cause, String uri) throws IOException
    {
        writer.write("<h2>HTTP ERROR ");
        String status = Integer.toString(code);
        writer.write(status);
        if (message != null && !message.equals(status))
        {
            writer.write(' ');
            writer.write(StringUtil.sanitizeXmlString(message));
        }
        writer.write("</h2>\n");
        writer.write("<table>\n");
        htmlRow(writer, "URI", uri);
        htmlRow(writer, "STATUS", status);
        htmlRow(writer, "MESSAGE", message);
        while (_showCauses && cause != null)
        {
            htmlRow(writer, "CAUSED BY", cause);
            cause = cause.getCause();
        }
        writer.write("</table>\n");
    }

    private void htmlRow(Writer writer, String tag, Object value) throws IOException
    {
        writer.write("<tr><th>");
        writer.write(tag);
        writer.write(":</th><td>");
        if (value == null)
            writer.write("-");
        else
            writer.write(StringUtil.sanitizeXmlString(value.toString()));
        writer.write("</td></tr>\n");
    }

    protected void writeErrorPlain(Request request, PrintWriter writer, int code, String message, Throwable cause, boolean showStacks)
    {
        writer.write("HTTP ERROR ");
        writer.write(Integer.toString(code));
        if (isShowMessageInTitle())
        {
            writer.write(' ');
            writer.write(StringUtil.sanitizeXmlString(message));
        }
        writer.write("\n");
        writer.printf("URI: %s%n", request.getHttpURI());
        writer.printf("STATUS: %s%n", code);
        writer.printf("MESSAGE: %s%n", message);
        while (_showCauses && cause != null)
        {
            writer.printf("CAUSED BY %s%n", cause);
            if (showStacks)
                cause.printStackTrace(writer);
            cause = cause.getCause();
        }
    }

    protected void writeErrorJson(Request request, PrintWriter writer, int code, String message, Throwable cause, boolean showStacks)
    {
        Map<String, String> json = new HashMap<>();

        json.put("url", request.getHttpURI().toString());
        json.put("status", Integer.toString(code));
        json.put("message", message);
        int c = 0;
        while (_showCauses && cause != null)
        {
            json.put("cause" + c++, cause.toString());
            cause = cause.getCause();
        }

        writer.append(json.entrySet().stream()
            .map(e -> HttpField.NAME_VALUE_TOKENIZER.quote(e.getKey()) + ":" + HttpField.NAME_VALUE_TOKENIZER.quote(StringUtil.sanitizeXmlString((e.getValue()))))
            .collect(Collectors.joining(",\n", "{\n", "\n}")));
    }

    protected void writeErrorHtmlStacks(Request request, Writer writer) throws IOException
    {
        Throwable th = (Throwable)request.getAttribute(ERROR_EXCEPTION);
        if (th != null)
        {
            writer.write("<h3>Caused by:</h3><pre>");
            // You have to pre-generate and then use #write(writer, String)
            try (StringWriter sw = new StringWriter();
                 PrintWriter pw = new PrintWriter(sw))
            {
                th.printStackTrace(pw);
                pw.flush();
                write(writer, sw.getBuffer().toString()); // sanitize
            }
            writer.write("</pre>\n");
        }
    }

    /**
     * Bad Message Error body
     * <p>Generate an error response body to be sent for a bad message.
     * In this case there is something wrong with the request, so either
     * a request cannot be built, or it is not safe to build a request.
     * This method allows for a simple error page body to be returned
     * and some response headers to be set.
     *
     * @param status The error code that will be sent
     * @param reason The reason for the error code (may be null)
     * @param fields The header fields that will be sent with the response.
     * @return The content as a ByteBuffer, or null for no body.
     * @deprecated Do not override. No longer invoked by Jetty.
     */
    @Deprecated(since = "12.0.8", forRemoval = true)
    public ByteBuffer badMessageError(int status, String reason, HttpFields.Mutable fields)
    {
        return null;
    }

    /**
     * Get the cacheControl.
     *
     * @return the cacheControl header to set on error responses.
     */
    @ManagedAttribute("The value of the Cache-Control response header")
    public String getCacheControl()
    {
        return _cacheControl == null ? null : _cacheControl.getValue();
    }

    /**
     * Set the cacheControl.
     *
     * @param cacheControl the cacheControl header to set on error responses.
     */
    public void setCacheControl(String cacheControl)
    {
        _cacheControl = cacheControl == null ? null : new PreEncodedHttpField(HttpHeader.CACHE_CONTROL, cacheControl);
    }

    /**
     * @return True if stack traces are shown in the error pages
     */
    @ManagedAttribute("Whether the error page shows the stack trace")
    public boolean isShowStacks()
    {
        return _showStacks;
    }

    /**
     * @param showStacks True if stack traces are shown in the error pages
     */
    public void setShowStacks(boolean showStacks)
    {
        _showStacks = showStacks;
    }

    /**
     * @return True if exception causes are shown in the error pages
     */
    @ManagedAttribute("Whether the error page shows the exception causes")
    public boolean isShowCauses()
    {
        return _showCauses;
    }

    /**
     * @param showCauses True if exception causes are shown in the error pages
     */
    public void setShowCauses(boolean showCauses)
    {
        _showCauses = showCauses;
    }

    @ManagedAttribute("Whether the error message is shown in the error page title")
    public boolean isShowMessageInTitle()
    {
        return _showMessageInTitle;
    }

    /**
     * Set if true, the error message appears in page title.
     * @param showMessageInTitle if true, the error message appears in page title
     */
    public void setShowMessageInTitle(boolean showMessageInTitle)
    {
        _showMessageInTitle = showMessageInTitle;
    }

    /**
     * @return The mime type to be used when a client does not specify an Accept header, or the request did not fully parse
     */
    @ManagedAttribute("Mime type to be used when a client does not specify an Accept header, or the request did not fully parse")
    public String getDefaultResponseMimeType()
    {
        return _defaultResponseMimeType;
    }

    /**
     * @param defaultResponseMimeType The mime type to be used when a client does not specify an Accept header, or the request did not fully parse
     */
    public void setDefaultResponseMimeType(String defaultResponseMimeType)
    {
        _defaultResponseMimeType = Objects.requireNonNull(defaultResponseMimeType);
    }

    protected void write(Writer writer, String string) throws IOException
    {
        if (string == null)
            return;

        writer.write(StringUtil.sanitizeXmlString(string));
    }

    public static Request.Handler getErrorHandler(Server server, ContextHandler context)
    {
        Request.Handler errorHandler = null;
        if (context != null)
            errorHandler = context.getErrorHandler();
        if (errorHandler == null && server != null)
            errorHandler = server.getErrorHandler();
        return errorHandler;
    }

    /**
     * @return Buffer size for entire error response. If error page is bigger than buffer size, it will be truncated.
     * With a -1 meaning that a heuristic will be used (e.g. min(8K, httpConfig.bufferSize))
     */
    @ManagedAttribute("Buffer size for entire error response")
    public int getBufferSize()
    {
        return _bufferSize;
    }

    /**
     * @param bufferSize Buffer size for entire error response. If error page is bigger than buffer size, it will be truncated.
     * With a -1 meaning that a heuristic will be used (e.g. min(8K, httpConfig.bufferSize))
     */
    public void setBufferSize(int bufferSize)
    {
        this._bufferSize = bufferSize;
    }

    public static class ErrorRequest extends Request.AttributesWrapper
    {
        private static final Set<String> ATTRIBUTES = Set.of(ERROR_MESSAGE, ERROR_EXCEPTION, ERROR_STATUS);

        public ErrorRequest(Request request, int status, String message, Throwable cause)
        {
            super(request, new Attributes.Synthetic(request)
            {
                @Override
                protected Object getSyntheticAttribute(String name)
                {
                    return switch (name)
                    {
                        case ERROR_MESSAGE -> message;
                        case ERROR_EXCEPTION -> cause;
                        case ERROR_STATUS -> status;
                        default -> null;
                    };
                }

                @Override
                protected Set<String> getSyntheticNameSet()
                {
                    return ATTRIBUTES;
                }
            });
        }

        @Override
        public Content.Chunk read()
        {
            return Content.Chunk.EOF;
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            demandCallback.run();
        }

        @Override
        public String toString()
        {
            return "%s@%x:%s".formatted(getClass().getSimpleName(), hashCode(), getWrapped());
        }
    }

    /**
     * The callback used by
     * {@link ErrorHandler#generateAcceptableResponse(Request, Response, Callback, String, List, int, String, Throwable)}
     * when calling {@link Response#write(boolean, ByteBuffer, Callback)} to wrap the passed in {@link Callback}
     * so that the {@link RetainableByteBuffer} used can be released.
     */
    private static class WriteErrorCallback implements Callback
    {
        private final AtomicReference<Callback>  _callback;
        private final ByteBufferPool _pool;
        private final RetainableByteBuffer _buffer;

        public WriteErrorCallback(Callback callback, ByteBufferPool pool, RetainableByteBuffer retainable)
        {
            _callback = new AtomicReference<>(callback);
            _pool = pool;
            _buffer = retainable;
        }

        @Override
        public void succeeded()
        {
            Callback callback = _callback.getAndSet(null);
            if (callback != null)
                ExceptionUtil.callAndThen(_buffer::release, callback::succeeded);
        }

        @Override
        public void failed(Throwable x)
        {
            Callback callback = _callback.getAndSet(null);
            if (callback != null)
                ExceptionUtil.callAndThen(x, t -> _pool.removeAndRelease(_buffer), callback::failed);
        }
    }
}
