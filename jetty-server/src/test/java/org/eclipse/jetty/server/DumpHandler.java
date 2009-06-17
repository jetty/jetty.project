// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.Enumeration;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;

/* ------------------------------------------------------------ */
/** Dump request handler.
 * Dumps GET and POST requests.
 * Useful for testing and debugging.
 * 
 * @version $Id: DumpHandler.java,v 1.14 2005/08/13 00:01:26 gregwilkins Exp $
 * 
 */
public class DumpHandler extends AbstractHandler
{
    String label="Dump HttpHandler";
    
    public DumpHandler()
    {
    }
    
    public DumpHandler(String label)
    {
        this.label=label;
    }
    
    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.jetty.server.server.Handler#handle(java.lang.String, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, int)
     */
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {        
        if (!isStarted())
            return;
        
        if (request.getParameter("continue")!=null)
        {
            Continuation continuation = ContinuationSupport.getContinuation(request,response);
            continuation.setTimeout(Long.parseLong(request.getParameter("continue")));
            continuation.suspend();
        }
        
        baseRequest.setHandled(true);
        response.setHeader(HttpHeaders.CONTENT_TYPE,MimeTypes.TEXT_HTML);
        
        OutputStream out = response.getOutputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream(2048);
        Writer writer = new OutputStreamWriter(buf,StringUtil.__ISO_8859_1);
        writer.write("<html><h1>"+label+"</h1>");
        writer.write("<pre>\npathInfo="+request.getPathInfo()+"\n</pre>\n");
        writer.write("<pre>\ncontentType="+request.getContentType()+"\n</pre>\n");
        writer.write("<pre>\nencoding="+request.getCharacterEncoding()+"\n</pre>\n");
        writer.write("<h3>Header:</h3><pre>");
        writer.write(request.getMethod()+" "+request.getRequestURI()+" "+request.getProtocol()+"\n");
        Enumeration headers = request.getHeaderNames();
        while(headers.hasMoreElements())
        {
            String name=(String)headers.nextElement();
            writer.write(name);
            writer.write(": ");
            writer.write(request.getHeader(name));
            writer.write("\n");
        }
        writer.write("</pre>\n<h3>Parameters:</h3>\n<pre>");
        Enumeration names=request.getParameterNames();
        while(names.hasMoreElements())
        {
            String name=names.nextElement().toString();
            String[] values=request.getParameterValues(name);
            if (values==null || values.length==0)
            {
                writer.write(name);
                writer.write("=\n");
            }
            else if (values.length==1)
            {
                writer.write(name);
                writer.write("=");
                writer.write(values[0]);
                writer.write("\n");
            }
            else
            {
                for (int i=0; i<values.length; i++)
                {
                    writer.write(name);
                    writer.write("["+i+"]=");
                    writer.write(values[i]);
                    writer.write("\n");
                }
            }
        }
        
        String cookie_name=request.getParameter("CookieName");
        if (cookie_name!=null && cookie_name.trim().length()>0)
        {
            String cookie_action=request.getParameter("Button");
            try{
                String val=request.getParameter("CookieVal");
                val=val.replaceAll("[ \n\r=<>]","?");
                Cookie cookie=
                    new Cookie(cookie_name.trim(),
                                    request.getParameter("CookieVal"));
                if ("Clear Cookie".equals(cookie_action))
                    cookie.setMaxAge(0);
                response.addCookie(cookie);
            }
            catch(IllegalArgumentException e)
            {
                writer.write("</pre>\n<h3>BAD Set-Cookie:</h3>\n<pre>");
                writer.write(e.toString());
            }
        }
        
        writer.write("</pre>\n<h3>Cookies:</h3>\n<pre>");
        Cookie[] cookies=request.getCookies();
        if (cookies!=null && cookies.length>0)
        {
            for(int c=0;c<cookies.length;c++)
            {
                Cookie cookie=cookies[c];
                writer.write(cookie.getName());
                writer.write("=");
                writer.write(cookie.getValue());
                writer.write("\n");
            }
        }
        
        writer.write("</pre>\n<h3>Attributes:</h3>\n<pre>");
        Enumeration attributes=request.getAttributeNames();
        if (attributes!=null && attributes.hasMoreElements())
        {
            while(attributes.hasMoreElements())
            {
                String attr=attributes.nextElement().toString();
                writer.write(attr);
                writer.write("=");
                writer.write(request.getAttribute(attr).toString());
                writer.write("\n");
            }
        }
        
        writer.write("</pre>\n<h3>Content:</h3>\n<pre>");
        char[] content= new char[4096];
        int len;
        try{
            request.setCharacterEncoding(StringUtil.__UTF8);
            Reader in=request.getReader();
            String charset=request.getCharacterEncoding();
            if (charset==null)
                charset=StringUtil.__ISO_8859_1;
            while((len=in.read(content))>=0)
                writer.write(new String(content,0,len));
        }
        catch(IOException e)
        {   
            Log.warn(e);
            writer.write(e.toString());
        }
        
        writer.write("</pre>");
        writer.write("</html>");
        
        // commit now
        writer.flush();
        response.setContentLength(buf.size()+1000);

        try
        {
            buf.writeTo(out);

            buf.reset();
            writer.flush();
            for (int pad=998-buf.size();pad-->0;)
                writer.write(" ");
            writer.write("\015\012");
            writer.flush();
            buf.writeTo(out);

            response.setHeader("IgnoreMe","ignored");
        }
        catch(Exception e)
        {
            Log.ignore(e);
        }
    }
}
