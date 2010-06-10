// ========================================================================
// Copyright (c) 1996-2009 Mort Bay Consulting Pty. Ltd.
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
package org.eclipse.jetty.servlets;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;

/* ------------------------------------------------------------ */
/**
 * Multipart Form Data Filter.
 * <p>
 * This class decodes the multipart/form-data stream sent by a HTML form that uses a file input
 * item.  Any files sent are stored to a tempary file and a File object added to the request 
 * as an attribute.  All other values are made available via the normal getParameter API and
 * the setCharacterEncoding mechanism is respected when converting bytes to Strings.
 * 
 * If the init paramter "delete" is set to "true", any files created will be deleted when the
 * current request returns.
 * 
 * 
 * 
 */
public class MultiPartFilter implements Filter
{
    private final static String FILES ="org.eclipse.jetty.servlet.MultiPartFilter.files";
    private File tempdir;
    private boolean _deleteFiles;
    private ServletContext _context;
    private int _fileOutputBuffer = 0;

    /* ------------------------------------------------------------------------------- */
    /**
     * @see javax.servlet.Filter#init(javax.servlet.FilterConfig)
     */
    public void init(FilterConfig filterConfig) throws ServletException
    {
        tempdir=(File)filterConfig.getServletContext().getAttribute("javax.servlet.context.tempdir");
        _deleteFiles="true".equals(filterConfig.getInitParameter("deleteFiles"));
        String fileOutputBuffer = filterConfig.getInitParameter("fileOutputBuffer");
        if(fileOutputBuffer!=null)
            _fileOutputBuffer = Integer.parseInt(fileOutputBuffer);
        _context=filterConfig.getServletContext();
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * @see javax.servlet.Filter#doFilter(javax.servlet.ServletRequest,
     *      javax.servlet.ServletResponse, javax.servlet.FilterChain)
     */
    public void doFilter(ServletRequest request,ServletResponse response,FilterChain chain) 
        throws IOException, ServletException
    {
        HttpServletRequest srequest=(HttpServletRequest)request;
        if(srequest.getContentType()==null||!srequest.getContentType().startsWith("multipart/form-data"))
        {
            chain.doFilter(request,response);
            return;
        }
        
        BufferedInputStream in = new BufferedInputStream(request.getInputStream());
        String content_type=srequest.getContentType();
        
        // TODO - handle encodings
        
        String boundary="--"+value(content_type.substring(content_type.indexOf("boundary=")));
        byte[] byteBoundary=(boundary+"--").getBytes(StringUtil.__ISO_8859_1);
        
        MultiMap params = new MultiMap();
        for (Iterator i = request.getParameterMap().entrySet().iterator();i.hasNext();)
        {
            Map.Entry entry=(Map.Entry)i.next();
            Object value=entry.getValue();
            if (value instanceof String[])
                params.addValues(entry.getKey(),(String[])value);
            else
                params.add(entry.getKey(),value);
        }
        
        try
        {
            // Get first boundary
            byte[] bytes=TypeUtil.readLine(in);
            String line=bytes==null?null:new String(bytes,"UTF-8");
            if(line==null || !line.equals(boundary))
            {
                throw new IOException("Missing initial multi part boundary");
            }
            
            // Read each part
            boolean lastPart=false;
            String content_disposition=null;
            while(!lastPart)
            {
                while(true)
                {
                    bytes=TypeUtil.readLine(in);
                    // If blank line, end of part headers
                    if(bytes==null || bytes.length==0)
                        break;
                    line=new String(bytes,"UTF-8");
                    
                    // place part header key and value in map
                    int c=line.indexOf(':',0);
                    if(c>0)
                    {
                        String key=line.substring(0,c).trim().toLowerCase();
                        String value=line.substring(c+1,line.length()).trim();
                        if(key.equals("content-disposition"))
                            content_disposition=value;
                    }
                }
                // Extract content-disposition
                boolean form_data=false;
                if(content_disposition==null)
                {
                    throw new IOException("Missing content-disposition");
                }
                
                StringTokenizer tok=new StringTokenizer(content_disposition,";");
                String name=null;
                String filename=null;
                while(tok.hasMoreTokens())
                {
                    String t=tok.nextToken().trim();
                    String tl=t.toLowerCase();
                    if(t.startsWith("form-data"))
                        form_data=true;
                    else if(tl.startsWith("name="))
                        name=value(t);
                    else if(tl.startsWith("filename="))
                        filename=value(t);
                }
                
                // Check disposition
                if(!form_data)
                {
                    continue;
                }
                //It is valid for reset and submit buttons to have an empty name.
                //If no name is supplied, the browser skips sending the info for that field.
                //However, if you supply the empty string as the name, the browser sends the
                //field, with name as the empty string. So, only continue this loop if we
                //have not yet seen a name field.
                if(name==null)
                {
                    continue;
                }
                
                OutputStream out=null;
                File file=null;
                try
                {
                    if (filename!=null && filename.length()>0)
                    {
                        file = File.createTempFile("MultiPart", "", tempdir);
                        out = new FileOutputStream(file);
                        if(_fileOutputBuffer>0)
                            out = new BufferedOutputStream(out, _fileOutputBuffer);
                        request.setAttribute(name,file);
                        params.add(name, filename);
                        
                        if (_deleteFiles)
                        {
                            file.deleteOnExit();
                            ArrayList files = (ArrayList)request.getAttribute(FILES);
                            if (files==null)
                            {
                                files=new ArrayList();
                                request.setAttribute(FILES,files);
                            }
                            files.add(file);
                        }
                        
                    }
                    else
                        out=new ByteArrayOutputStream();
                    
                    int state=-2;
                    int c;
                    boolean cr=false;
                    boolean lf=false;
                    
                    // loop for all lines`
                    while(true)
                    {
                        int b=0;
                        while((c=(state!=-2)?state:in.read())!=-1)
                        {
                            state=-2;
                            // look for CR and/or LF
                            if(c==13||c==10)
                            {
                                if(c==13)
                                    state=in.read();
                                break;
                            }
                            // look for boundary
                            if(b>=0&&b<byteBoundary.length&&c==byteBoundary[b])
                                b++;
                            else
                            {
                                // this is not a boundary
                                if(cr)
                                    out.write(13);
                                if(lf)
                                    out.write(10);
                                cr=lf=false;
                                if(b>0)
                                    out.write(byteBoundary,0,b);
                                b=-1;
                                out.write(c);
                            }
                        }
                        // check partial boundary
                        if((b>0&&b<byteBoundary.length-2)||(b==byteBoundary.length-1))
                        {
                            if(cr)
                                out.write(13);
                            if(lf)
                                out.write(10);
                            cr=lf=false;
                            out.write(byteBoundary,0,b);
                            b=-1;
                        }
                        // boundary match
                        if(b>0||c==-1)
                        {
                            if(b==byteBoundary.length)
                                lastPart=true;
                            if(state==10)
                                state=-2;
                            break;
                        }
                        // handle CR LF
                        if(cr)
                            out.write(13);
                        if(lf)
                            out.write(10);
                        cr=(c==13);
                        lf=(c==10||state==10);
                        if(state==10)
                            state=-2;
                    }
                }
                finally
                {
                    out.close();
                }
                
                if (file==null)
                {
                    bytes = ((ByteArrayOutputStream)out).toByteArray();
                    params.add(name,bytes);
                }
            }
        
            // handle request
            chain.doFilter(new Wrapper(srequest,params),response);
        }
        finally
        {
            deleteFiles(request);
        }
    }

