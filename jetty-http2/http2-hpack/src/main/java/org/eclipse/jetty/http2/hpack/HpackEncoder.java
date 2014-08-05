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


package org.eclipse.jetty.http2.hpack;

import java.nio.ByteBuffer;
import java.util.EnumSet;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.hpack.HpackContext.Entry;
import org.eclipse.jetty.http2.hpack.HpackContext.StaticEntry;
import org.eclipse.jetty.io.ByteBufferPool.Lease;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HpackEncoder
{   
    public static final Logger LOG = Log.getLogger(HpackEncoder.class);
    
    private final static HttpField[] __status= new HttpField[599];
    
    
    private final static EnumSet<HttpHeader> __DO_NOT_HUFFMAN = 
            EnumSet.of(
                    HttpHeader.AUTHORIZATION,
                    HttpHeader.CONTENT_MD5,
                    HttpHeader.PROXY_AUTHENTICATE,
                    HttpHeader.PROXY_AUTHORIZATION);
    
    private final static EnumSet<HttpHeader> __DO_NOT_INDEX = 
            EnumSet.of(
                    // TODO ??? HttpHeader.C_PATH,
                    // TODO ??? HttpHeader.DATE,
                    HttpHeader.CONTENT_MD5,
                    HttpHeader.CONTENT_RANGE,
                    HttpHeader.ETAG,
                    HttpHeader.IF_MODIFIED_SINCE,
                    HttpHeader.IF_UNMODIFIED_SINCE,
                    HttpHeader.IF_NONE_MATCH,
                    HttpHeader.IF_RANGE,
                    HttpHeader.IF_MATCH,
                    HttpHeader.RANGE,
                    HttpHeader.EXPIRES,
                    HttpHeader.LAST_MODIFIED,
                    HttpHeader.SET_COOKIE,
                    HttpHeader.SET_COOKIE2);
    

    private final static EnumSet<HttpHeader> __NEVER_INDEX = 
            EnumSet.of(HttpHeader.SET_COOKIE,
                    HttpHeader.SET_COOKIE2);
        
    static
    {
        for (HttpStatus.Code code : HttpStatus.Code.values())
            __status[code.getCode()]=new HttpField(":status",Integer.toString(code.getCode()));
    }
    
    private final HpackContext _context;
    private final boolean _debug;
    private int _remoteMaxHeaderTableSize;
    private int _localMaxHeaderTableSize;
    
    public HpackEncoder()
    {
        this(4096,4096);
    }
    
    public HpackEncoder(int localMaxHeaderTableSize)
    {
        this(localMaxHeaderTableSize,4096);
    }
    
    public HpackEncoder(int localMaxHeaderTableSize,int remoteMaxHeaderTableSize)
    {
        _context=new HpackContext(remoteMaxHeaderTableSize);
        _remoteMaxHeaderTableSize=remoteMaxHeaderTableSize;
        _localMaxHeaderTableSize=localMaxHeaderTableSize;
        _debug=LOG.isDebugEnabled();
    }

    public HpackContext getContext()
    {
        return _context;
    }
    
    public void setRemoteMaxHeaderTableSize(int remoteMaxHeaderTableSize)
    {
        _remoteMaxHeaderTableSize=remoteMaxHeaderTableSize;
    }
    
    public void setLocalMaxHeaderTableSize(int localMaxHeaderTableSize)
    {
        _localMaxHeaderTableSize=localMaxHeaderTableSize;
    }
    
    // TODO better handling of buffer size
    public void encode(MetaData metadata,Lease lease,int buffersize)
    {
        ByteBuffer buffer = lease.acquire(buffersize,false); 
        lease.append(buffer,true);
        BufferUtil.clearToFill(buffer);
        encode(buffer,metadata);
        BufferUtil.flipToFlush(buffer,0);
    }
    
    public void encode(ByteBuffer buffer, MetaData metadata)
    {
        if (LOG.isDebugEnabled())
            LOG.debug(String.format("CtxTbl[%x] encoding",_context.hashCode()));

        int pos = buffer.position();

        // Check the header table sizes!
        int maxHeaderTableSize=Math.min(_remoteMaxHeaderTableSize,_localMaxHeaderTableSize);
        if (maxHeaderTableSize!=_context.getMaxHeaderTableSize())
            encodeMaxHeaderTableSize(buffer,maxHeaderTableSize);

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

        if (LOG.isDebugEnabled())
            LOG.debug(String.format("CtxTbl[%x] encoded %d octets",_context.hashCode(), buffer.position() - pos));
    }

    public void encodeMaxHeaderTableSize(ByteBuffer buffer, int maxHeaderTableSize)
    {
        if (maxHeaderTableSize>_remoteMaxHeaderTableSize)
            throw new IllegalArgumentException();
        buffer.put((byte)0x20);
        NBitInteger.encode(buffer,5,maxHeaderTableSize);
        _context.resize(maxHeaderTableSize);
    }

    private void encode(ByteBuffer buffer, HttpField field)
    {
        final int p=_debug?buffer.position():-1;
        
        String encoding=null;

        // TODO currently we do not check if there is enough space, so we may fail nastily.

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
            final Entry name;
            final boolean indexed;
            final boolean never_index;
            final boolean huffman;
            final int bits;
            
            // But do we know it's name?
            HttpHeader header = field.getHeader();
            if (header==null)
            {
                name = _context.get(field.getName());
                
                // has the custom header name been seen before?
                if (name==null)
                {
                    // unknown name and value, so let's index this just in case it is 
                    // the first time we have seen a custom name or a custom field.
                    // unless the name is changing, this is worthwhile
                    indexed=true;
                    never_index=false;
                    huffman=true;
                    bits = 6;
                    buffer.put((byte)0x40);
                }
                else
                {
                    // known custom name, but unknown value.
                    // This is probably a custom field with changing value, so don't index.
                    indexed=false;
                    never_index=false;
                    huffman=true;
                    bits = 4;
                    buffer.put((byte)0x00);
                }
            }
            else 
            {
                name = _context.get(header);

                if (__DO_NOT_INDEX.contains(header))
                {
                    // Non indexed field
                    indexed=false;
                    never_index=__NEVER_INDEX.contains(header);
                    huffman=!__DO_NOT_HUFFMAN.contains(header);
                    bits = 4;
                    buffer.put(never_index?(byte)0x10:(byte)0x00);
                }
                else if (header==HttpHeader.CONTENT_LENGTH && field.getValue().length()>1)
                {
                    // Non indexed content length for non zero value
                    indexed=false;
                    never_index=false;
                    huffman=true;
                    bits = 4;
                    buffer.put((byte)0x00);
                }
                else
                {
                    // indexed
                    indexed=true;
                    never_index=false;
                    huffman=!__DO_NOT_HUFFMAN.contains(header);
                    bits = 6;
                    buffer.put((byte)0x40);
                }
            }

            if (_debug)
            {
                encoding="Lit"+
                        ((name==null)?"HuffN":("IdxN"+(name.isStatic()?"S":"")+(1+NBitInteger.octectsNeeded(bits,_context.index(name)))))+
                        (huffman?"HuffV":"LitV")+
                        (indexed?"Idx":(never_index?"!!Idx":"!Idx"));
            }
            
            if (name!=null)
                NBitInteger.encode(buffer,bits,_context.index(name));
            else
            {
                // leave name index bits as 0
                // Encode the name always with lowercase huffman
                buffer.put((byte)0x80);
                NBitInteger.encode(buffer,7,Huffman.octetsNeededLC(field.getName()));
                Huffman.encodeLC(buffer,field.getName());
            }

            // Add the literal value
            String value=field.getValue();
            
            if (huffman)
            {
                // huffman literal value
                buffer.put((byte)0x80);
                NBitInteger.encode(buffer,7,Huffman.octetsNeeded(value));
                Huffman.encode(buffer,field.getValue());
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

            // If we want the field referenced, then we add it to our
            // table and reference set.
            if (indexed)
                _context.add(field);
        }

        if (_debug)
        {
            int e=buffer.position();
            if (LOG.isDebugEnabled())
                LOG.debug("encode {}:'{}' to '{}'",encoding,field,TypeUtil.toHexString(buffer.array(),buffer.arrayOffset()+p,e-p));
        }        
    }
}
