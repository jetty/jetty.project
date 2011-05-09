package org.eclipse.jetty.util;

import java.io.IOException;

public abstract class Utf8Appendable
{
    protected final Appendable _appendable;
    protected int _more;
    protected int _bits;

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
        if (b>=0)
        {
            if (_more>0)
            {
                _appendable.append('?');
                _more=0;
                _bits=0;
            }
            else
                _appendable.append((char)(0x7f&b));
        }
        else if (_more==0)
        {
            if ((b&0xc0)!=0xc0)
            {
                // 10xxxxxx
                _appendable.append('?');
                _more=0;
                _bits=0;
            }
            else
            { 
                if ((b & 0xe0) == 0xc0)
                {
                    //110xxxxx
                    _more=1;
                    _bits=b&0x1f;
                }
                else if ((b & 0xf0) == 0xe0)
                {
                    //1110xxxx
                    _more=2;
                    _bits=b&0x0f;
                }
                else if ((b & 0xf8) == 0xf0)
                {
                    //11110xxx
                    _more=3;
                    _bits=b&0x07;
                }
                else if ((b & 0xfc) == 0xf8)
                {
                    //111110xx
                    _more=4;
                    _bits=b&0x03;
                }
                else if ((b & 0xfe) == 0xfc) 
                {
                    //1111110x
                    _more=5;
                    _bits=b&0x01;
                }
                else
                {
                    throw new IllegalArgumentException("!utf8");
                }
            }
        }
        else
        {
            if ((b&0xc0)==0xc0)
            {    // 11??????
                _appendable.append('?');
                _more=0;
                _bits=0;
                throw new IllegalArgumentException("!utf8");
            }
            else
            {
                // 10xxxxxx
                _bits=(_bits<<6)|(b&0x3f);
                if (--_more==0)
                    _appendable.append(new String(Character.toChars(_bits)));
            }
        }
    }

}