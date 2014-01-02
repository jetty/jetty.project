//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.BufferCache;
import org.eclipse.jetty.io.BufferCache.CachedBuffer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/** 
 * 
 */
public class MimeTypes
{
    private static final Logger LOG = Log.getLogger(MimeTypes.class);

    public final static String
      FORM_ENCODED="application/x-www-form-urlencoded",
      MESSAGE_HTTP="message/http",
      MULTIPART_BYTERANGES="multipart/byteranges",
      
      TEXT_HTML="text/html",
      TEXT_PLAIN="text/plain",
      TEXT_XML="text/xml",
      TEXT_JSON="text/json",
      
      TEXT_HTML_8859_1="text/html;charset=ISO-8859-1",
      TEXT_PLAIN_8859_1="text/plain;charset=ISO-8859-1",
      TEXT_XML_8859_1="text/xml;charset=ISO-8859-1",
      
      TEXT_HTML_UTF_8="text/html;charset=UTF-8",
      TEXT_PLAIN_UTF_8="text/plain;charset=UTF-8",
      TEXT_XML_UTF_8="text/xml;charset=UTF-8",
      TEXT_JSON_UTF_8="text/json;charset=UTF-8";

    private final static String
      TEXT_HTML__8859_1="text/html; charset=ISO-8859-1",
      TEXT_PLAIN__8859_1="text/plain; charset=ISO-8859-1",
      TEXT_XML__8859_1="text/xml; charset=ISO-8859-1",
      TEXT_HTML__UTF_8="text/html; charset=UTF-8",
      TEXT_PLAIN__UTF_8="text/plain; charset=UTF-8",
      TEXT_XML__UTF_8="text/xml; charset=UTF-8",
      TEXT_JSON__UTF_8="text/json; charset=UTF-8";

    private final static int
	FORM_ENCODED_ORDINAL=1,
    	MESSAGE_HTTP_ORDINAL=2,
    	MULTIPART_BYTERANGES_ORDINAL=3,
    	
    	TEXT_HTML_ORDINAL=4,
	TEXT_PLAIN_ORDINAL=5,
	TEXT_XML_ORDINAL=6,
        TEXT_JSON_ORDINAL=7,
	
        TEXT_HTML_8859_1_ORDINAL=8,
        TEXT_PLAIN_8859_1_ORDINAL=9,
        TEXT_XML_8859_1_ORDINAL=10,
        
        TEXT_HTML_UTF_8_ORDINAL=11,
        TEXT_PLAIN_UTF_8_ORDINAL=12,
        TEXT_XML_UTF_8_ORDINAL=13,
        TEXT_JSON_UTF_8_ORDINAL=14;
    
    private static int __index=15;
    
    public final static BufferCache CACHE = new BufferCache(); 

    public final static CachedBuffer
    	FORM_ENCODED_BUFFER=CACHE.add(FORM_ENCODED,FORM_ENCODED_ORDINAL),
    	MESSAGE_HTTP_BUFFER=CACHE.add(MESSAGE_HTTP, MESSAGE_HTTP_ORDINAL),
    	MULTIPART_BYTERANGES_BUFFER=CACHE.add(MULTIPART_BYTERANGES,MULTIPART_BYTERANGES_ORDINAL),
        
        TEXT_HTML_BUFFER=CACHE.add(TEXT_HTML,TEXT_HTML_ORDINAL),
        TEXT_PLAIN_BUFFER=CACHE.add(TEXT_PLAIN,TEXT_PLAIN_ORDINAL),
        TEXT_XML_BUFFER=CACHE.add(TEXT_XML,TEXT_XML_ORDINAL),
        TEXT_JSON_BUFFER=CACHE.add(TEXT_JSON,TEXT_JSON_ORDINAL),

        TEXT_HTML_8859_1_BUFFER=CACHE.add(TEXT_HTML_8859_1,TEXT_HTML_8859_1_ORDINAL),
        TEXT_PLAIN_8859_1_BUFFER=CACHE.add(TEXT_PLAIN_8859_1,TEXT_PLAIN_8859_1_ORDINAL),
        TEXT_XML_8859_1_BUFFER=CACHE.add(TEXT_XML_8859_1,TEXT_XML_8859_1_ORDINAL),
        
        TEXT_HTML_UTF_8_BUFFER=CACHE.add(TEXT_HTML_UTF_8,TEXT_HTML_UTF_8_ORDINAL),
        TEXT_PLAIN_UTF_8_BUFFER=CACHE.add(TEXT_PLAIN_UTF_8,TEXT_PLAIN_UTF_8_ORDINAL),
        TEXT_XML_UTF_8_BUFFER=CACHE.add(TEXT_XML_UTF_8,TEXT_XML_UTF_8_ORDINAL),
        TEXT_JSON_UTF_8_BUFFER=CACHE.add(TEXT_JSON_UTF_8,TEXT_JSON_UTF_8_ORDINAL),

