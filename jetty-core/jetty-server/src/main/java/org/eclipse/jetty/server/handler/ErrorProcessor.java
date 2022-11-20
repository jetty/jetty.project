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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes.Type;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.QuotedQualityCSV;
import org.eclipse.jetty.io.ByteBufferOutputStream;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for Error pages
 * An ErrorHandler is registered with {@link Server#setErrorProcessor(Request.Processor)}.
 * It is called by the {@link Response#writeError(Request, Response, Callback, int, String)}
 * to generate an error page.
 */
public class ErrorProcessor implements Request.Processor
{
    // TODO This classes API needs to be majorly refactored/cleanup in jetty-10
    private static final Logger LOG = LoggerFactory.getLogger(ErrorProcessor.class);
    public static final String ERROR_STATUS = "org.eclipse.jetty.server.error_status";
    public static final String ERROR_MESSAGE = "org.eclipse.jetty.server.error_message";
    public static final String ERROR_EXCEPTION = "org.eclipse.jetty.server.error_exception";
    public static final String ERROR_CONTEXT = "org.eclipse.jetty.server.error_context";
    public static final Set<String> ERROR_METHODS = Set.of("GET", "POST", "HEAD");
    public static final HttpField ERROR_CACHE_CONTROL = new PreEncodedHttpField(HttpHeader.CACHE_CONTROL, "must-revalidate,no-cache,no-store");

    boolean _showServlet = true;
    boolean _showStacks = true;
    boolean _showMessageInTitle = true;
    HttpField _cacheControl = new PreEncodedHttpField(HttpHeader.CACHE_CONTROL, "must-revalidate,no-cache,no-store");

    public ErrorProcessor()
    {
    }

    public boolean errorPageForMethod(String method)
    {
        return ERROR_METHODS.contains(method);
    }

    @Override
    public void process(Request request, Response response, Callback callback)
    {
        if (_cacheControl != null)
            response.getHeaders().put(_cacheControl);

        int code = response.getStatus();
        String message = (String)request.getAttribute(ERROR_MESSAGE);
        Throwable cause = (Throwable)request.getAttribute(ERROR_EXCEPTION);
        if (cause instanceof BadMessageException bad)
        {
            code = bad.getCode();
            response.setStatus(code);
            if (message == null)
                message = bad.getReason();
        }

        if (!errorPageForMethod(request.getMethod()) || HttpStatus.hasNoBody(code))
        {
            callback.succeeded();
        }
        else
        {
            if (message == null)
                message = cause == null ? HttpStatus.getMessage(code) : cause.toString();

            try
            {
                generateResponse(request, response, code, message, cause, callback);
            }
            catch (Throwable x)
            {
                // TODO: cannot write the error response, give up and close the stream.
                x.printStackTrace();
            }
        }
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
            acceptable = Collections.singletonList(Type.TEXT_HTML.asString());
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

        int bufferSize = request.getConnectionMetaData().getHttpConfiguration().getOutputBufferSize();
        bufferSize = Math.min(8192, bufferSize); // TODO ?
        ByteBuffer buffer = request.getComponents().getByteBufferPool().acquire(bufferSize, false);

        // write into the response aggregate buffer and flush it asynchronously.
        // Looping to reduce size if buffer overflows
        boolean showStacks = _showStacks;
        while (true)
        {
            try
            {
                BufferUtil.clear(buffer);
                ByteBufferOutputStream out = new ByteBufferOutputStream(buffer);
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, charset));

                switch (type)
                {
                    case TEXT_HTML -> writeErrorHtml(request, writer, charset, code, message, cause, showStacks);
                    case TEXT_JSON -> writeErrorJson(request, writer, code, message, cause, showStacks);
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
                        LOG.debug("Disable stacks for " + e.toString());

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
            callback.succeeded();
            return true;
        }

        response.getHeaders().put(type.getContentTypeField(charset));
        response.write(true, buffer, new Callback.Nested(callback)
        {
            @Override
            public void succeeded()
            {
                request.getComponents().getByteBufferPool().release(buffer);
                super.succeeded();
            }

            @Override
            public void failed(Throwable x)
            {
                request.getComponents().getByteBufferPool().release(buffer);
                super.failed(x);
            }
        });

        return true;
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
        if (message != null && !message.equals(status))
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
        while (cause != null)
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
        writer.write(' ');
        writer.write(StringUtil.sanitizeXmlString(message));
        writer.write("\n");
        writer.printf("URI: %s%n", request.getHttpURI());
        writer.printf("STATUS: %s%n", code);
        writer.printf("MESSAGE: %s%n", message);
        while (cause != null)
        {
            writer.printf("CAUSED BY %s%n", cause);
            if (showStacks)
                cause.printStackTrace(writer);
            cause = cause.getCause();
        }
    }

    private void writeErrorJson(Request request, PrintWriter writer, int code, String message, Throwable cause, boolean showStacks)
    {
        Map<String, String> json = new HashMap<>();

        json.put("url", request.getHttpURI().toString());
        json.put("status", Integer.toString(code));
        json.put("message", message);
        int c = 0;
        while (cause != null)
        {
            json.put("cause" + c++, cause.toString());
            cause = cause.getCause();
        }

        writer.append(json.entrySet().stream()
            .map(e -> QuotedStringTokenizer.quote(e.getKey()) +
                ":" +
                QuotedStringTokenizer.quote(StringUtil.sanitizeXmlString((e.getValue()))))
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
     */
    public ByteBuffer badMessageError(int status, String reason, HttpFields.Mutable fields)
    {
        if (reason == null)
            reason = HttpStatus.getMessage(status);
        if (HttpStatus.hasNoBody(status))
            return BufferUtil.EMPTY_BUFFER;
        fields.put(HttpHeader.CONTENT_TYPE, Type.TEXT_HTML_8859_1.asString());
        return BufferUtil.toBuffer("<h1>Bad Message " + status + "</h1><pre>reason: " + reason + "</pre>");
    }

    /**
     * Get the cacheControl.
     *
     * @return the cacheControl header to set on error responses.
     */
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
     * @return True if the error page will show the Servlet that generated the error
     */
    public boolean isShowServlet()
    {
        return _showServlet;
    }

    /**
     * @param showServlet True if the error page will show the Servlet that generated the error
     */
    public void setShowServlet(boolean showServlet)
    {
        _showServlet = showServlet;
    }

    /**
     * @return True if stack traces are shown in the error pages
     */
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
     * @param showMessageInTitle if true, the error message appears in page title
     */
    public void setShowMessageInTitle(boolean showMessageInTitle)
    {
        _showMessageInTitle = showMessageInTitle;
    }

    public boolean getShowMessageInTitle()
    {
        return _showMessageInTitle;
    }

    protected void write(Writer writer, String string) throws IOException
    {
        if (string == null)
            return;

        writer.write(StringUtil.sanitizeXmlString(string));
    }

    public static Request.Processor getErrorProcessor(Server server, ContextHandler context)
    {
        Request.Processor errorProcessor = null;
        if (context != null)
            errorProcessor = context.getErrorProcessor();
        if (errorProcessor == null && server != null)
            errorProcessor = server.getErrorProcessor();
        return errorProcessor;
    }

    public static class ErrorRequest extends Request.Wrapper
    {
        private final int _status;
        private final String _message;
        private final Throwable _cause;

        public ErrorRequest(Request request, int status, String message, Throwable cause)
        {
            super(request);
            _status = status;
            _message = message;
            _cause = cause;
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
        public Object getAttribute(String name)
        {
            return switch (name)
            {
                case ERROR_MESSAGE -> _message;
                case ERROR_EXCEPTION -> _cause;
                case ERROR_STATUS -> _status;
                default -> super.getAttribute(name);
            };
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            Set<String> names = new HashSet<>(super.getAttributeNameSet());
            if (_message != null)
                names.add(ERROR_MESSAGE);
            if (_status > 0)
                names.add(ERROR_STATUS);
            if (_cause != null)
                names.add(ERROR_EXCEPTION);
            return names;
        }
    }
}
