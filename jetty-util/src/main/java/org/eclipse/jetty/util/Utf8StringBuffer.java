// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import java.io.IOException;

/* ------------------------------------------------------------ */
/** UTF-8 StringBuffer.
 *
 * This class wraps a standard {@link java.lang.StringBuffer} and provides methods to append 
 * UTF-8 encoded bytes, that are converted into characters.
 * 
 * This class is stateful and up to 6  calls to {@link #append(byte)} may be needed before 
 * state a character is appended to the string buffer.
 * 
 * The UTF-8 decoding is done by this class and no additional buffers or Readers are used.
 * The UTF-8 code was inspired by http://javolution.org
 * 
 * This class is not synchronised and should probably be called Utf8StringBuilder
 */
public class Utf8StringBuffer extends Utf8Appendable 
{
    final StringBuffer _buffer;
    
    public Utf8StringBuffer()
    {
        super(new StringBuffer());
        _buffer=(StringBuffer)_appendable;
    }
    
    public Utf8StringBuffer(int capacity)
    {
        super(new StringBuffer(capacity));
        _buffer=(StringBuffer)_appendable;
    }

    public int length()
    {
        return _buffer.length();
    }
    
    public void reset()
    {
        _buffer.setLength(0);
        _expectedContinuationBytes=0;
        _codePoint=0;
    }
    
    public StringBuffer getStringBuffer()
    {
        if (_expectedContinuationBytes!=0)
            throw new NotUtf8Exception();
        return _buffer;
    }
    
    @Override
    public String toString()
    {
        if (_expectedContinuationBytes!=0)
            throw new NotUtf8Exception();
        return _buffer.toString();
    }
}
