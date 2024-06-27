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

package org.eclipse.jetty.ee11.servlet;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.QuotedQualityCSV;
import org.eclipse.jetty.io.ByteBufferOutputStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.server.handler.ErrorHandler.ERROR_MESSAGE;
import static org.eclipse.jetty.server.handler.ErrorHandler.ERROR_EXCEPTION;

public class ErrorHandler extends org.eclipse.jetty.server.handler.ErrorHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(ErrorHandler.class);
    public static final String ERROR_CHARSET = "org.eclipse.jetty.server.error_charset";

    public ErrorHandler()
    {
        setShowOrigin(true);
        setShowStacks(true);
        setShowMessageInTitle(true);
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        if (!errorPageForMethod(request.getMethod()))
        {
            callback.succeeded();
            return true;
        }

        generateCacheControl(response);

        ServletContextRequest servletContextRequest = Request.as(request, ServletContextRequest.class);
        HttpServletRequest httpServletRequest = servletContextRequest.getServletApiRequest();
        HttpServletResponse httpServletResponse = servletContextRequest.getHttpServletResponse();
        ServletContextHandler contextHandler = servletContextRequest.getServletContext().getServletContextHandler();

        // Look for an error page dispatcher
        // This logic really should be in ErrorPageErrorHandler, but some implementations extend ErrorHandler
        // and implement ErrorPageMapper directly, so we do this here in the base class.
        String errorPage = (this instanceof ErrorPageMapper) ? ((ErrorPageMapper)this).getErrorPage(httpServletRequest) : null;
        ServletContextHandler.ServletScopedContext context = servletContextRequest.getErrorContext();
        Dispatcher errorDispatcher = (errorPage != null && context != null)
            ? (Dispatcher)context.getServletContext().getRequestDispatcher(errorPage) : null;

        if (errorDispatcher != null)
        {
            try
            {
                try
                {
                    contextHandler.requestInitialized(servletContextRequest, httpServletRequest);
                    errorDispatcher.error(httpServletRequest, httpServletResponse);
                }
                finally
                {
                    contextHandler.requestDestroyed(servletContextRequest, httpServletRequest);
                }
                callback.succeeded();
                return true;
            }
            catch (ServletException e)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Unable to call error dispatcher", e);
                if (response.isCommitted())
                {
                    callback.failed(e);
                    return true;
                }
            }
        }

        //TODO call core ErrorHandler methods from here on to generate the default error page. Can we even use the methods that pass in the callback??
        String message = (String)request.getAttribute(ERROR_MESSAGE);
        if (message == null)
            message = HttpStatus.getMessage(response.getStatus());
        generateAcceptableResponse(servletContextRequest, httpServletRequest, httpServletResponse, response.getStatus(), message);
        callback.succeeded();
        return true;
    }

    /**
     * Generate an acceptable error response.
     * <p>This method is called to generate an Error page of a mime type that is
     * acceptable to the user-agent.  The Accept header is evaluated in
     * quality order and the method
     * {@link #generateAcceptableResponse(ServletContextRequest, HttpServletRequest, HttpServletResponse, int, String, String)}
     * is called for each mimetype until the response is written to or committed.</p>
     *
     * @param baseRequest The base request
     * @param request The servlet request (may be wrapped)
     * @param response The response (may be wrapped)
     * @param code the http error code
     * @param message the http error message
     * @throws IOException if the response cannot be generated
     */
    protected void generateAcceptableResponse(ServletContextRequest baseRequest, HttpServletRequest request, HttpServletResponse response, int code, String message) throws IOException
    {
        List<String> acceptable = baseRequest.getHeaders().getQualityCSV(HttpHeader.ACCEPT, QuotedQualityCSV.MOST_SPECIFIC_MIME_ORDERING);

        if (acceptable.isEmpty() && !baseRequest.getHeaders().contains(HttpHeader.ACCEPT))
        {
            generateAcceptableResponse(baseRequest, request, response, code, message, MimeTypes.Type.TEXT_HTML.asString());
        }
        else
        {
            for (String mimeType : acceptable)
            {
                generateAcceptableResponse(baseRequest, request, response, code, message, mimeType);
                if (response.isCommitted() || baseRequest.getServletContextResponse().isWritingOrStreaming())
                    break;
            }
        }
    }

    /**
     * Generate an acceptable error response for a mime type.
     * <p>This method is called for each mime type in the users agent's
     * <code>Accept</code> header, a response of the appropriate type is generated.
     * </p>
     * <p>The default implementation handles "text/html", "text/*" and "*&#47;*".
     * The method can be overridden to handle other types.  Implementations must
     * immediate produce a response and may not be async.
     * </p>
     *
     * @param baseRequest The base request
     * @param request The servlet request (may be wrapped)
     * @param response The response (may be wrapped)
     * @param code the http error code
     * @param message the http error message
     * @param contentType The mimetype to generate (may be *&#47;*or other wildcard)
     * @throws IOException if a response cannot be generated
     */
    protected void generateAcceptableResponse(ServletContextRequest baseRequest, HttpServletRequest request, HttpServletResponse response, int code, String message, String contentType) throws IOException
    {
        // We can generate an acceptable contentType, but can we generate an acceptable charset?
        // TODO refactor this in jetty-10 to be done in the other calling loop
        Charset charset = null;
        List<String> acceptable = baseRequest.getHeaders().getQualityCSV(HttpHeader.ACCEPT_CHARSET);
        if (!acceptable.isEmpty())
        {
            for (String name : acceptable)
            {
                if ("*".equals(name))
                {
                    charset = StandardCharsets.UTF_8;
                    break;
                }

                try
                {
                    charset = Charset.forName(name);
                }
                catch (Exception e)
                {
                    LOG.trace("IGNORED", e);
                }
            }
            if (charset == null)
                return;
        }

        MimeTypes.Type type;
        switch (contentType)
        {
            case "text/html":
            case "text/*":
            case "*/*":
                type = MimeTypes.Type.TEXT_HTML;
                if (charset == null)
                    charset = StandardCharsets.ISO_8859_1;
                break;

            case "text/json":
            case "application/json":
                type = MimeTypes.Type.TEXT_JSON;
                if (charset == null)
                    charset = StandardCharsets.UTF_8;
                break;

            case "text/plain":
                type = MimeTypes.Type.TEXT_PLAIN;
                if (charset == null)
                    charset = StandardCharsets.ISO_8859_1;
                break;

            default:
                return;
        }

        // write into the response aggregate buffer and flush it asynchronously.
        while (true)
        {
            try
            {
                // TODO currently the writer used here is of fixed size, so a large
                // TODO error page may cause a BufferOverflow.  In which case we try
                // TODO again with stacks disabled. If it still overflows, it is
                // TODO written without a body.
                ByteBuffer buffer = baseRequest.getServletContextResponse().getHttpOutput().getByteBuffer();
                ByteBufferOutputStream out = new ByteBufferOutputStream(buffer);
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, charset));

                switch (type)
                {
                    case TEXT_HTML:
                        response.setContentType(MimeTypes.Type.TEXT_HTML.asString());
                        response.setCharacterEncoding(charset.name());
                        request.setAttribute(ERROR_CHARSET, charset);
                        handleErrorPage(request, writer, code, message);
                        break;
                    case TEXT_JSON:
                        response.setContentType(contentType);
                        writeErrorJson(request, writer, code, message);
                        break;
                    case TEXT_PLAIN:
                        response.setContentType(MimeTypes.Type.TEXT_PLAIN.asString());
                        response.setCharacterEncoding(charset.name());
                        writeErrorPlain(request, writer, code, message);
                        break;
                    default:
                        throw new IllegalStateException();
                }

                writer.flush();
                break;
            }
            catch (BufferOverflowException e)
            {
                if (LOG.isDebugEnabled())
                    LOG.warn("Error page too large: {} {} {}", code, message, request, e);
                else
                    LOG.warn("Error page too large: {} {} {}", code, message, request);
                baseRequest.getServletContextResponse().resetContent();
                break;
            }
        }

        // Do an asynchronous completion.
        baseRequest.getServletChannel().sendErrorResponseAndComplete();
    }

    protected void handleErrorPage(HttpServletRequest request, Writer writer, int code, String message) throws IOException
    {
        writeErrorPage(request, writer, code, message, isShowStacks());
    }

    protected void writeErrorPage(HttpServletRequest request, Writer writer, int code, String message, boolean showStacks) throws IOException
    {
        if (message == null)
            message = HttpStatus.getMessage(code);

        writer.write("<html>\n<head>\n");
        writeErrorPageHead(request, writer, code, message);
        writer.write("</head>\n<body>");
        writeErrorPageBody(request, writer, code, message, showStacks);
        writer.write("\n</body>\n</html>\n");
    }

    protected void writeErrorPageHead(HttpServletRequest request, Writer writer, int code, String message) throws IOException
    {
        Charset charset = (Charset)request.getAttribute(ERROR_CHARSET);
        if (charset != null)
        {
            writer.write("<meta http-equiv=\"Content-Type\" content=\"text/html;charset=");
            writer.write(charset.name());
            writer.write("\"/>\n");
        }
        writer.write("<title>Error ");
        // TODO this code is duplicated in writeErrorPageMessage
        String status = Integer.toString(code);
        writer.write(status);
        if (message != null && !message.equals(status))
        {
            writer.write(' ');
            writer.write(StringUtil.sanitizeXmlString(message));
        }
        writer.write("</title>\n");
    }

    protected void writeErrorPageBody(HttpServletRequest request, Writer writer, int code, String message, boolean showStacks) throws IOException
    {
        String uri = request.getRequestURI();

        writeErrorPageMessage(request, writer, code, message, uri);
        if (showStacks)
            writeErrorPageStacks(request, writer);

        ((ServletApiRequest)request).getServletRequestInfo().getServletChannel().getHttpConfiguration()
            .writePoweredBy(writer, "<hr/>", "<hr/>\n");
    }

    protected void writeErrorPageMessage(HttpServletRequest request, Writer writer, int code, String message, String uri) throws IOException
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
        if (isShowOrigin())
        {
            htmlRow(writer, "SERVLET", request.getAttribute(Dispatcher.ERROR_SERVLET_NAME));
        }
        Throwable cause = (Throwable)request.getAttribute(Dispatcher.ERROR_EXCEPTION);
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

    protected void writeErrorPlain(HttpServletRequest request, PrintWriter writer, int code, String message)
    {
        writer.write("HTTP ERROR ");
        writer.write(Integer.toString(code));
        writer.write(' ');
        writer.write(StringUtil.sanitizeXmlString(message));
        writer.write("\n");
        writer.printf("URI: %s%n", request.getRequestURI());
        writer.printf("STATUS: %s%n", code);
        writer.printf("MESSAGE: %s%n", message);
        if (isShowOrigin())
        {
            writer.printf("SERVLET: %s%n", request.getAttribute(Dispatcher.ERROR_SERVLET_NAME));
        }
        Throwable cause = (Throwable)request.getAttribute(Dispatcher.ERROR_EXCEPTION);
        while (cause != null)
        {
            writer.printf("CAUSED BY %s%n", cause);
            if (isShowStacks())
            {
                cause.printStackTrace(writer);
            }
            cause = cause.getCause();
        }
    }

    protected void writeErrorJson(HttpServletRequest request, PrintWriter writer, int code, String message)
    {
        Throwable cause = (Throwable)request.getAttribute(Dispatcher.ERROR_EXCEPTION);
        Object servlet = request.getAttribute(Dispatcher.ERROR_SERVLET_NAME);
        Map<String, String> json = new HashMap<>();

        json.put("url", request.getRequestURI());
        json.put("status", Integer.toString(code));
        json.put("message", message);
        if (isShowOrigin() && servlet != null)
        {
            json.put("servlet", servlet.toString());
        }
        int c = 0;
        while (cause != null)
        {
            json.put("cause" + c++, cause.toString());
            cause = cause.getCause();
        }

        writer.append(json.entrySet().stream()
            .map(e -> HttpField.NAME_VALUE_TOKENIZER.quote(e.getKey()) + ":" + HttpField.NAME_VALUE_TOKENIZER.quote(StringUtil.sanitizeXmlString((e.getValue()))))
            .collect(Collectors.joining(",\n", "{\n", "\n}")));
    }

    protected void writeErrorPageStacks(HttpServletRequest request, Writer writer) throws IOException
    {
        Throwable th = (Throwable)request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
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

    public interface ErrorPageMapper
    {
        String getErrorPage(HttpServletRequest request);
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
}