        TEXT_HTML__8859_1_BUFFER=CACHE.add(TEXT_HTML__8859_1,TEXT_HTML_8859_1_ORDINAL),
        TEXT_PLAIN__8859_1_BUFFER=CACHE.add(TEXT_PLAIN__8859_1,TEXT_PLAIN_8859_1_ORDINAL),
        TEXT_XML__8859_1_BUFFER=CACHE.add(TEXT_XML__8859_1,TEXT_XML_8859_1_ORDINAL),
        
        TEXT_HTML__UTF_8_BUFFER=CACHE.add(TEXT_HTML__UTF_8,TEXT_HTML_UTF_8_ORDINAL),
        TEXT_PLAIN__UTF_8_BUFFER=CACHE.add(TEXT_PLAIN__UTF_8,TEXT_PLAIN_UTF_8_ORDINAL),
        TEXT_XML__UTF_8_BUFFER=CACHE.add(TEXT_XML__UTF_8,TEXT_XML_UTF_8_ORDINAL),
        TEXT_JSON__UTF_8_BUFFER=CACHE.add(TEXT_JSON__UTF_8,TEXT_JSON_UTF_8_ORDINAL);

    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private final static Map __dftMimeMap = new HashMap();
    private final static Map __encodings = new HashMap();
    static
    {
        try
        {
            ResourceBundle mime = ResourceBundle.getBundle("org/eclipse/jetty/http/mime");
            Enumeration i = mime.getKeys();
            while(i.hasMoreElements())
            {
                String ext = (String)i.nextElement();
                String m = mime.getString(ext);
                __dftMimeMap.put(StringUtil.asciiToLowerCase(ext),normalizeMimeType(m));
            }
        }
        catch(MissingResourceException e)
        {
            LOG.warn(e.toString());
            LOG.debug(e);
        }

        try
        {
            ResourceBundle encoding = ResourceBundle.getBundle("org/eclipse/jetty/http/encoding");
            Enumeration i = encoding.getKeys();
            while(i.hasMoreElements())
            {
                Buffer type = normalizeMimeType((String)i.nextElement());
                __encodings.put(type,encoding.getString(type.toString()));
            }
        }
        catch(MissingResourceException e)
        {
            LOG.warn(e.toString());
            LOG.debug(e);
        }

        
        TEXT_HTML_BUFFER.setAssociate("ISO-8859-1",TEXT_HTML_8859_1_BUFFER);
        TEXT_HTML_BUFFER.setAssociate("ISO_8859_1",TEXT_HTML_8859_1_BUFFER);
        TEXT_HTML_BUFFER.setAssociate("iso-8859-1",TEXT_HTML_8859_1_BUFFER);
        TEXT_PLAIN_BUFFER.setAssociate("ISO-8859-1",TEXT_PLAIN_8859_1_BUFFER);
        TEXT_PLAIN_BUFFER.setAssociate("ISO_8859_1",TEXT_PLAIN_8859_1_BUFFER);
        TEXT_PLAIN_BUFFER.setAssociate("iso-8859-1",TEXT_PLAIN_8859_1_BUFFER);
        TEXT_XML_BUFFER.setAssociate("ISO-8859-1",TEXT_XML_8859_1_BUFFER);
        TEXT_XML_BUFFER.setAssociate("ISO_8859_1",TEXT_XML_8859_1_BUFFER);
        TEXT_XML_BUFFER.setAssociate("iso-8859-1",TEXT_XML_8859_1_BUFFER);

        TEXT_HTML_BUFFER.setAssociate("UTF-8",TEXT_HTML_UTF_8_BUFFER);
        TEXT_HTML_BUFFER.setAssociate("UTF8",TEXT_HTML_UTF_8_BUFFER);
        TEXT_HTML_BUFFER.setAssociate("utf8",TEXT_HTML_UTF_8_BUFFER);
        TEXT_HTML_BUFFER.setAssociate("utf-8",TEXT_HTML_UTF_8_BUFFER);
        TEXT_PLAIN_BUFFER.setAssociate("UTF-8",TEXT_PLAIN_UTF_8_BUFFER);
        TEXT_PLAIN_BUFFER.setAssociate("UTF8",TEXT_PLAIN_UTF_8_BUFFER);
        TEXT_PLAIN_BUFFER.setAssociate("utf8",TEXT_PLAIN_UTF_8_BUFFER);
        TEXT_PLAIN_BUFFER.setAssociate("utf-8",TEXT_PLAIN_UTF_8_BUFFER);
        TEXT_XML_BUFFER.setAssociate("UTF-8",TEXT_XML_UTF_8_BUFFER);
        TEXT_XML_BUFFER.setAssociate("UTF8",TEXT_XML_UTF_8_BUFFER);
        TEXT_XML_BUFFER.setAssociate("utf8",TEXT_XML_UTF_8_BUFFER);
        TEXT_XML_BUFFER.setAssociate("utf-8",TEXT_XML_UTF_8_BUFFER);
        TEXT_JSON_BUFFER.setAssociate("UTF-8",TEXT_JSON_UTF_8_BUFFER);
        TEXT_JSON_BUFFER.setAssociate("UTF8",TEXT_JSON_UTF_8_BUFFER);
        TEXT_JSON_BUFFER.setAssociate("utf8",TEXT_JSON_UTF_8_BUFFER);
        TEXT_JSON_BUFFER.setAssociate("utf-8",TEXT_JSON_UTF_8_BUFFER);
    }


