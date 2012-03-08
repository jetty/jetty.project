// ========================================================================
// Copyright (c) 2000-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http;

import java.nio.ByteBuffer;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/**
 * 
 */
public class MimeTypes
{
    public enum Type
    {
        FORM_ENCODED("application/x-www-form-urlencoded"),
        MESSAGE_HTTP("message/http"),
        MULTIPART_BYTERANGES("multipart/byteranges"),

        TEXT_HTML("text/html"),
        TEXT_PLAIN("text/plain"),
        TEXT_XML("text/xml"),
        TEXT_JSON("text/json"),

        TEXT_HTML_8859_1("text/html;charset=ISO-8859-1"),
        TEXT_PLAIN_8859_1("text/plain;charset=ISO-8859-1"),
        TEXT_XML_8859_1("text/xml;charset=ISO-8859-1"),
        TEXT_HTML_UTF_8("text/html;charset=UTF-8"),
        TEXT_PLAIN_UTF_8("text/plain;charset=UTF-8"),
        TEXT_XML_UTF_8("text/xml;charset=UTF-8"),
        TEXT_JSON_UTF_8("text/json;charset=UTF-8");


        /* ------------------------------------------------------------ */
        private final String _string;
        private final ByteBuffer _buffer;

        /* ------------------------------------------------------------ */
        Type(String s)
        {
            _string=s;
            _buffer=BufferUtil.toBuffer(s);
        }

        /* ------------------------------------------------------------ */
        public ByteBuffer toBuffer()
        {
            return _buffer.asReadOnlyBuffer();
        }

        /* ------------------------------------------------------------ */
        @Override
        public String toString()
        {
            return _string;
        }
    }

    /* ------------------------------------------------------------ */
    private static final Logger LOG = Log.getLogger(MimeTypes.class);
    private final static StringMap<MimeTypes.Type> CACHE= new StringMap<MimeTypes.Type>(true);
    private final static StringMap<ByteBuffer> TYPES= new StringMap<ByteBuffer>(true);
    private final static Map<String,ByteBuffer> __dftMimeMap = new HashMap<String,ByteBuffer>();
    private final static Map<String,String> __encodings = new HashMap<String,String>();

    static
    {

        for (MimeTypes.Type type : MimeTypes.Type.values())
        {
            CACHE.put(type.toString(),type);
            TYPES.put(type.toString(),type.toBuffer());

            int charset=type.toString().indexOf(";charset=");
            if (charset>0)
            {
                CACHE.put(type.toString().replace(";charset=","; charset="),type);
                TYPES.put(type.toString().replace(";charset=","; charset="),type.toBuffer());
            }
        }

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
            Enumeration<String> i = encoding.getKeys();
            while(i.hasMoreElements())
            {
                String type = i.nextElement();
                __encodings.put(type,encoding.getString(type));
            }
        }
        catch(MissingResourceException e)
        {
            LOG.warn(e.toString());
            LOG.debug(e);
        }


    }


    /* ------------------------------------------------------------ */
    private final Map<String,ByteBuffer> _mimeMap=new HashMap<String,ByteBuffer>();

    /* ------------------------------------------------------------ */
    /** Constructor.
     */
    public MimeTypes()
    {
    }

    /* ------------------------------------------------------------ */
    public synchronized Map<String,ByteBuffer> getMimeMap()
    {
        return _mimeMap;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param mimeMap A Map of file extension to mime-type.
     */
    public void setMimeMap(Map<String,String> mimeMap)
    {
        _mimeMap.clear();
        if (mimeMap!=null)
        {
            for (String ext : mimeMap.keySet())
                _mimeMap.put(StringUtil.asciiToLowerCase(ext),normalizeMimeType(mimeMap.get(ext)));
        }
    }

    /* ------------------------------------------------------------ */
    /** Get the MIME type by filename extension.
     * @param filename A file name
     * @return MIME type matching the longest dot extension of the
     * file name.
     */
    public ByteBuffer getMimeByExtension(String filename)
    {
        ByteBuffer type=null;

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
                    type=_mimeMap.get(ext);
                if (type==null)
                    type=__dftMimeMap.get(ext);
            }
        }

        if (type==null)
        {
            if (_mimeMap!=null)
                type=_mimeMap.get("*");
            if (type==null)
                type=__dftMimeMap.get("*");
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
        _mimeMap.put(StringUtil.asciiToLowerCase(extension),normalizeMimeType(type));
    }

    /* ------------------------------------------------------------ */
    private static ByteBuffer normalizeMimeType(String type)
    {
        MimeTypes.Type t =CACHE.get(type);
        if (t!=null)
            return t.toBuffer();

        return BufferUtil.toBuffer(StringUtil.asciiToLowerCase(type));
    }

    /* ------------------------------------------------------------ */
    public static String getCharsetFromContentType(ByteBuffer value)
    {
        int i=value.position();
        int end=value.limit();
        int state=0;
        int start=0;
        boolean quote=false;
        for (;i<end;i++)
        {
            byte b = value.get(i);

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
                        return StringUtil.normalizeCharset(value,start,i-start);
            }
        }

        if (state==10)
            return StringUtil.normalizeCharset(value,start,i-start);

        return __encodings.get(value);
    }
}
