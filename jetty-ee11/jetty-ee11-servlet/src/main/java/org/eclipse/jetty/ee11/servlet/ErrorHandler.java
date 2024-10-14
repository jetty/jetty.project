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
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.QuotedQualityCSV;
import org.eclipse.jetty.io.ByteBufferOutputStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        String cacheControl = getCacheControl();
        if (cacheControl != null)
            response.getHeaders().put(HttpHeader.CACHE_CONTROL.asString(), cacheControl);

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

        String message = (String)request.getAttribute(ERROR_MESSAGE);
        if (message == null)
            message = HttpStatus.getMessage(response.getStatus());
        generateResponse(request, response, response.getStatus(), message,  (Throwable)request.getAttribute(ERROR_EXCEPTION), callback);
        callback.succeeded();
        return true;
    }

    protected boolean generateAcceptableResponse(Request request, Response response, Callback callback, String contentType, List<Charset> charsets, int code, String message, Throwable cause) throws IOException
    {
        boolean result = super.generateAcceptableResponse(request, response, callback, contentType, charsets, code, message, cause);
        if (result)
        {
            // Do an asynchronous completion
            ServletContextRequest servletContextRequest = Request.as(request, ServletContextRequest.class);
            servletContextRequest.getServletChannel().sendErrorResponseAndComplete();
        }
        return result;
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
        writeErrorOrigin((String)request.getAttribute(ERROR_ORIGIN), (o) ->
        {
            try
            {
                htmlRow(writer, "SERVLET", o);
            }
            catch (IOException x)
            {
                throw new UncheckedIOException(x);
            }
        });

        while (cause != null)
        {
            htmlRow(writer, "CAUSED BY", cause);
            cause = cause.getCause();
        }
        writer.write("</table>\n");
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
