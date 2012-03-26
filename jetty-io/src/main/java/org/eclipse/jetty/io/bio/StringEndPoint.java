// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.io.bio;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;

/**
 * 
 *
 * To change the template for this generated type comment go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
public class StringEndPoint extends ByteArrayEndPoint
{
    Charset _charset=StringUtil.__UTF8_CHARSET;
    
    public StringEndPoint()
    {
    }
    
    public StringEndPoint(String encoding)
    {
        this();
        if (encoding!=null)
            _charset=Charset.forName(encoding);
    }
    
    public void setInput(String s) 
    {
        try
        {
            super.setIn(BufferUtil.toBuffer(s,_charset));
        }
        catch(Exception e)
        {
            throw new IllegalStateException(e.toString());
        }
    }
    
    public String getOutput() 
    {
        ByteBuffer b = getOut();
        b.flip();
        String s=BufferUtil.toString(b,_charset);
        b.clear();
        return s;
    }

    /**
     * @return <code>true</code> if there are bytes remaining to be read from the encoded input
     */
    public boolean hasMore()
    {
        return getOut().position()>0;
    }   
}
