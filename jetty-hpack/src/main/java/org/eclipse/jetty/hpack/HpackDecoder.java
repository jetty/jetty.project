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

import org.eclipse.jetty.http.HttpField;


/* ------------------------------------------------------------ */
/**
 * This is not thread safe. May only be called by 1 thread at a time
 */
public class HpackDecoder
{
    public interface Listener
    {
        void emit(HttpField field);
        void endHeaders();
    }
    
    public HpackDecoder(Listener listenr)
    {
        
    }
    
    public void parse(ByteBuffer buffer)
    {
        
    }

}
