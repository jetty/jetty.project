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

package org.eclipse.jetty.websocket;

import java.io.IOException;
import java.util.Map;

public class FragmentExtension extends AbstractExtension
{
    private int _maxLength=-1;
    private int _minFragments=1;
    
    public FragmentExtension()
    {
        super("fragment");
    }

    @Override
    public boolean init(Map<String, String> parameters)
    {
        if(super.init(parameters))
        {
            _maxLength=getInitParameter("maxLength",_maxLength);
            _minFragments=getInitParameter("minFragments",_minFragments);
            return true;
        }
        return false;
    }

    @Override
    public void addFrame(byte flags, byte opcode, byte[] content, int offset, int length) throws IOException
    {
        if (getConnection().isControl(opcode))
        {
            super.addFrame(flags,opcode,content,offset,length);
            return;
        }
        
        int fragments=1;
        
        while (_maxLength>0 && length>_maxLength)
        {
            fragments++;
            super.addFrame((byte)(flags&~getConnection().finMask()),opcode,content,offset,_maxLength);
            length-=_maxLength;
            offset+=_maxLength;
            opcode=getConnection().continuationOpcode();
        }
        
        while (fragments<_minFragments)
        {
            int frag=length/2;
            fragments++;
            super.addFrame((byte)(flags&0x7),opcode,content,offset,frag);
            length-=frag;
            offset+=frag;
            opcode=getConnection().continuationOpcode();
        }

        super.addFrame((byte)(flags|getConnection().finMask()),opcode,content,offset,length);
    }
    
    
}
