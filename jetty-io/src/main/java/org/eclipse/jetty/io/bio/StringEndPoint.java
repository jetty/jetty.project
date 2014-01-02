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

package org.eclipse.jetty.io.bio;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.eclipse.jetty.util.StringUtil;

/**
 * 
 *
 * To change the template for this generated type comment go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
public class StringEndPoint extends StreamEndPoint
{
    String _encoding=StringUtil.__UTF8;
    ByteArrayInputStream _bin = new ByteArrayInputStream(new byte[0]);
    ByteArrayOutputStream _bout = new ByteArrayOutputStream();
    
    public StringEndPoint()
    {
        super(null,null);
        _in=_bin;
        _out=_bout;
    }
    
    public StringEndPoint(String encoding)
    {
        this();
        if (encoding!=null)
            _encoding=encoding;
    }
    
    public void setInput(String s) 
    {
        try
        {
            byte[] bytes = s.getBytes(_encoding);
            _bin=new ByteArrayInputStream(bytes);
            _in=_bin;
            _bout = new ByteArrayOutputStream();
            _out=_bout;
            _ishut=false;
            _oshut=false;
        }
        catch(Exception e)
        {
            throw new IllegalStateException(e.toString());
        }
    }
    
    public String getOutput() 
    {
        try
        {
            String s = new String(_bout.toByteArray(),_encoding);
            _bout.reset();
      	  return s;
        }
        catch(final Exception e)
        {
            throw new IllegalStateException(_encoding)
            {
                {initCause(e);}
            };
        }
    }

    /**
     * @return <code>true</code> if there are bytes remaining to be read from the encoded input
     */
    public boolean hasMore()
    {
        return _bin.available()>0;
    }   
}
