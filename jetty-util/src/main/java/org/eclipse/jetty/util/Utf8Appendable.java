package org.eclipse.jetty.util;

import java.io.IOException;
import java.util.IllegalFormatCodePointException;

public abstract class Utf8Appendable
{
    private final char REPLACEMENT = '\ufffd';
    protected final Appendable _appendable;
    protected int _expectedContinuationBytes;
    protected int _codePoint;
    protected int _minCodePoint;

    public Utf8Appendable(Appendable appendable)
    {
        _appendable=appendable;
    }

    public abstract int length();
    
    public void append(byte b)
    {
        try
        {
            appendByte(b);
        }
        catch(IOException e)
        {
            throw new RuntimeException(e);
        }
    }
    
    public void append(byte[] b,int offset, int length)
    {
        try
        {
            int end=offset+length;
            for (int i=offset; i<end;i++)
                appendByte(b[i]);
        }
        catch(IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public boolean append(byte[] b,int offset, int length, int maxChars)
    {
        try
        {
            int end=offset+length;
            for (int i=offset; i<end;i++)
            {
                if (length()>maxChars)
                    return false;
                appendByte(b[i]);
            }
            return true;
        }
        catch(IOException e)
        {
            throw new RuntimeException(e);
        }
    }
    
    protected void appendByte(byte b) throws IOException
    {
        // Check for invalid bytes
        if (b==(byte)0xc0 || b==(byte)0xc1 || (int)b>=0xf5)
        {
            _appendable.append(REPLACEMENT);
            _expectedContinuationBytes=0;
            _codePoint=0;
            throw new NotUtf8Exception();
        }
        
        // Is it plain ASCII?
        if (b>=0)
        {
            // Were we expecting a continuation byte?
            if (_expectedContinuationBytes>0)
            {
                _appendable.append(REPLACEMENT);
                _expectedContinuationBytes=0;
                _codePoint=0;
                throw new NotUtf8Exception();
            }
            else
                _appendable.append((char)(0x7f&b));
        }
        // Else is this a start byte
        else if (_expectedContinuationBytes==0)
        {
            if ((b & 0xe0) == 0xc0)
            {
                //110xxxxx
                _expectedContinuationBytes=1;
                _codePoint=b&0x1f;
                _minCodePoint=0x80;
            }
            else if ((b & 0xf0) == 0xe0)
            {
                //1110xxxx
                _expectedContinuationBytes=2;
                _codePoint=b&0x0f;
                _minCodePoint=0x800;
            }
            else if ((b & 0xf8) == 0xf0)
            {
                //11110xxx
                _expectedContinuationBytes=3;
                _codePoint=b&0x07;
                _minCodePoint=0x10000;
            }
            else if ((b & 0xfc) == 0xf8)
            {
                //111110xx
                _expectedContinuationBytes=4;
                _codePoint=b&0x03;
                _minCodePoint=0x200000;
            }
            else if ((b & 0xfe) == 0xfc) 
            {
                //1111110x
                _expectedContinuationBytes=5;
                _codePoint=b&0x01;
                _minCodePoint=0x400000;
            }
            else
            {
                _appendable.append(REPLACEMENT);
                _expectedContinuationBytes=0;
                _codePoint=0;
                throw new NotUtf8Exception();
            }
        }
        // else is this a continuation character
        else if ((b&0xc0)==0x80)
        {
            // 10xxxxxx
            _codePoint=(_codePoint<<6)|(b&0x3f);
            
            // was that the last continuation?
            if (--_expectedContinuationBytes==0)
            {
                // If this a valid unicode point?
                if (_codePoint<_minCodePoint || (_codePoint>=0xD800 && _codePoint<=0xDFFF))
                {
                    _appendable.append(REPLACEMENT);
                    _expectedContinuationBytes=0;
                    _codePoint=0;
                    throw new NotUtf8Exception();
                }

                _minCodePoint=0;
                char[] chars = Character.toChars(_codePoint);
                for (char c : chars)
                    _appendable.append(c);
            }
        }
        // Else this is not a continuation character
        else
        {
            // ! 10xxxxxx
            _appendable.append(REPLACEMENT);
            _expectedContinuationBytes=0;
            _codePoint=0;
            throw new NotUtf8Exception();
        }
    }


    public static class NotUtf8Exception extends IllegalArgumentException
    {
        public NotUtf8Exception()
        {
            super("!UTF-8");
        }
    }
}