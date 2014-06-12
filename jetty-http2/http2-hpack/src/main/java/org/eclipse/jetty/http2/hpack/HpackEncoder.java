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
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.hpack.HpackContext.Entry;
import org.eclipse.jetty.io.ByteBufferPool.Lease;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HpackEncoder
{   
    public static final Logger LOG = Log.getLogger(HpackEncoder.class);
    
    private final static HttpField[] __status= new HttpField[599];
    
    private final static EnumSet<HttpHeader> __NEVER_INDEX = 
            EnumSet.of(HttpHeader.SET_COOKIE,
                    HttpHeader.SET_COOKIE2);
    
    private final static EnumSet<HttpHeader> __DO_NOT_HUFFMAN = 
            EnumSet.of(HttpHeader.COOKIE,
                    HttpHeader.SET_COOKIE,
                    HttpHeader.SET_COOKIE2,
                    HttpHeader.AUTHORIZATION,
                    HttpHeader.CONTENT_MD5,
                    HttpHeader.PROXY_AUTHENTICATE,
                    HttpHeader.PROXY_AUTHORIZATION);
    
    private final static EnumSet<HttpHeader> __USE_REFERENCE_SET = 
            EnumSet.of(HttpHeader.ACCEPT,
                    HttpHeader.ACCEPT_CHARSET,
                    HttpHeader.ACCEPT_ENCODING,
                    HttpHeader.ACCEPT_LANGUAGE,
                    HttpHeader.ACCEPT_RANGES,
                    HttpHeader.ALLOW,
                    HttpHeader.AUTHORIZATION,
                    HttpHeader.CACHE_CONTROL,
                    HttpHeader.CONTENT_LANGUAGE,
                    HttpHeader.COOKIE,
                    HttpHeader.DATE,
                    HttpHeader.HOST,
                    HttpHeader.SERVER,
                    HttpHeader.SERVLET_ENGINE,
                    HttpHeader.USER_AGENT);
    
    static
    {
        for (HttpStatus.Code code : HttpStatus.Code.values())
            __status[code.getCode()]=new HttpField(":status",Integer.toString(code.getCode()));
    }
    
    private final HpackContext _context;
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
    
    public void encode(MetaData metadata,Lease lease)
    {
        ByteBuffer buffer = lease.acquire(8*1024,false); // TODO make size configurable
        lease.append(buffer,true);
        // TODO handle multiple buffers if large size configured.
        BufferUtil.clearToFill(buffer);
        encode(buffer,metadata);
        BufferUtil.flipToFlush(buffer,0);
    }

    
    public void encode(ByteBuffer buffer, MetaData metadata)
    {
        // Check the header table sizes!
        int maxHeaderTableSize=Math.min(_remoteMaxHeaderTableSize,_localMaxHeaderTableSize);
        if (maxHeaderTableSize!=_context.getMaxHeaderTableSize())
            encodeMaxHeaderTableSize(buffer,maxHeaderTableSize);
        
        // Add Request/response meta fields
        if (metadata.isRequest())
        {
            MetaData.Request request = (MetaData.Request)metadata;
            
            // TODO optimise these to avoid HttpField creation
            encode(buffer,new HttpField(":scheme",request.getScheme().asString()));
            encode(buffer,new HttpField(":method",request.getMethod()));
            encode(buffer,new HttpField(":authority",request.getAuthority())); // TODO look for host header?
            encode(buffer,new HttpField(":path",request.getPath()));
            
        }
        else if (metadata.isResponse())
        {
            MetaData.Response response = (MetaData.Response)metadata;
            int code=response.getStatus();
            HttpField status = code<__status.length?__status[code]:null;
            if (status==null)
                status=new HttpField(":status",Integer.toString(code));
            encode(buffer,status);
        }
        
        // Add all the other fields
        for (HttpField field : metadata)
        {
            encode(buffer,field);
        }

        _context.removedUnusedReferences(buffer);
    }

    public void encodeMaxHeaderTableSize(ByteBuffer buffer, int maxHeaderTableSize)
    {
        if (maxHeaderTableSize>_remoteMaxHeaderTableSize)
            throw new IllegalArgumentException();
        buffer.put((byte)0x20);
        NBitInteger.encode(buffer,4,maxHeaderTableSize);
        _context.resize(maxHeaderTableSize);
    }

    public void encodeClearReferenceSet(ByteBuffer buffer)
    {
        buffer.put((byte)0x30);
        _context.clearReferenceSet();
    }

    private void encode(ByteBuffer buffer, HttpField field)
    {
        final int p=LOG.isDebugEnabled()?buffer.position():-1;
        String encoding=null;

        // TODO currently we do not check if there is enough space, so we will always
        // return true or fail nastily.

        // Is there an entry for the field?
        Entry entry = _context.get(field);

        if (entry!=null)
        {
            // if entry is already in the reference set, then nothing more to do.
            if (entry.isInReferenceSet())
            {
                entry.used();
                encoding="InRefSet";
            }

            // Is this as static field
            else if (entry.isStatic())
            {
                // TODO Strategy decision to make!
                // Should we add to reference set or just always send as indexed?

                if (entry==HpackContext.METHOD_GET || entry==HpackContext.STATUS_200)
                {
                    // :status: 200 and :method: GET are worthwhile putting into ref set.
                    // as they are likely to be repeated.
                    int index=_context.index(entry);
                    buffer.put((byte)0x80);
                    NBitInteger.encode(buffer,7,index);
                    entry=_context.add(entry.getHttpField());
                    if (entry!=null)
                        _context.addToRefSet(entry);
                }
                else
                {        
                    // Let's send other statics as indexed to reduce churn in header table!
                    // BUGGER! Can't do that as we have to copy them into header table.
                    // Oh well all the static fields have small values, so
                    // lets send as literal header, indexed name.
                    // We don't need never indexed because the cookie fields are name only and we can
                    // huffman encode the value for the same reason. 
                    
                    // Add the token
                    buffer.put((byte)0x00);
                    // Add the name index
                    NBitInteger.encode(buffer,4,_context.index(entry));
                    // Add the value
                    buffer.put(entry.getStaticHuffmanValue());

                    encoding="LiteralStaticIdxNameHuffmanValue";
                }
            }
            else
            {
                encoding="IdxField";

                // So we can emit the index and add the entry to the reference Set
                int index=_context.index(entry);
                buffer.put((byte)0x80);
                NBitInteger.encode(buffer,7,index);
                _context.addToRefSet(entry);
            }
        }
        else
        {
            // Must be a new entry, so we will have to send literally.
            // TODO Strategy decision to make!
            // What fields will we put in the reference set and what fields will we huffman encode?

            // Let's make these decisions by lookup of known fields
            HttpHeader header = field.getHeader();
            final boolean never_index;
            final boolean huffman;
            final boolean reference;
            final int name_bits;
            final byte mask;
            if (header==null)
            {
                never_index=false;
                huffman=true;
                reference=true;
                name_bits = 6;
                mask=(byte)0x40;
            }
            else if (__USE_REFERENCE_SET.contains(header))
            {
                reference=true;
                never_index=false;
                huffman=!__DO_NOT_HUFFMAN.contains(header);
                name_bits = 6;
                mask=(byte)0x40;
            }
            else
            {
                reference=false;
                never_index=__NEVER_INDEX.contains(header);
                huffman=!__DO_NOT_HUFFMAN.contains(header);
                name_bits = 4;
                mask=never_index?(byte)0x01:(byte)0x00;
            }

            // Add the mask bits
            buffer.put(mask);

            // Look for a name Index
            Entry name_entry = _context.get(field.getName());

            if (p>=0)
            {
                encoding="Lit"+
                        ((name_entry==null)?"HuffName":"IdxName")+
                        (huffman?"HuffVal":"LitVal")+
                        (reference?"Idxd":(never_index?"NeverIdx":""));
            }
            
            if (name_entry!=null)
                NBitInteger.encode(buffer,name_bits,_context.index(name_entry));
            else
            {
                // Encode the name always with huffman
                buffer.put((byte)0x80);
                NBitInteger.encode(buffer,7,Huffman.octetsNeeded(field.getName()));
                Huffman.encode(buffer,field.getName());
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
            if (reference)
            {
                Entry new_entry=_context.add(field);
                if (new_entry!=null)
                    _context.addToRefSet(new_entry);
            }


        }

        if (p>=0)
        {
            int e=buffer.position();
            LOG.debug("encoded '{}' by {} to '{}'",field,encoding,TypeUtil.toHexString(buffer.array(),buffer.arrayOffset()+p,e-p));
        }
    }
}
