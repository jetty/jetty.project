//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.QuotedQualityCSV;
import org.eclipse.jetty.io.ByteBufferOutputStream;
import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Handler for Error pages
 * An ErrorHandler is registered with {@link ContextHandler#setErrorHandler(ErrorHandler)} or
 * {@link Server#setErrorHandler(ErrorHandler)}.
 * It is called by the HttpResponse.sendError method to write an error page via {@link #handle(String, Request, HttpServletRequest, HttpServletResponse)}
 * or via {@link #badMessageError(int, String, HttpFields)} for bad requests for which a dispatch cannot be done.
 */
public class ErrorHandler extends AbstractHandler
{
    // TODO This classes API needs to be majorly refactored/cleanup in jetty-10
    private static final Logger LOG = Log.getLogger(ErrorHandler.class);
    public static final String ERROR_PAGE = "org.eclipse.jetty.server.error_page";
    public static final String ERROR_CONTEXT = "org.eclipse.jetty.server.error_context";

    boolean _showServlet = true;
    boolean _showStacks = true;
    boolean _disableStacks = false;
    boolean _showMessageInTitle = true;
    String _cacheControl = "must-revalidate,no-cache,no-store";

    public ErrorHandler()
    {
    }

    public boolean errorPageForMethod(String method)
    {
        switch (method)
        {
            case "GET":
            case "POST":
            case "HEAD":
                return true;
            default:
                return false;
        }
    }

    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        // TODO inline this and remove method in jetty-10
        doError(target, baseRequest, request, response);
    }

    @Override
    public void doError(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        String cacheControl = getCacheControl();
        if (cacheControl != null)
            response.setHeader(HttpHeader.CACHE_CONTROL.asString(), cacheControl);

        // Look for an error page dispatcher
        // This logic really should be in ErrorPageErrorHandler, but some implementations extend ErrorHandler
        // and implement ErrorPageMapper directly, so we do this here in the base class.
        String errorPage = (this instanceof ErrorPageMapper) ? ((ErrorPageMapper)this).getErrorPage(request) : null;
        ContextHandler.Context context = baseRequest.getErrorContext();
        Dispatcher errorDispatcher = (errorPage != null && context != null)
            ? (Dispatcher)context.getRequestDispatcher(errorPage) : null;

        try
        {
            if (errorDispatcher != null)
            {
                try
                {
                    errorDispatcher.error(request, response);
                    return;
                }
                catch (ServletException e)
                {
                    LOG.debug(e);
                    if (response.isCommitted())
                        return;
                }
            }

            String message = (String)request.getAttribute(Dispatcher.ERROR_MESSAGE);
            if (message == null)
                message = baseRequest.getResponse().getReason();
            generateAcceptableResponse(baseRequest, request, response, response.getStatus(), message);
        }
        finally
        {
            baseRequest.setHandled(true);
        }
    }

    /**
     * Generate an acceptable error response.
     * <p>This method is called to generate an Error page of a mime type that is
     * acceptable to the user-agent.  The Accept header is evaluated in
     * quality order and the method
     * {@link #generateAcceptableResponse(Request, HttpServletRequest, HttpServletResponse, int, String, String)}
     * is called for each mimetype until the response is written to or committed.</p>
     *
     * @param baseRequest The base request
     * @param request The servlet request (may be wrapped)
     * @param response The response (may be wrapped)
     * @param code the http error code
     * @param message the http error message
     * @throws IOException if the response cannot be generated
     */
    protected void generateAcceptableResponse(Request baseRequest, HttpServletRequest request, HttpServletResponse response, int code, String message)
        throws IOException
    {
        List<String> acceptable = baseRequest.getHttpFields().getQualityCSV(HttpHeader.ACCEPT, QuotedQualityCSV.MOST_SPECIFIC_MIME_ORDERING);

        if (acceptable.isEmpty() && !baseRequest.getHttpFields().contains(HttpHeader.ACCEPT))
        {
            generateAcceptableResponse(baseRequest, request, response, code, message, MimeTypes.Type.TEXT_HTML.asString());
        }
        else
        {
            for (String mimeType : acceptable)
            {
                generateAcceptableResponse(baseRequest, request, response, code, message, mimeType);
                if (response.isCommitted() || baseRequest.getResponse().isWritingOrStreaming())
                    break;
            }
        }
    }

    /**
     * Returns an acceptable writer for an error page.
     * <p>Uses the user-agent's <code>Accept-Charset</code> to get response
     * {@link Writer}.  The acceptable charsets are tested in quality order
     * if they are known to the JVM and the first known is set on
     * {@link HttpServletResponse#setCharacterEncoding(String)} and the
     * {@link HttpServletResponse#getWriter()} method used to return a writer.
     * If there is no <code>Accept-Charset</code> header then
     * <code>ISO-8859-1</code> is used.  If '*' is the highest quality known
     * charset, then <code>utf-8</code> is used.
     * </p>
     *
     * @param baseRequest The base request
     * @param request The servlet request (may be wrapped)
     * @param response The response (may be wrapped)
     * @return A {@link Writer} if there is a known acceptable charset or null
     * @throws IOException if a Writer cannot be returned
     */
    @Deprecated
    protected Writer getAcceptableWriter(Request baseRequest, HttpServletRequest request, HttpServletResponse response)
        throws IOException
    {
        List<String> acceptable = baseRequest.getHttpFields().getQualityCSV(HttpHeader.ACCEPT_CHARSET);
        if (acceptable.isEmpty())
        {
            response.setCharacterEncoding(StandardCharsets.ISO_8859_1.name());
            return response.getWriter();
        }

        for (String charset : acceptable)
        {
            try
            {
                if ("*".equals(charset))
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                else
                    response.setCharacterEncoding(Charset.forName(charset).name());
                return response.getWriter();
            }
            catch (Exception e)
            {
                LOG.ignore(e);
            }
        }
        return null;
    }

    /**
     * Generate an acceptable error response for a mime type.
     * <p>This method is called for each mime type in the users agent's
     * <code>Accept</code> header, until {@link Request#isHandled()} is true and a
     * response of the appropriate type is generated.
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
    protected void generateAcceptableResponse(Request baseRequest, HttpServletRequest request, HttpServletResponse response, int code, String message, String contentType)
        throws IOException
    {
        // We can generate an acceptable contentType, but can we generate an acceptable charset?
        // TODO refactor this in jetty-10 to be done in the other calling loop
        Charset charset = null;
        List<String> acceptable = baseRequest.getHttpFields().getQualityCSV(HttpHeader.ACCEPT_CHARSET);
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
                    LOG.ignore(e);
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
                ByteBuffer buffer = baseRequest.getResponse().getHttpOutput().getBuffer();
                ByteBufferOutputStream out = new ByteBufferOutputStream(buffer);
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, charset));

                switch (type)
                {
                    case TEXT_HTML:
                        response.setContentType(MimeTypes.Type.TEXT_HTML.asString());
                        response.setCharacterEncoding(charset.name());
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
                LOG.warn("Error page too large: {} {} {}", code, message, request);
                if (LOG.isDebugEnabled())
                    LOG.warn(e);
                baseRequest.getResponse().resetContent();
                if (!_disableStacks)
                {
                    LOG.info("Disabling showsStacks for " + this);
                    _disableStacks = true;
                    continue;
                }
                break;
            }
        }

        // Do an asynchronous completion.
        baseRequest.getHttpChannel().sendResponseAndComplete();
    }

    protected void handleErrorPage(HttpServletRequest request, Writer writer, int code, String message)
        throws IOException
    {
        writeErrorPage(request, writer, code, message, _showStacks);
    }

    protected void writeErrorPage(HttpServletRequest request, Writer writer, int code, String message, boolean showStacks)
        throws IOException
    {
        if (message == null)
            message = HttpStatus.getMessage(code);

        writer.write("<html>\n<head>\n");
        writeErrorPageHead(request, writer, code, message);
        writer.write("</head>\n<body>");
        writeErrorPageBody(request, writer, code, message, showStacks);
        writer.write("\n</body>\n</html>\n");
    }

    protected void writeErrorPageHead(HttpServletRequest request, Writer writer, int code, String message)
        throws IOException
    {
        writer.write("<meta http-equiv=\"Content-Type\" content=\"text/html;charset=utf-8\"/>\n");
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

    protected void writeErrorPageBody(HttpServletRequest request, Writer writer, int code, String message, boolean showStacks)
        throws IOException
    {
        String uri = request.getRequestURI();

        writeErrorPageMessage(request, writer, code, message, uri);
        if (showStacks && !_disableStacks)
            writeErrorPageStacks(request, writer);

        Request.getBaseRequest(request).getHttpChannel().getHttpConfiguration()
            .writePoweredBy(writer, "<hr>", "<hr/>\n");
    }

    protected void writeErrorPageMessage(HttpServletRequest request, Writer writer, int code, String message, String uri)
        throws IOException
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
        if (isShowServlet())
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

    private void htmlRow(Writer writer, String tag, Object value)
        throws IOException
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

    private void writeErrorPlain(HttpServletRequest request, PrintWriter writer, int code, String message)
    {
        writer.write("HTTP ERROR ");
        writer.write(Integer.toString(code));
        writer.write(' ');
        writer.write(StringUtil.sanitizeXmlString(message));
        writer.write("\n");
        writer.printf("URI: %s%n", request.getRequestURI());
        writer.printf("STATUS: %s%n", code);
        writer.printf("MESSAGE: %s%n", message);
        if (isShowServlet())
        {
            writer.printf("SERVLET: %s%n", request.getAttribute(Dispatcher.ERROR_SERVLET_NAME));
        }
        Throwable cause = (Throwable)request.getAttribute(Dispatcher.ERROR_EXCEPTION);
        while (cause != null)
        {
            writer.printf("CAUSED BY %s%n", cause);
            if (isShowStacks() && !_disableStacks)
            {
                cause.printStackTrace(writer);
            }
            cause = cause.getCause();
        }
    }

    private void writeErrorJson(HttpServletRequest request, PrintWriter writer, int code, String message)
    {
        Throwable cause = (Throwable)request.getAttribute(Dispatcher.ERROR_EXCEPTION);
        Object servlet = request.getAttribute(Dispatcher.ERROR_SERVLET_NAME);
        Map<String,String> json = new HashMap<>();

        json.put("url", request.getRequestURI());
        json.put("status", Integer.toString(code));
        json.put("message", message);
        if (isShowServlet() && servlet != null)
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
                .map(e -> QuotedStringTokenizer.quote(e.getKey()) +
                        ":" +
                    QuotedStringTokenizer.quote(StringUtil.sanitizeXmlString((e.getValue()))))
                .collect(Collectors.joining(",\n", "{\n", "\n}")));
    }

    protected void writeErrorPageStacks(HttpServletRequest request, Writer writer)
        throws IOException
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
    public ByteBuffer badMessageError(int status, String reason, HttpFields fields)
    {
        if (reason == null)
            reason = HttpStatus.getMessage(status);
        fields.put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_HTML_8859_1.asString());
        return BufferUtil.toBuffer("<h1>Bad Message " + status + "</h1><pre>reason: " + reason + "</pre>");
    }

    /**
     * Get the cacheControl.
     *
     * @return the cacheControl header to set on error responses.
     */
    public String getCacheControl()
    {
        return _cacheControl;
    }

    /**
     * Set the cacheControl.
     *
     * @param cacheControl the cacheControl header to set on error responses.
     */
    public void setCacheControl(String cacheControl)
    {
        _cacheControl = cacheControl;
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

    protected void write(Writer writer, String string)
        throws IOException
    {
        if (string == null)
            return;

        writer.write(StringUtil.sanitizeXmlString(string));
    }

    public interface ErrorPageMapper
    {
        String getErrorPage(HttpServletRequest request);
    }

    public static ErrorHandler getErrorHandler(Server server, ContextHandler context)
    {
        ErrorHandler errorHandler = null;
        if (context != null)
            errorHandler = context.getErrorHandler();
        if (errorHandler == null && server != null)
            errorHandler = server.getBean(ErrorHandler.class);
        return errorHandler;
    }
}
