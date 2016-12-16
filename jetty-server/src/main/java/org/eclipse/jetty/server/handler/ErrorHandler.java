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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/** Handler for Error pages
 * An ErrorHandler is registered with {@link ContextHandler#setErrorHandler(ErrorHandler)} or
 * {@link Server#setErrorHandler(ErrorHandler)}.
 * It is called by the HttpResponse.sendError method to write a error page via {@link #handle(String, Request, HttpServletRequest, HttpServletResponse)}
 * or via {@link #badMessageError(int, String, HttpFields)} for bad requests for which a dispatch cannot be done.
 *
 */
public class ErrorHandler extends AbstractHandler
{    
    private static final Logger LOG = Log.getLogger(ErrorHandler.class);
    public final static String ERROR_PAGE="org.eclipse.jetty.server.error_page";
    
    boolean _showStacks=true;
    boolean _showMessageInTitle=true;
    String _cacheControl="must-revalidate,no-cache,no-store";

    /* ------------------------------------------------------------ */
    public ErrorHandler()
    {
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.server.Handler#handle(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, int)
     */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        doError(target,baseRequest,request,response);
    }
    
    @Override
    public void doError(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        String method = request.getMethod();
        if (!HttpMethod.GET.is(method) && !HttpMethod.POST.is(method) && !HttpMethod.HEAD.is(method))
        {
            baseRequest.setHandled(true);
            return;
        }
        
        if (this instanceof ErrorPageMapper)
        {
            String error_page=((ErrorPageMapper)this).getErrorPage(request);
            if (error_page!=null)
            {
                String old_error_page=(String)request.getAttribute(ERROR_PAGE);
                ServletContext servlet_context = request.getServletContext();
                if (servlet_context==null)
                    servlet_context=ContextHandler.getCurrentContext();
                if (servlet_context==null)
                {
                    LOG.warn("No ServletContext for error page {}",error_page);       
                }
                else if (old_error_page!=null && old_error_page.equals(error_page))
                {
                    LOG.warn("Error page loop {}",error_page); 
                }
                else
                {
                    request.setAttribute(ERROR_PAGE, error_page);

                    Dispatcher dispatcher = (Dispatcher) servlet_context.getRequestDispatcher(error_page);
                    try
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("error page dispatch {}->{}",error_page,dispatcher);
                        if(dispatcher!=null)
                        {
                            dispatcher.error(request, response);
                            return;
                        }
                        LOG.warn("No error page found "+error_page);
                    }
                    catch (ServletException e)
                    {
                        LOG.warn(Log.EXCEPTION, e);
                        return;
                    }
                }
            } 
            else 
            {
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("No Error Page mapping for request({} {}) (using default)",request.getMethod(),request.getRequestURI());
                }
            }
        }

        if (_cacheControl != null)
            response.setHeader(HttpHeader.CACHE_CONTROL.asString(), _cacheControl);
        generateAcceptableResponse(baseRequest,request,response,response.getStatus(),baseRequest.getResponse().getReason());
    }

    /** 
     * Generate an acceptable error response.
     * <p>This method is called to generate an Error page of a mime type that is 
     * acceptable to the user-agent.  The Accept header is evaluated in 
     * quality order and the method 
     * {@link #generateAcceptableResponse(Request, HttpServletRequest, HttpServletResponse, int, String, String)}
     * is called for each mimetype until {@link Request#isHandled()} is true.</p>
     * @param baseRequest The base request
     * @param request The servlet request (may be wrapped)
     * @param response The response (may be wrapped)
     * @throws IOException if the response cannot be generated
     */
    protected void generateAcceptableResponse(Request baseRequest, HttpServletRequest request, HttpServletResponse response, int code, String message)
        throws IOException
    {
        List<String> acceptable=baseRequest.getHttpFields().getQualityCSV(HttpHeader.ACCEPT);
        
        if (acceptable.isEmpty() && !baseRequest.getHttpFields().contains(HttpHeader.ACCEPT))
            generateAcceptableResponse(baseRequest,request,response,code,message,MimeTypes.Type.TEXT_HTML.asString());
        else
        {
            for (String mimeType:acceptable)
            {
                generateAcceptableResponse(baseRequest,request,response,code,message,mimeType);
                if (baseRequest.isHandled())
                    return;
            }
        }
        baseRequest.setHandled(true);
        baseRequest.getResponse().closeOutput();
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
     * @param baseRequest The base request
     * @param request The servlet request (may be wrapped)
     * @param response The response (may be wrapped)
     * @return A {@link Writer} if there is a known acceptable charset or null
     * @throws IOException if a Writer cannot be returned
     */
    protected Writer getAcceptableWriter(Request baseRequest, HttpServletRequest request, HttpServletResponse response)
        throws IOException
    {
        List<String> acceptable=baseRequest.getHttpFields().getQualityCSV(HttpHeader.ACCEPT_CHARSET);
        if (acceptable.isEmpty())
        {
            response.setCharacterEncoding(StandardCharsets.ISO_8859_1.name());
            return response.getWriter();
        }
        
        for (String charset:acceptable)
        {
            try
            {
                if ("*".equals(charset))
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                else
                    response.setCharacterEncoding(Charset.forName(charset).name());
                return response.getWriter();
            }
            catch(Exception e)
            {
                LOG.ignore(e);
            }
        }
        return null;
    }
        
    /* ------------------------------------------------------------ */
    /** Generate an acceptable error response for a mime type.
     * <p>This method is called for each mime type in the users agent's
     * <code>Accept</code> header, until {@link Request#isHandled()} is true and a 
     * response of the appropriate type is generated.
     * 
     * @param baseRequest The base request
     * @param request The servlet request (may be wrapped)
     * @param response The response (may be wrapped)
     * @param mimeType The mimetype to generate (may be *&#47;*or other wildcard)
     * @throws IOException if a response cannot be generated
     */
    protected void generateAcceptableResponse(Request baseRequest, HttpServletRequest request, HttpServletResponse response, int code, String message, String mimeType)
        throws IOException
    {
        switch(mimeType)
        {
            case "text/html":
            case "text/*":
            case "*/*":
            {
                baseRequest.setHandled(true);
                Writer writer = getAcceptableWriter(baseRequest,request,response);
                if (writer!=null)
                {
                    response.setContentType(MimeTypes.Type.TEXT_HTML.asString());
                    handleErrorPage(request, writer, code, message);
                }
            }
        }        
    }
    
    /* ------------------------------------------------------------ */
    protected void handleErrorPage(HttpServletRequest request, Writer writer, int code, String message)
        throws IOException
    {
        writeErrorPage(request, writer, code, message, _showStacks);
    }

    /* ------------------------------------------------------------ */
    protected void writeErrorPage(HttpServletRequest request, Writer writer, int code, String message, boolean showStacks)
        throws IOException
    {
        if (message == null)
            message=HttpStatus.getMessage(code);

        writer.write("<html>\n<head>\n");
        writeErrorPageHead(request,writer,code,message);
        writer.write("</head>\n<body>");
        writeErrorPageBody(request,writer,code,message,showStacks);
        writer.write("\n</body>\n</html>\n");
    }

    /* ------------------------------------------------------------ */
    protected void writeErrorPageHead(HttpServletRequest request, Writer writer, int code, String message)
        throws IOException
        {
        writer.write("<meta http-equiv=\"Content-Type\" content=\"text/html;charset=utf-8\"/>\n");
        writer.write("<title>Error ");
        writer.write(Integer.toString(code));

        if (_showMessageInTitle)
        {
            writer.write(' ');
            write(writer,message);
        }
        writer.write("</title>\n");
    }

    /* ------------------------------------------------------------ */
    protected void writeErrorPageBody(HttpServletRequest request, Writer writer, int code, String message, boolean showStacks)
        throws IOException
    {
        String uri= request.getRequestURI();

        writeErrorPageMessage(request,writer,code,message,uri);
        if (showStacks)
            writeErrorPageStacks(request,writer);

        Request.getBaseRequest(request).getHttpChannel().getHttpConfiguration()
            .writePoweredBy(writer,"<hr>","<hr/>\n");
    }

    /* ------------------------------------------------------------ */
    protected void writeErrorPageMessage(HttpServletRequest request, Writer writer, int code, String message,String uri)
    throws IOException
    {
        writer.write("<h2>HTTP ERROR ");
        writer.write(Integer.toString(code));
        writer.write("</h2>\n<p>Problem accessing ");
        write(writer,uri);
        writer.write(". Reason:\n<pre>    ");
        write(writer,message);
        writer.write("</pre></p>");
    }

    /* ------------------------------------------------------------ */
    protected void writeErrorPageStacks(HttpServletRequest request, Writer writer)
        throws IOException
    {
        Throwable th = (Throwable)request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        while(th!=null)
        {
            writer.write("<h3>Caused by:</h3><pre>");
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            th.printStackTrace(pw);
            pw.flush();
            write(writer,sw.getBuffer().toString());
            writer.write("</pre>\n");

            th =th.getCause();
        }
    }

    /* ------------------------------------------------------------ */
    /** Bad Message Error body
     * <p>Generate a error response body to be sent for a bad message.
     * In this case there is something wrong with the request, so either
     * a request cannot be built, or it is not safe to build a request.
     * This method allows for a simple error page body to be returned 
     * and some response headers to be set.
     * @param status The error code that will be sent
     * @param reason The reason for the error code (may be null)
     * @param fields The header fields that will be sent with the response.
     * @return The content as a ByteBuffer, or null for no body.
     */
    public ByteBuffer badMessageError(int status, String reason, HttpFields fields)
    {
        if (reason==null)
            reason=HttpStatus.getMessage(status);
        fields.put(HttpHeader.CONTENT_TYPE,MimeTypes.Type.TEXT_HTML_8859_1.asString());
        return BufferUtil.toBuffer("<h1>Bad Message " + status + "</h1><pre>reason: " + reason + "</pre>");
    }    
    
    /* ------------------------------------------------------------ */
    /** Get the cacheControl.
     * @return the cacheControl header to set on error responses.
     */
    public String getCacheControl()
    {
        return _cacheControl;
    }

    /* ------------------------------------------------------------ */
    /** Set the cacheControl.
     * @param cacheControl the cacheControl header to set on error responses.
     */
    public void setCacheControl(String cacheControl)
    {
        _cacheControl = cacheControl;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if stack traces are shown in the error pages
     */
    public boolean isShowStacks()
    {
        return _showStacks;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param showStacks True if stack traces are shown in the error pages
     */
    public void setShowStacks(boolean showStacks)
    {
        _showStacks = showStacks;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param showMessageInTitle if true, the error message appears in page title
     */
    public void setShowMessageInTitle(boolean showMessageInTitle)
    {
        _showMessageInTitle = showMessageInTitle;
    }


    /* ------------------------------------------------------------ */
    public boolean getShowMessageInTitle()
    {
        return _showMessageInTitle;
    }

    /* ------------------------------------------------------------ */
    protected void write(Writer writer,String string)
        throws IOException
    {
        if (string==null)
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
        ErrorHandler error_handler=null;
        if (context!=null)
            error_handler=context.getErrorHandler();
        if (error_handler==null && server!=null)
            error_handler = server.getBean(ErrorHandler.class);
        return error_handler;
    }
}
