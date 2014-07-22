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

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.hpack.HpackContext.Entry;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/**
 * This is not thread safe. May only be called by 1 thread at a time
 */
public class HpackDecoder
{
    public static final Logger LOG = Log.getLogger(HpackDecoder.class);
    
    private final HpackContext _context;
    private final MetaDataBuilder _builder;
    private int _localMaxHeaderTableSize;

    public HpackDecoder(int localMaxHeaderTableSize, int maxHeaderSize)
    {
        _context=new HpackContext(localMaxHeaderTableSize);
        _localMaxHeaderTableSize=localMaxHeaderTableSize;
        _builder = new MetaDataBuilder(maxHeaderSize);
    }
    
    public void setLocalMaxHeaderTableSize(int localMaxHeaderTableSize)
    {
        _localMaxHeaderTableSize=localMaxHeaderTableSize; 
    }
    
    public MetaData decode(ByteBuffer buffer)
    {       
        if (LOG.isDebugEnabled())
            LOG.debug(String.format("CtxTbl[%x] decoding %d octets",_context.hashCode(),buffer.remaining()));
        
        // If the buffer is big, don't even think about decoding it
        if (buffer.remaining()>_builder.getMaxSize())
            throw new BadMessageException(HttpStatus.REQUEST_ENTITY_TOO_LARGE_413,"Header frame size "+buffer.remaining()+">"+_builder.getMaxSize());
            
        
        while(buffer.hasRemaining())
        {
            if (LOG.isDebugEnabled())
            {                
                int l=Math.min(buffer.remaining(),16);
                LOG.debug("decode  "+TypeUtil.toHexString(buffer.array(),buffer.arrayOffset()+buffer.position(),l)+(l<buffer.remaining()?"...":""));
            }
            
            byte b = buffer.get();
            if (b<0)
            {
                // indexed
                int index = NBitInteger.decode(buffer,7);
                Entry entry=_context.get(index);
                if (entry.isInReferenceSet())
                    _context.get(index).removeFromRefSet();
                else if (entry.isStatic())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("decode IdxStatic {}",entry);
                    // emit field
                    _builder.emit(entry.getHttpField());
                    
                    // copy and add to reference set if there is room
                    Entry new_entry = _context.add(entry.getHttpField());
                    if (new_entry!=null)
                        _context.addToRefSet(new_entry);
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("decode Idx {}",entry);
                    // emit
                    _builder.emit(entry.getHttpField());
                    // add to reference set
                    _context.addToRefSet(entry);
                }
            }
            else 
            {
                // look at the first nibble in detail
                int f=(b&0xF0)>>4;
                String name;
                HttpHeader header;
                String value;
                
                if (f<=1 || f>=4)
                {
                    // literal 
                    boolean indexed=f>=4;
                    int bits=indexed?6:4;
                    boolean huffmanName=false;
                    
                    // decode the name
                    int name_index=NBitInteger.decode(buffer,bits);
                    if (name_index>0)
                    {
                        Entry name_entry=_context.get(name_index);
                        name=name_entry.getHttpField().getName();
                        header=name_entry.getHttpField().getHeader();
                    }
                    else
                    {
                        huffmanName = (buffer.get()&0x80)==0x80;
                        int length = NBitInteger.decode(buffer,7);
                        _builder.checkSize(length,huffmanName);
                        if (huffmanName)
                            name=Huffman.decode(buffer,length);
                        else
                            name=toASCIIString(buffer,length);
                        for (int i=0;i<name.length();i++)
                        {
                            char c=name.charAt(i);
                            if (c>='A'&&c<='Z')
                            {
                                throw new BadMessageException(400,"Uppercase header name");
                            }
                        }
                        header=HttpHeader.CACHE.get(name);
                    }
                    
                    // decode the value
                    boolean huffmanValue = (buffer.get()&0x80)==0x80;
                    int length = NBitInteger.decode(buffer,7);
                    _builder.checkSize(length,huffmanValue);
                    if (huffmanValue)
                        value=Huffman.decode(buffer,length);
                    else
                        value=toASCIIString(buffer,length);
                    
                    // Make the new field
                    HttpField field;
                    switch(name)
                    {
                        case ":method":
                            HttpMethod method=HttpMethod.CACHE.get(value);
                            if (method!=null)
                                field = new StaticValueHttpField(header,name,method.asString(),method);
                            else
                                field = new AuthorityHttpField(value);    
                            break;
                            
                        case ":status":
                            Integer code = Integer.valueOf(value);
                            field = new StaticValueHttpField(header,name,value,code);
                            break;
                            
                        case ":scheme":
                            HttpScheme scheme=HttpScheme.CACHE.get(value);
                            if (scheme!=null)
                                field = new StaticValueHttpField(header,name,scheme.asString(),scheme);
                            else
                                field = new AuthorityHttpField(value);
                            break;
                            
                        case ":authority":
                            field = new AuthorityHttpField(value);
                            break;
                            
                        case ":path":
                            // TODO is this needed
                            /*
                            if (indexed)
                                field = new StaticValueHttpField(header,name,value,new HttpURI(value));
                            else*/
                                field = new HttpField(header,name,value);
                            break;
                            
                        default:
                            field = new HttpField(header,name,value);
                            break;
                    }
                    
                    if (LOG.isDebugEnabled())
                        LOG.debug("decoded '"+field+"' by Lit"+(name_index>0?"IdxName":(huffmanName?"HuffName":"LitName"))+(huffmanValue?"HuffVal":"LitVal")+(indexed?"Idx":""));
                    
                    // emit the field
                    _builder.emit(field);
                    
                    // if indexed
                    if (indexed)
                    {
                        // add to header table
                        Entry new_entry=_context.add(field);
                        // and to ref set if there was room in header table
                        if (new_entry!=null)
                            _context.addToRefSet(new_entry);
                    }
                }
                else if (f==2)
                {
                    // change table size
                    int size = NBitInteger.decode(buffer,4);
                    if (LOG.isDebugEnabled())
                        LOG.debug("decode resize="+size);
                    if (size>_localMaxHeaderTableSize)
                        throw new IllegalArgumentException();
                    _context.resize(size);
                }
                else if (f==3)
                {
                    // clear reference set
                    if (LOG.isDebugEnabled())
                        LOG.debug("decode clear");
                    _context.clearReferenceSet();
                }   
            }
        }
        
        _context.emitUnusedReferences(_builder);
        return _builder.build();
    }

    public static String toASCIIString(ByteBuffer buffer,int length)
    {
        StringBuilder builder = new StringBuilder(length);
        int start=buffer.arrayOffset()+buffer.position();
        int end=start+length;
        buffer.position(end);
        byte[] array=buffer.array();
        for (int i=start;i<end;i++)
            builder.append((char)(0x7f&array[i]));
        return builder.toString();
    }
}
