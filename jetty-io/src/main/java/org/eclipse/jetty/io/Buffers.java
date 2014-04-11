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

package org.eclipse.jetty.io;


/* ------------------------------------------------------------ */
/** BufferSource.
 * Represents a pool or other source of buffers and abstracts the creation
 * of specific types of buffers (eg NIO).   The concept of big and little buffers
 * is supported, but these terms have no absolute meaning and must be determined by context.
 * 
 */
public interface Buffers
{
    enum Type { BYTE_ARRAY, DIRECT, INDIRECT } ;
    
    Buffer getHeader();
    Buffer getBuffer();
    Buffer getBuffer(int size);
    
    void returnBuffer(Buffer buffer);
}
