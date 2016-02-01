//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.ByteBuffer;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.AsyncContextEvent;
import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ByteArrayISO8859Writer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>Component that handles Error Pages.</p>
 * <p>An ErrorHandler is registered with {@link ContextHandler#setErrorHandler(ErrorHandler)} or
 * {@link org.eclipse.jetty.server.Server#addBean(Object)}.</p>
 * <p>It is called by {@link HttpServletResponse#sendError(int)} to write an error page via
 * {@link #handle(String, Request, HttpServletRequest, HttpServletResponse)}
 * or via {@link #badMessageError(int, String, HttpFields)} for bad requests for which a
 * dispatch cannot be done.</p>
 */
public class ErrorHandler extends AbstractHandler
{
    private static final Logger LOG = Log.getLogger(ErrorHandler.class);

    private boolean _showStacks = true;
    private boolean _showMessageInTitle = true;
    private String _cacheControl = "must-revalidate,no-cache,no-store";

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        String method = request.getMethod();
        if (!HttpMethod.GET.is(method) && !HttpMethod.POST.is(method) && !HttpMethod.HEAD.is(method))
        {
            baseRequest.setHandled(true);
            return;
        }

        if (this instanceof ErrorPageMapper)
        {
            String error_page = ((ErrorPageMapper)this).getErrorPage(request);

            ServletContext context = request.getServletContext();
            if (context == null)
            {
                AsyncContextEvent event = baseRequest.getHttpChannelState().getAsyncContextEvent();
                context = event == null ? null : event.getServletContext();
            }

            if (error_page != null && context != null)
            {
                Dispatcher dispatcher = (Dispatcher)context.getRequestDispatcher(error_page);
                if (dispatcher != null)
                {
                    try
                    {
                        dispatcher.error(request, response);
                        return;
                    }
                    catch (ServletException x)
                    {
                        throw new IOException(x);
                    }
                }
                else
                {
                    LOG.warn("Could not dispatch to error page: {}", error_page);
                    // Fall through to provide the default error page.
                }
            }
        }

        baseRequest.setHandled(true);
        
        HttpOutput out = baseRequest.getResponse().getHttpOutput();
        if (!out.isAsync())
        {
            response.setContentType(MimeTypes.Type.TEXT_HTML_8859_1.asString());
            String cacheHeader = getCacheControl();
            if (cacheHeader != null)
                response.setHeader(HttpHeader.CACHE_CONTROL.asString(), cacheHeader);
            ByteArrayISO8859Writer writer = new ByteArrayISO8859Writer(4096);
            String reason = (response instanceof Response) ? ((Response)response).getReason() : null;
            handleErrorPage(request, writer, response.getStatus(), reason);
            writer.flush();
            response.setContentLength(writer.size());
            writer.writeTo(response.getOutputStream());
            writer.destroy();
        }
    }

    /* ------------------------------------------------------------ */
    protected void handleErrorPage(HttpServletRequest request, Writer writer, int code, String message)
            throws IOException
    {
        writeErrorPage(request, writer, code, message, isShowStacks());
    }

    /* ------------------------------------------------------------ */
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

    /* ------------------------------------------------------------ */
    protected void writeErrorPageHead(HttpServletRequest request, Writer writer, int code, String message)
            throws IOException
    {
        writer.write("<meta http-equiv=\"Content-Type\" content=\"text/html;charset=utf-8\"/>\n");
        writer.write("<title>Error ");
        writer.write(Integer.toString(code));

        if (getShowMessageInTitle())
        {
            writer.write(' ');
            write(writer, message);
        }
        writer.write("</title>\n");
    }

    /* ------------------------------------------------------------ */
    protected void writeErrorPageBody(HttpServletRequest request, Writer writer, int code, String message, boolean showStacks)
            throws IOException
    {
        String uri = request.getRequestURI();

        writeErrorPageMessage(request, writer, code, message, uri);
        if (showStacks)
            writeErrorPageStacks(request, writer);

        Request.getBaseRequest(request).getHttpChannel().getHttpConfiguration()
                .writePoweredBy(writer, "<hr>", "<hr/>\n");
    }

    /* ------------------------------------------------------------ */
    protected void writeErrorPageMessage(HttpServletRequest request, Writer writer, int code, String message, String uri)
            throws IOException
    {
        writer.write("<h2>HTTP ERROR ");
        writer.write(Integer.toString(code));
        writer.write("</h2>\n<p>Problem accessing ");
        write(writer, uri);
        writer.write(". Reason:\n<pre>    ");
        write(writer, message);
        writer.write("</pre></p>");
    }

    /* ------------------------------------------------------------ */
    protected void writeErrorPageStacks(HttpServletRequest request, Writer writer)
            throws IOException
    {
        Throwable th = (Throwable)request.getAttribute("javax.servlet.error.exception");
        while (th != null)
        {
            writer.write("<h3>Caused by:</h3><pre>");
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            th.printStackTrace(pw);
            pw.flush();
            write(writer, sw.getBuffer().toString());
            writer.write("</pre>\n");

            th = th.getCause();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * <p>Generate a error response body to be sent for a bad message.</p>
     * <p>In this case there is something wrong with the request, so either
     * a request cannot be built, or it is not safe to build a request.
     * This method allows for a simple error page body to be returned
     * and some response headers to be set.</p>
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

    /* ------------------------------------------------------------ */
    /**
     * @return the cacheControl header to set on error responses.
     */
    public String getCacheControl()
    {
        return _cacheControl;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param cacheControl the cacheControl header to set on error responses.
     */
    public void setCacheControl(String cacheControl)
    {
        _cacheControl = cacheControl;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return whether stack traces are shown in the error pages
     */
    public boolean isShowStacks()
    {
        return _showStacks;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param showStacks whether stack traces are shown in the error pages
     */
    public void setShowStacks(boolean showStacks)
    {
        _showStacks = showStacks;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return whether the error message appears in page title
     */
    public boolean getShowMessageInTitle()
    {
        return _showMessageInTitle;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param showMessageInTitle whether the error message appears in page title
     */
    public void setShowMessageInTitle(boolean showMessageInTitle)
    {
        _showMessageInTitle = showMessageInTitle;
    }

    /* ------------------------------------------------------------ */
    protected void write(Writer writer, String string)
            throws IOException
    {
        if (string == null)
            return;

        writer.write(StringUtil.sanitizeXmlString(string));
    }

    /* ------------------------------------------------------------ */
    public interface ErrorPageMapper
    {
        String getErrorPage(HttpServletRequest request);
    }

    /* ------------------------------------------------------------ */
    public static ErrorHandler getErrorHandler(Server server, ContextHandler context)
    {
        ErrorHandler error_handler = null;
        if (context != null)
            error_handler = context.getErrorHandler();
        if (error_handler == null)
        {
            synchronized (ErrorHandler.class)
            {
                error_handler = server.getBean(ErrorHandler.class);
                if (error_handler == null)
                {
                    error_handler = new ErrorHandler();
                    error_handler.setServer(server);
                    server.addManaged(error_handler);
                }
            }
        }
        return error_handler;
    }
}
