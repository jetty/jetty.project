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


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.jetty.hpack.HpackContext.Entry;
import org.eclipse.jetty.http.HttpField;
import org.junit.Assert;
import org.junit.Test;


/* ------------------------------------------------------------ */
/**
 */
public class HpackContextTest
{

    @Test
    public void testStaticName()
    {
        HpackContext ctx = new HpackContext(4096);
        Entry entry=ctx.getNameEntry(":method");
        assertEquals(":method",entry.getHttpField().getName());
        Assert.assertTrue(entry.isStatic());
    }
    
    @Test
    public void testEmptyAdd()
    {
        HpackContext ctx = new HpackContext(0);
        HttpField field = new HttpField("foo","bar");
        Assert.assertNull(ctx.add(field));
    }
    
    @Test
    public void testTooBigAdd()
    {
        HpackContext ctx = new HpackContext(37);
        HttpField field = new HttpField("foo","bar");
        Assert.assertNull(ctx.add(field));
    }
    
    @Test
    public void testJustRight()
    {
        HpackContext ctx = new HpackContext(38);
        HttpField field = new HttpField("foo","bar");
        Assert.assertNotNull(ctx.add(field));
    }
    
    @Test
    public void testEvictOne()
    {
        HpackContext ctx = new HpackContext(38);
        HttpField field0 = new HttpField("foo","bar");
        
        assertEquals(field0,ctx.add(field0).getHttpField());
        assertEquals(field0,ctx.getNameEntry("foo").getHttpField());
        
        HttpField field1 = new HttpField("xxx","yyy");
        assertEquals(field1,ctx.add(field1).getHttpField());

        assertNull(ctx.get(field0));
        assertNull(ctx.getNameEntry("foo"));
        assertEquals(field1,ctx.get(field1).getHttpField());
        assertEquals(field1,ctx.getNameEntry("xxx").getHttpField());
        
    }
    
    @Test
    public void testGetAddStatic()
    {
        HpackContext ctx = new HpackContext(4096);

        // Look for the field.  Should find static version.
        HttpField methodGet = new HttpField(":method","GET");
        assertEquals(methodGet,ctx.get(methodGet).getHttpField());
        assertTrue(ctx.get(methodGet).isStatic());
        
        // Add static version to header table
        Entry e0=ctx.add(ctx.get(methodGet).getHttpField());
        
        // Look again and should see dynamic version
        assertEquals(methodGet,ctx.get(methodGet).getHttpField());
        assertFalse(methodGet==ctx.get(methodGet).getHttpField());
        assertFalse(ctx.get(methodGet).isStatic());
        
        // Duplicates allows
        Entry e1=ctx.add(ctx.get(methodGet).getHttpField());
        
        // Look again and should see dynamic version
        assertEquals(methodGet,ctx.get(methodGet).getHttpField());
        assertFalse(methodGet==ctx.get(methodGet).getHttpField());
        assertFalse(ctx.get(methodGet).isStatic());
        assertFalse(e0==e1);
    }

}
