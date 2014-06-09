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

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http2.hpack.HpackContext.Entry;


/* ------------------------------------------------------------ */
/**
 * This is not thread safe. May only be called by 1 thread at a time
 */
public class HpackDecoder
{
    
    private final HpackContext _context;
    private final MetaDataBuilder _builder = new MetaDataBuilder();

    public HpackDecoder()
    {
        this(4096);
    }
    
    public HpackDecoder(int maxHeaderTableSize)
    {
        _context=new HpackContext(maxHeaderTableSize);
    }

    
    public MetaData decode(ByteBuffer buffer)
    {
                
        while(buffer.hasRemaining())
        {
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
                    // emit field
                    _builder.emit(entry.getHttpField());
                    
                    // copy and add to reference set if there is room
                    Entry new_entry = _context.add(entry.getHttpField());
                    if (new_entry!=null)
                        _context.addToRefSet(new_entry);
                }
                else
                {
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
                        boolean huffman = (buffer.get()&0x80)==0x80;
                        int length = NBitInteger.decode(buffer,7);
                        if (huffman)
                            name=Huffman.decode(buffer,length);
                        else
                            name=toASCIIString(buffer,length);
                        header=HttpHeader.CACHE.get(name);
                    }
                    
                    // decode the value
                    boolean huffman = (buffer.get()&0x80)==0x80;
                    int length = NBitInteger.decode(buffer,7);
                    if (huffman)
                        value=Huffman.decode(buffer,length);
                    else
                        value=toASCIIString(buffer,length);
                    
                    // Make the new field
                    HttpField field = new HttpField(header,name,value);
                    
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
                    _context.resize(size);
                }
                else if (f==3)
                {
                    // clear reference set
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
