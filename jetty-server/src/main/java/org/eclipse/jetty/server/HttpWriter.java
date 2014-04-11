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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;

import org.eclipse.jetty.http.AbstractGenerator;
import org.eclipse.jetty.util.ByteArrayOutputStream2;
import org.eclipse.jetty.util.StringUtil;

/** OutputWriter.
 * A writer that can wrap a {@link HttpOutput} stream and provide
 * character encodings.
 *
 * The UTF-8 encoding is done by this class and no additional 
 * buffers or Writers are used.
 * The UTF-8 code was inspired by http://javolution.org
 */
public class HttpWriter extends Writer
{
    public static final int MAX_OUTPUT_CHARS = 512; 
    
    private static final int WRITE_CONV = 0;
    private static final int WRITE_ISO1 = 1;
    private static final int WRITE_UTF8 = 2;
    
    final HttpOutput _out;
    final AbstractGenerator _generator;
    int _writeMode;
    int _surrogate;

    /* ------------------------------------------------------------ */
    public HttpWriter(HttpOutput out)
    {
        _out=out;
        _generator=_out._generator;
        _surrogate=0; // AS lastUTF16CodePoint
    }

    /* ------------------------------------------------------------ */
    public void setCharacterEncoding(String encoding)
    {
        if (encoding == null || StringUtil.__ISO_8859_1.equalsIgnoreCase(encoding))
        {
            _writeMode = WRITE_ISO1;
        }
        else if (StringUtil.__UTF8.equalsIgnoreCase(encoding))
        {
            _writeMode = WRITE_UTF8;
        }
        else
        {
            _writeMode = WRITE_CONV;
            if (_out._characterEncoding == null || !_out._characterEncoding.equalsIgnoreCase(encoding))
                _out._converter = null; // Set lazily in getConverter()
        }
        
        _out._characterEncoding = encoding;
        if (_out._bytes==null)
            _out._bytes = new ByteArrayOutputStream2(MAX_OUTPUT_CHARS);
    }