    private void deleteFiles(ServletRequest request)
    {
        ArrayList files = (ArrayList)request.getAttribute(FILES);
        if (files!=null)
        {
            Iterator iter = files.iterator();
            while (iter.hasNext())
            {
                File file=(File)iter.next();
                try
                {
                    file.delete();
                }
                catch(Exception e)
                {
                    _context.log("failed to delete "+file,e);
                }
            }
        }
    }
    
    /* ------------------------------------------------------------ */
    private String value(String nameEqualsValue)
    {
        String value=nameEqualsValue.substring(nameEqualsValue.indexOf('=')+1).trim();
        int i=value.indexOf(';');
        if(i>0)
            value=value.substring(0,i);
        if(value.startsWith("\""))
        {
            value=value.substring(1,value.indexOf('"',1));
        }
        else
        {
            i=value.indexOf(' ');
            if(i>0)
                value=value.substring(0,i);
        }
        return value;
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * @see javax.servlet.Filter#destroy()
     */
    public void destroy()
    {
    }

    /* ------------------------------------------------------------------------------- */
    /* ------------------------------------------------------------------------------- */
    private static class Wrapper extends HttpServletRequestWrapper
    {
        String _encoding=StringUtil.__UTF8;
        MultiMap _params;
        
        /* ------------------------------------------------------------------------------- */
        /** Constructor.
         * @param request
         */
        public Wrapper(HttpServletRequest request, MultiMap map)
        {
            super(request);
            this._params=map;
        }
        
        /* ------------------------------------------------------------------------------- */
        /**
         * @see javax.servlet.ServletRequest#getContentLength()
         */
        @Override
        public int getContentLength()
        {
            return 0;
        }
        
        /* ------------------------------------------------------------------------------- */
        /**
         * @see javax.servlet.ServletRequest#getParameter(java.lang.String)
         */
        @Override
        public String getParameter(String name)
        {
            Object o=_params.get(name);
            if (!(o instanceof byte[]) && LazyList.size(o)>0)
                o=LazyList.get(o,0);
            
            if (o instanceof byte[])
            {
                try
                {
                    String s=new String((byte[])o,_encoding);
                    return s;
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            }
            else if (o!=null)
                return String.valueOf(o);
            return null;
        }
        
        /* ------------------------------------------------------------------------------- */
        /**
         * @see javax.servlet.ServletRequest#getParameterMap()
         */
        @Override
        public Map getParameterMap()
        {
            return Collections.unmodifiableMap(_params.toStringArrayMap());
        }
        
        /* ------------------------------------------------------------------------------- */
        /**
         * @see javax.servlet.ServletRequest#getParameterNames()
         */
        @Override
        public Enumeration getParameterNames()
        {
            return Collections.enumeration(_params.keySet());
        }
        
        /* ------------------------------------------------------------------------------- */
        /**
         * @see javax.servlet.ServletRequest#getParameterValues(java.lang.String)
         */
        @Override
        public String[] getParameterValues(String name)
        {
            List l=_params.getValues(name);
            if (l==null || l.size()==0)
                return new String[0];
            String[] v = new String[l.size()];
            for (int i=0;i<l.size();i++)
            {
                Object o=l.get(i);
                if (o instanceof byte[])
                {
                    try
                    {
                        v[i]=new String((byte[])o,_encoding);
                    }
                    catch(Exception e)
                    {
                        e.printStackTrace();
                    }
                }
                else if (o instanceof String)
                    v[i]=(String)o;
            }
            return v;
        }
        
        /* ------------------------------------------------------------------------------- */
        /**
         * @see javax.servlet.ServletRequest#setCharacterEncoding(java.lang.String)
         */
        @Override
        public void setCharacterEncoding(String enc) 
            throws UnsupportedEncodingException
        {
            _encoding=enc;
        }
    }
}
