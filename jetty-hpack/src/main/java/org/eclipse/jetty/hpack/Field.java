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


package org.eclipse.jetty.hpack;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;

/* ------------------------------------------------------------ */
/**
 */
public class Field extends HttpField
{
    // final ByteBuffer _nameLiteral;
    // final ByteBuffer _nameHuffman;
    private final NameKey _nameKey=new NameKey();
    
    public Field(String name,String value)
    {
        super(HttpHeader.CACHE.get(name),name,value);
        
        // Generate a non huffman literal field
        /*
        _nameLiteral=BufferUtil.allocate(1+NBitInteger.octectsNeeded(7,name.length())+name.length());
        BufferUtil.flipToFill(_nameLiteral);
        _nameLiteral.put((byte)0x00);
        NBitInteger.encode(_nameLiteral,7,name.length());
        for (int i=0;i<name.length();i++)
        {
            char c=name.charAt(0);
            if (c<32||c>126)
                throw new IllegalArgumentException();
            _nameLiteral.array()[_nameLiteral.position()+i]=(byte)c;
        }
        _nameLiteral.position(_nameLiteral.limit());
        BufferUtil.flipToFlush(_nameLiteral,0);

        // Generate a huffman literal field
        int h=Huffman.octetsNeeded(name);
        _nameHuffman=BufferUtil.allocate(1+NBitInteger.octectsNeeded(7,h)+h);
        BufferUtil.flipToFill(_nameHuffman);
        _nameHuffman.put((byte)0x80);
        NBitInteger.encode(_nameHuffman,7,name.length());
        for (int i=0;i<name.length();i++)
        {
            char c=name.charAt(0);
            if (c<32||c>126)
                throw new IllegalArgumentException();
            _nameHuffman.array()[_nameHuffman.position()+i]=(byte)c;
        }
        _nameHuffman.position(_nameHuffman.limit());
        BufferUtil.flipToFlush(_nameHuffman,0);
        */
    }
    
    public NameKey getNameKey()
    {
        return _nameKey;
    }
    
    public class NameKey
    {
        
        public Field getField()
        {
            return Field.this;
        }
        
        @Override 
        public String toString()
        {
            return getName();
        }
    };
}
    
    
    
