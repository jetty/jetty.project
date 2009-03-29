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

package org.eclipse.jetty.io;

/* ------------------------------------------------------------ */
/** SimpleBuffers.
 * Simple implementation of Buffers holder.
 * 
 *
 */
public class SimpleBuffers implements Buffers
{   
    Buffer[] _buffers;
    
    /* ------------------------------------------------------------ */
    /**
     * 
     */
    public SimpleBuffers(Buffer[] buffers)
    {
        _buffers=buffers;
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.io.Buffers#getBuffer(boolean)
     */
    public Buffer getBuffer(int size)
    {
        if (_buffers!=null)
        {
            for (int i=0;i<_buffers.length;i++)
            {
                if (_buffers[i]!=null && _buffers[i].capacity()==size)
                {
                    Buffer b=_buffers[i];
                    _buffers[i]=null;
                    return b;
                }
            }
        }
        return new ByteArrayBuffer(size);
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.io.Buffers#returnBuffer(org.eclipse.io.Buffer)
     */
    public void returnBuffer(Buffer buffer)
    {
        buffer.clear();
        if (_buffers!=null)
        {
            for (int i=0;i<_buffers.length;i++)
            {
                if (_buffers[i]==null)
                    _buffers[i]=buffer;
            }
        }
    }
    

}