    /* ------------------------------------------------------------ */
    @Override
    public void close() throws IOException
    {
        _out.close();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void flush() throws IOException
    {
        _out.flush();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void write (String s,int offset, int length) throws IOException
    {   
        while (length > MAX_OUTPUT_CHARS)
        {
            write(s, offset, MAX_OUTPUT_CHARS);
            offset += MAX_OUTPUT_CHARS;
            length -= MAX_OUTPUT_CHARS;
        }

        if (_out._chars==null)
        {
            _out._chars = new char[MAX_OUTPUT_CHARS]; 
        }
        char[] chars = _out._chars;
        s.getChars(offset, offset + length, chars, 0);
        write(chars, 0, length);
    }

    /* ------------------------------------------------------------ */
    @Override
    public void write (char[] s,int offset, int length) throws IOException
    {              
        HttpOutput out = _out; 
        
        while (length > 0)
        {  
            out._bytes.reset();
            int chars = length>MAX_OUTPUT_CHARS?MAX_OUTPUT_CHARS:length;
            
            switch (_writeMode)
            {
                case WRITE_CONV:
                {
                    Writer converter=getConverter();
                    converter.write(s, offset, chars);
                    converter.flush();
                }
                break;

                case WRITE_ISO1:
                {
                    byte[] buffer=out._bytes.getBuf();
                    int bytes=out._bytes.getCount();
                    
                    if (chars>buffer.length-bytes)
                        chars=buffer.length-bytes;

                    for (int i = 0; i < chars; i++)
                    {
                        int c = s[offset+i];
                        buffer[bytes++]=(byte)(c<256?c:'?'); // ISO-1 and UTF-8 match for 0 - 255
                    }
                    if (bytes>=0)
                        out._bytes.setCount(bytes);

                    break;
                }

                case WRITE_UTF8:
                {
                    byte[] buffer=out._bytes.getBuf();
                    int bytes=out._bytes.getCount();

                    if (bytes+chars>buffer.length)
                        chars=buffer.length-bytes;

                    for (int i = 0; i < chars; i++)
                    {
                        int code = s[offset+i];

                        // Do we already have a surrogate?
                        if(_surrogate==0)
                        {
                            // No - is this char code a surrogate?
                            if(Character.isHighSurrogate((char)code))
                            {
                                _surrogate=code; // UCS-?
                                continue;
                            }                            
                        }
                        // else handle a low surrogate
                        else if(Character.isLowSurrogate((char)code))
                        {
                            code = Character.toCodePoint((char)_surrogate, (char)code); // UCS-4
                        }
                        // else UCS-2
                        else
                        {
                            code=_surrogate; // UCS-2
                            _surrogate=0; // USED
                            i--;
                        }

                        if ((code & 0xffffff80) == 0) 
                        {
                            // 1b
                            if (bytes>=buffer.length)
                            {
                                chars=i;
                                break;
                            }
                            buffer[bytes++]=(byte)(code);
                        }
                        else
                        {
                            if((code&0xfffff800)==0)
                            {
                                // 2b
                                if (bytes+2>buffer.length)
                                {
                                    chars=i;
                                    break;
                                }
                                buffer[bytes++]=(byte)(0xc0|(code>>6));
                                buffer[bytes++]=(byte)(0x80|(code&0x3f));
                            }
                            else if((code&0xffff0000)==0)
                            {
                                // 3b
                                if (bytes+3>buffer.length)
                                {
                                    chars=i;
                                    break;
                                }
                                buffer[bytes++]=(byte)(0xe0|(code>>12));
                                buffer[bytes++]=(byte)(0x80|((code>>6)&0x3f));
                                buffer[bytes++]=(byte)(0x80|(code&0x3f));
                            }
                            else if((code&0xff200000)==0)
                            {
                                // 4b
                                if (bytes+4>buffer.length)
                                {
                                    chars=i;
                                    break;
                                }
                                buffer[bytes++]=(byte)(0xf0|(code>>18));
                                buffer[bytes++]=(byte)(0x80|((code>>12)&0x3f));
                                buffer[bytes++]=(byte)(0x80|((code>>6)&0x3f));
                                buffer[bytes++]=(byte)(0x80|(code&0x3f));
                            }
                            else if((code&0xf4000000)==0)
                            {
                                // 5b
                                if (bytes+5>buffer.length)
                                {
                                    chars=i;
                                    break;
                                }
                                buffer[bytes++]=(byte)(0xf8|(code>>24));
                                buffer[bytes++]=(byte)(0x80|((code>>18)&0x3f));
                                buffer[bytes++]=(byte)(0x80|((code>>12)&0x3f));
                                buffer[bytes++]=(byte)(0x80|((code>>6)&0x3f));
                                buffer[bytes++]=(byte)(0x80|(code&0x3f));
                            }
                            else if((code&0x80000000)==0)
                            {
                                // 6b
                                if (bytes+6>buffer.length)
                                {
                                    chars=i;
                                    break;
                                }
                                buffer[bytes++]=(byte)(0xfc|(code>>30));
                                buffer[bytes++]=(byte)(0x80|((code>>24)&0x3f));
                                buffer[bytes++]=(byte)(0x80|((code>>18)&0x3f));
                                buffer[bytes++]=(byte)(0x80|((code>>12)&0x3f));
                                buffer[bytes++]=(byte)(0x80|((code>>6)&0x3f));
                                buffer[bytes++]=(byte)(0x80|(code&0x3f));
                            }
                            else
                            {
                                buffer[bytes++]=(byte)('?');
                            } 

                            _surrogate=0; // USED

                            if (bytes==buffer.length)
                            {
                                chars=i+1;
                                break;
                            }
                        }
                    }
                    out._bytes.setCount(bytes);
                    break;
                }
                default:
                    throw new IllegalStateException();
            }
            
            out._bytes.writeTo(out);
            length-=chars;
            offset+=chars;
        }
    }
    
    
    /* ------------------------------------------------------------ */
    private Writer getConverter() throws IOException
    {
        if (_out._converter == null)
            _out._converter = new OutputStreamWriter(_out._bytes, _out._characterEncoding);
        return _out._converter;
    }   
}
