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

import java.nio.ByteBuffer;

import org.eclipse.jetty.hpack.HpackContext.Entry;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.ByteBufferPool;

public class HpackEncoder
{   
    private final ByteBufferPool _byteBufferPool;
    private final HpackContext _context;

    public HpackEncoder(ByteBufferPool byteBufferPool)
    {
        this(byteBufferPool,4096);
    }
    
    public HpackEncoder(ByteBufferPool byteBufferPool,int maxHeaderTableSize)
    {
        this._byteBufferPool = byteBufferPool;
        _context=new HpackContext(maxHeaderTableSize);
    }
    
    public ByteBufferPool.Lease encode(HttpFields fields)
    {
        return new ByteBufferPool.Lease(_byteBufferPool);
    }
    
    
    private boolean encode(ByteBuffer buffer, HttpField field)
    {
        // Is there an entry for the field?
        Entry entry = _context.get(field);
        
        if (entry!=null)
        {
            // if entry is already in the reference set, then nothing more to do.
            if (entry.isInReferenceSet())
                return true;
            
            // Is this as static field
            if (entry.isStatic())
            {
                // TODO Policy decision to make!
                // Should we add to reference set or just always send as indexed?
                // Let's always send as indexed to reduce churn in header table!
                // BUGGER! Can't do that.  Oh well all the static fields have small values, so
                // lets send as literal header, indexed name.
                // We don't need never indexed because the cookie fields are name only and we can
                // huffman encode the value for the same reason. 
                               
                // Add the token
                buffer.put((byte)0x00);
                // Add the name index
                NBitInteger.encode(buffer,4,_context.index(entry));
                // Add the value
                buffer.put(entry.getStaticHuffmanValue());
                
            }
        }
    }
    
    
    
}