    /* ------------------------------------------------------------ */
    private Map _mimeMap;
    
    /* ------------------------------------------------------------ */
    /** Constructor.
     */
    public MimeTypes()
    {
    }

    /* ------------------------------------------------------------ */
    public synchronized Map getMimeMap()
    {
        return _mimeMap;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param mimeMap A Map of file extension to mime-type.
     */
    public void setMimeMap(Map mimeMap)
    {
        if (mimeMap==null)
        {
            _mimeMap=null;
            return;
        }
        
        Map m=new HashMap();
        Iterator i=mimeMap.entrySet().iterator();
        while (i.hasNext())
        {
            Map.Entry entry = (Map.Entry)i.next();
            m.put(entry.getKey(),normalizeMimeType(entry.getValue().toString()));
        }
        _mimeMap=m;
    }

    /* ------------------------------------------------------------ */
    /** Get the MIME type by filename extension.
     * @param filename A file name
     * @return MIME type matching the longest dot extension of the
     * file name.
     */
    public Buffer getMimeByExtension(String filename)
    {
        Buffer type=null;

        if (filename!=null)
        {
            int i=-1;
            while(type==null)
            {
                i=filename.indexOf(".",i+1);

                if (i<0 || i>=filename.length())
                    break;

                String ext=StringUtil.asciiToLowerCase(filename.substring(i+1));
                if (_mimeMap!=null)
                    type = (Buffer)_mimeMap.get(ext);
                if (type==null)
                    type=(Buffer)__dftMimeMap.get(ext);
            }
        }

        if (type==null)
        {
            if (_mimeMap!=null)
                type=(Buffer)_mimeMap.get("*");
             if (type==null)
                 type=(Buffer)__dftMimeMap.get("*");
        }

        return type;
    }

    /* ------------------------------------------------------------ */
    /** Set a mime mapping
     * @param extension
     * @param type
     */
    public void addMimeMapping(String extension,String type)
    {
        if (_mimeMap==null)
            _mimeMap=new HashMap();
        
        _mimeMap.put(StringUtil.asciiToLowerCase(extension),normalizeMimeType(type));
    }

    /* ------------------------------------------------------------ */
    private static synchronized Buffer normalizeMimeType(String type)
    {
        Buffer b =CACHE.get(type);
        if (b==null)
            b=CACHE.add(type,__index++);
        return b;
    }

    /* ------------------------------------------------------------ */
    public static String getCharsetFromContentType(Buffer value)
    {
        if (value instanceof CachedBuffer)
        {
            switch(((CachedBuffer)value).getOrdinal())
            {
                case TEXT_HTML_8859_1_ORDINAL:
                case TEXT_PLAIN_8859_1_ORDINAL:
                case TEXT_XML_8859_1_ORDINAL:
                    return StringUtil.__ISO_8859_1;

                case TEXT_HTML_UTF_8_ORDINAL:
                case TEXT_PLAIN_UTF_8_ORDINAL:
                case TEXT_XML_UTF_8_ORDINAL:
                case TEXT_JSON_UTF_8_ORDINAL:
                    return StringUtil.__UTF8;
            }
        }
        
        int i=value.getIndex();
        int end=value.putIndex();
        int state=0;
        int start=0;
        boolean quote=false;
        for (;i<end;i++)
        {
            byte b = value.peek(i);
            
            if (quote && state!=10)
            {
                if ('"'==b)
                    quote=false;
                continue;
            }
                
            switch(state)
            {
                case 0:
                    if ('"'==b)
                    {
                        quote=true;
                        break;
                    }
                    if (';'==b)
                        state=1;
                    break;

                case 1: if ('c'==b) state=2; else if (' '!=b) state=0; break;
                case 2: if ('h'==b) state=3; else state=0;break;
                case 3: if ('a'==b) state=4; else state=0;break;
                case 4: if ('r'==b) state=5; else state=0;break;
                case 5: if ('s'==b) state=6; else state=0;break;
                case 6: if ('e'==b) state=7; else state=0;break;
                case 7: if ('t'==b) state=8; else state=0;break;

                case 8: if ('='==b) state=9; else if (' '!=b) state=0; break;
                
                case 9: 
                    if (' '==b) 
                        break;
                    if ('"'==b) 
                    {
                        quote=true;
                        start=i+1;
                        state=10;
                        break;
                    }
                    start=i;
                    state=10;
                    break;
                    
                case 10:
                    if (!quote && (';'==b || ' '==b )||
                        (quote && '"'==b ))
                        return CACHE.lookup(value.peek(start,i-start)).toString(StringUtil.__UTF8);
            }
        }    
        
        if (state==10)
            return CACHE.lookup(value.peek(start,i-start)).toString(StringUtil.__UTF8);
        
        return (String)__encodings.get(value);
    }
}
