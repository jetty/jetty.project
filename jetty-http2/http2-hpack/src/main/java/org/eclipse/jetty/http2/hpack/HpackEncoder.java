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


package org.eclipse.jetty.http2.hpack;

import java.nio.ByteBuffer;
import java.util.EnumSet;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http2.hpack.HpackContext.Entry;
import org.eclipse.jetty.http2.hpack.HpackContext.StaticEntry;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HpackEncoder
{
    public static final Logger LOG = Log.getLogger(HpackEncoder.class);

    private final static HttpField[] __status= new HttpField[599];


    final static EnumSet<HttpHeader> __DO_NOT_HUFFMAN =
            EnumSet.of(
                    HttpHeader.AUTHORIZATION,
                    HttpHeader.CONTENT_MD5,
                    HttpHeader.PROXY_AUTHENTICATE,
                    HttpHeader.PROXY_AUTHORIZATION);

    final static EnumSet<HttpHeader> __DO_NOT_INDEX =
            EnumSet.of(
                    // HttpHeader.C_PATH,  // TODO more data needed
                    // HttpHeader.DATE,    // TODO more data needed
                    HttpHeader.AUTHORIZATION,
                    HttpHeader.CONTENT_MD5,
                    HttpHeader.CONTENT_RANGE,
                    HttpHeader.ETAG,
                    HttpHeader.IF_MODIFIED_SINCE,
                    HttpHeader.IF_UNMODIFIED_SINCE,
                    HttpHeader.IF_NONE_MATCH,
                    HttpHeader.IF_RANGE,
                    HttpHeader.IF_MATCH,
                    HttpHeader.LOCATION,
                    HttpHeader.RANGE,
                    HttpHeader.RETRY_AFTER,
                    // HttpHeader.EXPIRES,
                    HttpHeader.LAST_MODIFIED,
                    HttpHeader.SET_COOKIE,
                    HttpHeader.SET_COOKIE2);


    final static EnumSet<HttpHeader> __NEVER_INDEX =
            EnumSet.of(
                    HttpHeader.AUTHORIZATION,
                    HttpHeader.SET_COOKIE,
                    HttpHeader.SET_COOKIE2);

    static
    {
        for (HttpStatus.Code code : HttpStatus.Code.values())
            __status[code.getCode()]=new PreEncodedHttpField(HttpHeader.C_STATUS,Integer.toString(code.getCode()));
    }

    private final HpackContext _context;
    private final boolean _debug;
    private int _remoteMaxDynamicTableSize;
    private int _localMaxDynamicTableSize;
    private int _maxHeaderListSize;
    private int _headerListSize;

    public HpackEncoder()
    {
        this(4096,4096,-1);
    }

    public HpackEncoder(int localMaxDynamicTableSize)
    {
        this(localMaxDynamicTableSize,4096,-1);
    }
    
    public HpackEncoder(int localMaxDynamicTableSize,int remoteMaxDynamicTableSize)
    {
        this(localMaxDynamicTableSize,remoteMaxDynamicTableSize,-1);
    }
    
    public HpackEncoder(int localMaxDynamicTableSize,int remoteMaxDynamicTableSize, int maxHeaderListSize)
    {
        _context=new HpackContext(remoteMaxDynamicTableSize);
        _remoteMaxDynamicTableSize=remoteMaxDynamicTableSize;
        _localMaxDynamicTableSize=localMaxDynamicTableSize;
        _maxHeaderListSize=maxHeaderListSize;
        _debug=LOG.isDebugEnabled();
    }

    public int getMaxHeaderListSize()
    {
        return _maxHeaderListSize;
    }

    public void setMaxHeaderListSize(int maxHeaderListSize)
    {
        _maxHeaderListSize = maxHeaderListSize;
    }

    public HpackContext getHpackContext()
    {
        return _context;
    }

    public void setRemoteMaxDynamicTableSize(int remoteMaxDynamicTableSize)
    {
        _remoteMaxDynamicTableSize=remoteMaxDynamicTableSize;
    }

    public void setLocalMaxDynamicTableSize(int localMaxDynamicTableSize)
    {
        _localMaxDynamicTableSize=localMaxDynamicTableSize;
    }

    public void encode(ByteBuffer buffer, MetaData metadata)
    {
        if (LOG.isDebugEnabled())
            LOG.debug(String.format("CtxTbl[%x] encoding",_context.hashCode()));

        _headerListSize=0;
        int pos = buffer.position();

        // Check the dynamic table sizes!
        int maxDynamicTableSize=Math.min(_remoteMaxDynamicTableSize,_localMaxDynamicTableSize);
        if (maxDynamicTableSize!=_context.getMaxDynamicTableSize())
            encodeMaxDynamicTableSize(buffer,maxDynamicTableSize);

        // Add Request/response meta fields
        if (metadata.isRequest())
        {
            MetaData.Request request = (MetaData.Request)metadata;

            // TODO optimise these to avoid HttpField creation
            String scheme=request.getURI().getScheme();
            encode(buffer,new HttpField(HttpHeader.C_SCHEME,scheme==null?HttpScheme.HTTP.asString():scheme));
            encode(buffer,new HttpField(HttpHeader.C_METHOD,request.getMethod()));
            encode(buffer,new HttpField(HttpHeader.C_AUTHORITY,request.getURI().getAuthority()));
            encode(buffer,new HttpField(HttpHeader.C_PATH,request.getURI().getPathQuery()));
        }
        else if (metadata.isResponse())
        {
            MetaData.Response response = (MetaData.Response)metadata;
            int code=response.getStatus();
            HttpField status = code<__status.length?__status[code]:null;
            if (status==null)
                status=new HttpField.IntValueHttpField(HttpHeader.C_STATUS,code);
            encode(buffer,status);
        }

        // Add all the other fields
        for (HttpField field : metadata)
            encode(buffer,field);

        // Check size
        if (_maxHeaderListSize>0 && _headerListSize>_maxHeaderListSize)
        {
            LOG.warn("Header list size too large {} > {} for {}",_headerListSize,_maxHeaderListSize);
            if (LOG.isDebugEnabled())
                LOG.debug("metadata={}",metadata);
        }
        
        if (LOG.isDebugEnabled())
            LOG.debug(String.format("CtxTbl[%x] encoded %d octets",_context.hashCode(), buffer.position() - pos));
    }

    public void encodeMaxDynamicTableSize(ByteBuffer buffer, int maxDynamicTableSize)
    {
        if (maxDynamicTableSize>_remoteMaxDynamicTableSize)
            throw new IllegalArgumentException();
        buffer.put((byte)0x20);
        NBitInteger.encode(buffer,5,maxDynamicTableSize);
        _context.resize(maxDynamicTableSize);
    }

    public void encode(ByteBuffer buffer, HttpField field)
    {
        if (field.getValue()==null)
            field = new HttpField(field.getHeader(),field.getName(),"");
        
        int field_size = field.getName().length() + field.getValue().length();
        _headerListSize+=field_size+32;
        
        final int p=_debug?buffer.position():-1;

        String encoding=null;

        // Is there an entry for the field?
        Entry entry = _context.get(field);
        if (entry!=null)
        {
            // Known field entry, so encode it as indexed
            if (entry.isStatic())
            {
                buffer.put(((StaticEntry)entry).getEncodedField());
                if (_debug)
                    encoding="IdxFieldS1";
            }
            else
            {
                int index=_context.index(entry);
                buffer.put((byte)0x80);
                NBitInteger.encode(buffer,7,index);
                if (_debug)
                    encoding="IdxField"+(entry.isStatic()?"S":"")+(1+NBitInteger.octectsNeeded(7,index));
            }
        }
        else
        {
            // Unknown field entry, so we will have to send literally.
            final boolean indexed;

            // But do we know it's name?
            HttpHeader header = field.getHeader();

            // Select encoding strategy
            if (header==null)
            {
                // Select encoding strategy for unknown header names
                Entry name = _context.get(field.getName());

                if (field instanceof PreEncodedHttpField)
                {
                    int i=buffer.position();
                    ((PreEncodedHttpField)field).putTo(buffer,HttpVersion.HTTP_2);
                    byte b=buffer.get(i);
                    indexed=b<0||b>=0x40;
                    if (_debug)
                        encoding=indexed?"PreEncodedIdx":"PreEncoded";
                }
                // has the custom header name been seen before?
                else if (name==null)
                {
                    // unknown name and value, so let's index this just in case it is
                    // the first time we have seen a custom name or a custom field.
                    // unless the name is changing, this is worthwhile
                    indexed=true;
                    encodeName(buffer,(byte)0x40,6,field.getName(),null);
                    encodeValue(buffer,true,field.getValue());
                    if (_debug)
                        encoding="LitHuffNHuffVIdx";
                }
                else
                {
                    // known custom name, but unknown value.
                    // This is probably a custom field with changing value, so don't index.
                    indexed=false;
                    encodeName(buffer,(byte)0x00,4,field.getName(),null);
                    encodeValue(buffer,true,field.getValue());
                    if (_debug)
                        encoding="LitHuffNHuffV!Idx";
                }
            }
            else
            {
                // Select encoding strategy for known header names
                Entry name = _context.get(header);

                if (field instanceof PreEncodedHttpField)
                {
                    // Preencoded field
                    int i=buffer.position();
                    ((PreEncodedHttpField)field).putTo(buffer,HttpVersion.HTTP_2);
                    byte b=buffer.get(i);
                    indexed=b<0||b>=0x40;
                    if (_debug)
                        encoding=indexed?"PreEncodedIdx":"PreEncoded";
                }
                else if (__DO_NOT_INDEX.contains(header))
                {
                    // Non indexed field
                    indexed=false;
                    boolean never_index=__NEVER_INDEX.contains(header);
                    boolean huffman=!__DO_NOT_HUFFMAN.contains(header);
                    encodeName(buffer,never_index?(byte)0x10:(byte)0x00,4,header.asString(),name);
                    encodeValue(buffer,huffman,field.getValue());

                    if (_debug)
                        encoding="Lit"+
                                ((name==null)?"HuffN":("IdxN"+(name.isStatic()?"S":"")+(1+NBitInteger.octectsNeeded(4,_context.index(name)))))+
                                (huffman?"HuffV":"LitV")+
                                (indexed?"Idx":(never_index?"!!Idx":"!Idx"));
                }
                else if (field_size>=_context.getMaxDynamicTableSize() || header==HttpHeader.CONTENT_LENGTH && field.getValue().length()>2)
                {
                    // Non indexed if field too large or a content length for 3 digits or more
                    indexed=false;
                    encodeName(buffer,(byte)0x00,4,header.asString(),name);
                    encodeValue(buffer,true,field.getValue());
                    if (_debug)
                        encoding="LitIdxNS"+(1+NBitInteger.octectsNeeded(4,_context.index(name)))+"HuffV!Idx";
                }
                else
                {
                    // indexed
                    indexed=true;
                    boolean huffman=!__DO_NOT_HUFFMAN.contains(header);
                    encodeName(buffer,(byte)0x40,6,header.asString(),name);
                    encodeValue(buffer,huffman,field.getValue());
                    if (_debug)
                        encoding=((name==null)?"LitHuffN":("LitIdxN"+(name.isStatic()?"S":"")+(1+NBitInteger.octectsNeeded(6,_context.index(name)))))+
                                (huffman?"HuffVIdx":"LitVIdx");
                }
            }

            // If we want the field referenced, then we add it to our
            // table and reference set.
            if (indexed)
                if (_context.add(field)==null)
                    throw new IllegalStateException();
        }

        if (_debug)
        {
            int e=buffer.position();
            if (LOG.isDebugEnabled())
                LOG.debug("encode {}:'{}' to '{}'",encoding,field,TypeUtil.toHexString(buffer.array(),buffer.arrayOffset()+p,e-p));
        }
    }

    private void encodeName(ByteBuffer buffer, byte mask, int bits, String name, Entry entry)
    {
        buffer.put(mask);
        if (entry==null)
        {
            // leave name index bits as 0
            // Encode the name always with lowercase huffman
            buffer.put((byte)0x80);
            NBitInteger.encode(buffer,7,Huffman.octetsNeededLC(name));
            Huffman.encodeLC(buffer,name);
        }
        else
        {
            NBitInteger.encode(buffer,bits,_context.index(entry));
        }
    }

    static void encodeValue(ByteBuffer buffer, boolean huffman, String value)
    {
        if (huffman)
        {
            // huffman literal value
            buffer.put((byte)0x80);
            NBitInteger.encode(buffer,7,Huffman.octetsNeeded(value));
            Huffman.encode(buffer,value);
        }
        else
        {
            // add literal assuming iso_8859_1
            buffer.put((byte)0x00);
            NBitInteger.encode(buffer,7,value.length());
            for (int i=0;i<value.length();i++)
            {
                char c=value.charAt(i);
                if (c<' '|| c>127)
                    throw new IllegalArgumentException();
                buffer.put((byte)c);
            }
        }
    }
}
