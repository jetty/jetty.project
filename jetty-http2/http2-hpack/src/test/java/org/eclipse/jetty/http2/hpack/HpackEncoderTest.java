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
import java.util.HashSet;

import org.eclipse.jetty.http.FinalMetaData;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.hpack.HpackContext.Entry;
import org.eclipse.jetty.util.BufferUtil;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

import static org.junit.Assert.assertThat;


/* ------------------------------------------------------------ */
/**
 */
public class HpackEncoderTest
{
    @Test
    public void testUnknownFieldsContextManagement()
    {
        HpackEncoder encoder = new HpackEncoder(38*5);
        HttpFields fields = new HttpFields();
        

        HttpField[] field = 
        {
           new HttpField("fo0","b0r"),
           new HttpField("fo1","b1r"),
           new HttpField("fo2","b2r"),
           new HttpField("fo3","b3r"),
           new HttpField("fo4","b4r"),
           new HttpField("fo5","b5r"),
           new HttpField("fo6","b6r"),
           new HttpField("fo7","b7r"),
           new HttpField("fo8","b8r"),
           new HttpField("fo9","b9r"),
           new HttpField("foA","bAr"),
        };
        
        // Add 4 entries
        for (int i=0;i<=3;i++)  
            fields.add(field[i]);
        
        // encode them
        ByteBuffer buffer = BufferUtil.allocate(4096);
        int pos = BufferUtil.flipToFill(buffer);
        encoder.encode(buffer,new FinalMetaData(HttpVersion.HTTP_2,fields));
        BufferUtil.flipToFlush(buffer,pos);
        
        // something was encoded!
        assertThat(buffer.remaining(),Matchers.greaterThan(0));
        
        // All are in the header table
        Assert.assertEquals(4,encoder.getContext().size());
        
        // All are in the reference set
        HashSet<HttpField> refSet = new HashSet<>();
        for (Entry entry : encoder.getContext().getReferenceSet())
            refSet.add(entry.getHttpField());
        Assert.assertEquals(4,refSet.size());
        for (int i=0;i<=3;i++) 
            Assert.assertTrue(refSet.contains(field[i]));
        
        // encode exact same fields again!
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer,new FinalMetaData(HttpVersion.HTTP_2,fields));
        BufferUtil.flipToFlush(buffer,0);

        // nothing should be encoded!
        assertThat(buffer.remaining(),Matchers.is(0));
        
        // All are in the header table
        Assert.assertEquals(4,encoder.getContext().size());
        
        // All are in the reference set
        refSet.clear();
        for (Entry entry : encoder.getContext().getReferenceSet())
            refSet.add(entry.getHttpField());
        Assert.assertEquals(4,refSet.size());
        for (int i=0;i<=3;i++) 
            Assert.assertTrue(refSet.contains(field[i]));

        // Add 4 more fields
        for (int i=4;i<=7;i++)  
            fields.add(field[i]);
        
        // encode
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer,new FinalMetaData(HttpVersion.HTTP_2,fields));
        BufferUtil.flipToFlush(buffer,0);

        // something was encoded!
        assertThat(buffer.remaining(),Matchers.greaterThan(0));

        // max header table size reached
        Assert.assertEquals(5,encoder.getContext().size());
        
        // last 5 in reference set
        refSet.clear();
        for (Entry entry : encoder.getContext().getReferenceSet())
            refSet.add(entry.getHttpField());
        Assert.assertEquals(5,refSet.size());
        for (int i=3;i<=7;i++) 
            Assert.assertTrue(refSet.contains(field[i]));
        
        
        // remove some fields
        for (int i=0;i<=7;i+=2)  
            fields.remove(field[i].getName());

        // encode
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer,new FinalMetaData(HttpVersion.HTTP_2,fields));
        BufferUtil.flipToFlush(buffer,0);
        
        // something was encoded!
        assertThat(buffer.remaining(),Matchers.greaterThan(0));

        // max header table size reached
        Assert.assertEquals(5,encoder.getContext().size());

        // last 5 in reference set
        refSet.clear();
        for (Entry entry : encoder.getContext().getReferenceSet())
            refSet.add(entry.getHttpField());
        Assert.assertEquals(4,refSet.size());
        for (int i=0;i<=7;i++)
        {
            if (i%2==1)
                Assert.assertTrue(refSet.contains(field[i]));
            else
                Assert.assertFalse(refSet.contains(field[i]));
        }

        // remove another fields
        fields.remove(field[1].getName());

        // encode
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer,new FinalMetaData(HttpVersion.HTTP_2,fields));
        BufferUtil.flipToFlush(buffer,0);
        
        // something was encoded!
        assertThat(buffer.remaining(),Matchers.greaterThan(0));

        // max header table size reached
        Assert.assertEquals(5,encoder.getContext().size());

        // last 5 in reference set
        refSet.clear();
        for (Entry entry : encoder.getContext().getReferenceSet())
            refSet.add(entry.getHttpField());
        Assert.assertEquals(3,refSet.size());
        for (int i=2;i<=7;i++)
        {
            if (i%2==1)
                Assert.assertTrue(refSet.contains(field[i]));
            else
                Assert.assertFalse(refSet.contains(field[i]));
        }
        
        // re add the field

        fields.add(field[1]);

        // encode
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer,new FinalMetaData(HttpVersion.HTTP_2,fields));
        BufferUtil.flipToFlush(buffer,0);
        
        // something was encoded!
        assertThat(buffer.remaining(),Matchers.greaterThan(0));

        // max header table size reached
        Assert.assertEquals(5,encoder.getContext().size());

        // last 5 in reference set
        refSet.clear();
        for (Entry entry : encoder.getContext().getReferenceSet())
            refSet.add(entry.getHttpField());
        Assert.assertEquals(4,refSet.size());
        for (int i=0;i<=7;i++)
        {
            if (i%2==1)
                Assert.assertTrue(refSet.contains(field[i]));
            else
                Assert.assertFalse(refSet.contains(field[i]));
        }
    }

    @Test
    public void testDoNotReferenceStatics()
    {
        HpackEncoder encoder = new HpackEncoder(38*5);
        ByteBuffer buffer = BufferUtil.allocate(4096);
        
        HttpFields fields = new HttpFields();
        fields.put(":method","POST");

        // encode
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer,new FinalMetaData(HttpVersion.HTTP_2,fields));
        BufferUtil.flipToFlush(buffer,0);
        
        // something was encoded!
        assertThat(buffer.remaining(),Matchers.greaterThan(0));
        
        // empty header table
        Assert.assertEquals(0,encoder.getContext().size());
    }

    @Test
    public void testNeverIndexSetCookie()
    {
        HpackEncoder encoder = new HpackEncoder(38*5);
        ByteBuffer buffer = BufferUtil.allocate(4096);
        
        HttpFields fields = new HttpFields();
        fields.put("set-cookie","some cookie value");

        // encode
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer,new FinalMetaData(HttpVersion.HTTP_2,fields));
        BufferUtil.flipToFlush(buffer,0);
        
        // something was encoded!
        assertThat(buffer.remaining(),Matchers.greaterThan(0));
        
        // empty header table
        Assert.assertEquals(0,encoder.getContext().size());
        

        // encode again
        BufferUtil.clearToFill(buffer);
        encoder.encode(buffer,new FinalMetaData(HttpVersion.HTTP_2,fields));
        BufferUtil.flipToFlush(buffer,0);
        
        // something was encoded!
        assertThat(buffer.remaining(),Matchers.greaterThan(0));
        
        // empty header table
        Assert.assertEquals(0,encoder.getContext().size());
        
    }
    

    

}
