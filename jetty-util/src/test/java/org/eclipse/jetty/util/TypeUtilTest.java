//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import junit.framework.Assert;

import org.junit.Test;

public class TypeUtilTest
{

    @Test
    public void testToHexInt() throws Exception
    {
        StringBuilder b = new StringBuilder();
        
        b.setLength(0);
        TypeUtil.toHex((int)0,b);
        Assert.assertEquals("00000000",b.toString());
        
        b.setLength(0);
        TypeUtil.toHex(Integer.MAX_VALUE,b);
        Assert.assertEquals("7FFFFFFF",b.toString());
        
        b.setLength(0);
        TypeUtil.toHex(Integer.MIN_VALUE,b);
        Assert.assertEquals("80000000",b.toString());
        
        b.setLength(0);
        TypeUtil.toHex(0x12345678,b);
        Assert.assertEquals("12345678",b.toString());
        
        b.setLength(0);
        TypeUtil.toHex(0x9abcdef0,b);
        Assert.assertEquals("9ABCDEF0",b.toString());
    }

    @Test
    public void testToHexLong() throws Exception
    {
        StringBuilder b = new StringBuilder();
        
        b.setLength(0);
        TypeUtil.toHex((long)0,b);
        Assert.assertEquals("0000000000000000",b.toString());
        
        b.setLength(0);
        TypeUtil.toHex(Long.MAX_VALUE,b);
        Assert.assertEquals("7FFFFFFFFFFFFFFF",b.toString());
        
        b.setLength(0);
        TypeUtil.toHex(Long.MIN_VALUE,b);
        Assert.assertEquals("8000000000000000",b.toString());
        
        b.setLength(0);
        TypeUtil.toHex(0x123456789abcdef0L,b);
        Assert.assertEquals("123456789ABCDEF0",b.toString());
    }

}
