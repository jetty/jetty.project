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

package org.eclipse.jetty.util;

import java.util.NoSuchElementException;
import java.util.StringTokenizer;

/* ------------------------------------------------------------ */
/** StringTokenizer with Quoting support.
 *
 * This class is a copy of the java.util.StringTokenizer API and
 * the behaviour is the same, except that single and doulbe quoted
 * string values are recognized.
 * Delimiters within quotes are not considered delimiters.
 * Quotes can be escaped with '\'.
 *
 * @see java.util.StringTokenizer
 * 
 */
public class QuotedStringTokenizer
    extends StringTokenizer
{
    private final static String __delim="\t\n\r";
    private String _string;
    private String _delim = __delim;
    private boolean _returnQuotes=false;
    private boolean _returnDelimiters=false;
    private StringBuffer _token;
    private boolean _hasToken=false;
    private int _i=0;
    private int _lastStart=0;
    private boolean _double=true;
    private boolean _single=true;
    
    /* ------------------------------------------------------------ */
    public QuotedStringTokenizer(String str,
                                 String delim,
                                 boolean returnDelimiters,
                                 boolean returnQuotes)
    {
        super("");
        _string=str;
        if (delim!=null)
            _delim=delim;
        _returnDelimiters=returnDelimiters;
        _returnQuotes=returnQuotes;
        
        if (_delim.indexOf('\'')>=0 ||
            _delim.indexOf('"')>=0)
            throw new Error("Can't use quotes as delimiters: "+_delim);
        
        _token=new StringBuffer(_string.length()>1024?512:_string.length()/2);
    }

    /* ------------------------------------------------------------ */
    public QuotedStringTokenizer(String str,
                                 String delim,
                                 boolean returnDelimiters)
    {
        this(str,delim,returnDelimiters,false);
    }
    
    /* ------------------------------------------------------------ */
    public QuotedStringTokenizer(String str,
                                 String delim)
    {
        this(str,delim,false,false);
    }

    /* ------------------------------------------------------------ */
    public QuotedStringTokenizer(String str)
    {
        this(str,null,false,false);
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean hasMoreTokens()
    {
        // Already found a token
        if (_hasToken)
            return true;
        
        _lastStart=_i;
        
        int state=0;
        boolean escape=false;
        while (_i<_string.length())
        {
            char c=_string.charAt(_i++);
            
            switch (state)
            {
              case 0: // Start
                  if(_delim.indexOf(c)>=0)
                  {
                      if (_returnDelimiters)
                      {
                          _token.append(c);
                          return _hasToken=true;
                      }
                  }
                  else if (c=='\'' && _single)
                  {
                      if (_returnQuotes)
                          _token.append(c);
                      state=2;
                  }
                  else if (c=='\"' && _double)
                  {
                      if (_returnQuotes)
                          _token.append(c);
                      state=3;
                  }
                  else
                  {
                      _token.append(c);
                      _hasToken=true;
                      state=1;
                  }
                  continue;
                  
              case 1: // Token
                  _hasToken=true;
                  if(_delim.indexOf(c)>=0)
                  {
                      if (_returnDelimiters)
                          _i--;
                      return _hasToken;
                  }
                  else if (c=='\'' && _single)
                  {
                      if (_returnQuotes)
                          _token.append(c);
                      state=2;
                  }
                  else if (c=='\"' && _double)
                  {
                      if (_returnQuotes)
                          _token.append(c);
                      state=3;
                  }
                  else
                      _token.append(c);
                  continue;

                  
              case 2: // Single Quote
                  _hasToken=true;
                  if (escape)
                  {
                      escape=false;
                      _token.append(c);
                  }
                  else if (c=='\'')
                  {
                      if (_returnQuotes)
                          _token.append(c);
                      state=1;
                  }
                  else if (c=='\\')
                  {
                      if (_returnQuotes)
                          _token.append(c);
                      escape=true;
                  }
                  else
                      _token.append(c);
                  continue;

                  
              case 3: // Double Quote
                  _hasToken=true;
                  if (escape)
                  {
                      escape=false;
                      _token.append(c);
                  }
                  else if (c=='\"')
                  {
                      if (_returnQuotes)
                          _token.append(c);
                      state=1;
                  }
                  else if (c=='\\')
                  {
                      if (_returnQuotes)
                          _token.append(c);
                      escape=true;
                  }
                  else
                      _token.append(c);
                  continue;
            }
        }

        return _hasToken;
    }

    /* ------------------------------------------------------------ */
    @Override
    public String nextToken()
        throws NoSuchElementException 
    {
        if (!hasMoreTokens() || _token==null)
            throw new NoSuchElementException();
        String t=_token.toString();
        _token.setLength(0);
        _hasToken=false;
        return t;
    }

    /* ------------------------------------------------------------ */
    @Override
    public String nextToken(String delim)
        throws NoSuchElementException 
    {
        _delim=delim;
        _i=_lastStart;
        _token.setLength(0);
        _hasToken=false;
        return nextToken();
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean hasMoreElements()
    {
        return hasMoreTokens();
    }

    /* ------------------------------------------------------------ */
    @Override
    public Object nextElement()
        throws NoSuchElementException 
    {
        return nextToken();
    }

    /* ------------------------------------------------------------ */
    /** Not implemented.
     */
    @Override
    public int countTokens()
    {
        return -1;
    }

    
    /* ------------------------------------------------------------ */
    /** Quote a string.
     * The string is quoted only if quoting is required due to
     * embeded delimiters, quote characters or the
     * empty string.
     * @param s The string to quote.
     * @return quoted string
     */
    public static String quote(String s, String delim)
    {
        if (s==null)
            return null;
        if (s.length()==0)
            return "\"\"";

        
        for (int i=0;i<s.length();i++)
        {
            char c = s.charAt(i);
            if (c=='\\' || c=='"' || c=='\'' || Character.isWhitespace(c) || delim.indexOf(c)>=0)
            {
                StringBuffer b=new StringBuffer(s.length()+8);
                quote(b,s);
                return b.toString();
            }
        }
        
        return s;
    }

    /* ------------------------------------------------------------ */
    /** Quote a string.
     * The string is quoted only if quoting is required due to
     * embeded delimiters, quote characters or the
     * empty string.
     * @param s The string to quote.
     * @return quoted string
     */
    public static String quote(String s)
    {
        if (s==null)
            return null;
        if (s.length()==0)
            return "\"\"";
        
        StringBuffer b=new StringBuffer(s.length()+8);
        quote(b,s);
        return b.toString();
   
    }

    
    /* ------------------------------------------------------------ */
    /** Quote a string into a StringBuffer.
     * The characters ", \, \n, \r, \t, \f and \b are escaped
     * @param buf The StringBuffer
     * @param s The String to quote.
     */
    public static void quote(StringBuffer buf, String s)
    {
        synchronized(buf)
        {
            buf.append('"');
            
            int i=0;
            loop:
            for (;i<s.length();i++)
            {
                char c = s.charAt(i);
                switch(c)
                {
                    case '"':
                        buf.append(s,0,i);
                        buf.append("\\\"");
                        break loop;
                    case '\\':
                        buf.append(s,0,i);
                        buf.append("\\\\");
                        break loop;
                    case '\n':
                        buf.append(s,0,i);
                        buf.append("\\n");
                        break loop;
                    case '\r':
                        buf.append(s,0,i);
                        buf.append("\\r");
                        break loop;
                    case '\t':
                        buf.append(s,0,i);
                        buf.append("\\t");
                        break loop;
                    case '\f':
                        buf.append(s,0,i);
                        buf.append("\\f");
                        break loop;
                    case '\b':
                        buf.append(s,0,i);
                        buf.append("\\b");
                        break loop;
                        
                    default:
                        continue;
                }
            }
            if (i==s.length())
                buf.append(s);
            else
            {
                i++;
                for (;i<s.length();i++)
                {
                    char c = s.charAt(i);
                    switch(c)
                    {
                        case '"':
                            buf.append("\\\"");
                            continue;
                        case '\\':
                            buf.append("\\\\");
                            continue;
                        case '\n':
                            buf.append("\\n");
                            continue;
                        case '\r':
                            buf.append("\\r");
                            continue;
                        case '\t':
                            buf.append("\\t");
                            continue;
                        case '\f':
                            buf.append("\\f");
                            continue;
                        case '\b':
                            buf.append("\\b");
                            continue;

                        default:
                            buf.append(c);
                        continue;
                    }
                }
            }
            
            buf.append('"');
        } 
        
        
        
    }


    /* ------------------------------------------------------------ */
    /** Quote a string into a StringBuffer.
     * The characters ", \, \n, \r, \t, \f and \b are escaped
     * @param buf The StringBuffer
     * @param s The String to quote.
     */
    public static void quote(StringBuilder buf, String s)
    {
        buf.append('"');

        int i=0;
        loop:
            for (;i<s.length();i++)
            {
                char c = s.charAt(i);
                switch(c)
                {
                    case '"':
                        buf.append(s,0,i);
                        buf.append("\\\"");
                        break loop;
                    case '\\':
                        buf.append(s,0,i);
                        buf.append("\\\\");
                        break loop;
                    case '\n':
                        buf.append(s,0,i);
                        buf.append("\\n");
                        break loop;
                    case '\r':
                        buf.append(s,0,i);
                        buf.append("\\r");
                        break loop;
                    case '\t':
                        buf.append(s,0,i);
                        buf.append("\\t");
                        break loop;
                    case '\f':
                        buf.append(s,0,i);
                        buf.append("\\f");
                        break loop;
                    case '\b':
                        buf.append(s,0,i);
                        buf.append("\\b");
                        break loop;

                    default:
                        continue;
                }
            }
        if (i==s.length())
            buf.append(s);
        else
        {
            i++;
            for (;i<s.length();i++)
            {
                char c = s.charAt(i);
                switch(c)
                {
                    case '"':
                        buf.append("\\\"");
                        continue;
                    case '\\':
                        buf.append("\\\\");
                        continue;
                    case '\n':
                        buf.append("\\n");
                        continue;
                    case '\r':
                        buf.append("\\r");
                        continue;
                    case '\t':
                        buf.append("\\t");
                        continue;
                    case '\f':
                        buf.append("\\f");
                        continue;
                    case '\b':
                        buf.append("\\b");
                        continue;

                    default:
                        buf.append(c);
                    continue;
                }
            }
        }

        buf.append('"');
    } 




    
    /* ------------------------------------------------------------ */
    /** Quote a string into a StringBuffer.
     * The characters ", \, \n, \r, \t, \f, \b are escaped.
     * Quotes are forced if any escaped characters are present or there
     * is a ", ', space, +, =, ; or % character.
     * 
     * @param buf The StringBuffer
     * @param s The String to quote.
     */
    public static void quoteIfNeeded(StringBuffer buf, String s)
    {
        synchronized(buf)
        {
            int e=-1;
            
            search: for (int i=0;i<s.length();i++)
            {
                char c = s.charAt(i);
                switch(c)
                {
                    case '"':
                    case '\\':
                    case '\n':
                    case '\r':
                    case '\t':
                    case '\f':
                    case '\b':
                    case '%':
                    case '+':
                    case ' ':
                    case ';':
                    case '=':
                        e=i;
                        buf.append('"');
                        // TODO when 1.4 support is dropped: buf.append(s,0,e);
                        for (int j=0;j<e;j++)
                            buf.append(s.charAt(j));
                        break search;
                        
                    default:
                        continue;
                }
            }
            
            if (e<0)
            {
                buf.append(s);
                return;
            }
            
            for (int i=e;i<s.length();i++)
            {
                char c = s.charAt(i);
                switch(c)
                {
                    case '"':
                        buf.append("\\\"");
                        continue;
                    case '\\':
                        buf.append("\\\\");
                        continue;
                    case '\n':
                        buf.append("\\n");
                        continue;
                    case '\r':
                        buf.append("\\r");
                        continue;
                    case '\t':
                        buf.append("\\t");
                        continue;
                    case '\f':
                        buf.append("\\f");
                        continue;
                    case '\b':
                        buf.append("\\b");
                        continue;
                        
                    default:
                        buf.append(c);
                        continue;
                }
            }
            buf.append('"');
        }
    }
    
    /* ------------------------------------------------------------ */
    /** Quote a string into a StringBuffer.
     * The characters ", \, \n, \r, \t, \f, \b are escaped.
     * Quotes are forced if any escaped characters are present or there
     * is a ", ', space, + or % character.
     * 
     * @param buf The StringBuilder
     * @param s The String to quote.
     */
    public static void quoteIfNeeded(StringBuilder buf, String s)
    {
        int e=-1;

        search: for (int i=0;i<s.length();i++)
        {
            char c = s.charAt(i);
            switch(c)
            {
                case '"':
                case '\\':
                case '\n':
                case '\r':
                case '\t':
                case '\f':
                case '\b':
                case '%':
                case '+':
                case ' ':
                case ';':
                case '=':
                    e=i;
                    buf.append('"');
                    // TODO when 1.4 support is dropped: buf.append(s,0,e);
                    for (int j=0;j<e;j++)
                        buf.append(s.charAt(j));
                    break search;

                default:
                    continue;
            }
        }

        if (e<0)
        {
            buf.append(s);
            return;
        }

        for (int i=e;i<s.length();i++)
        {
            char c = s.charAt(i);
            switch(c)
            {
                case '"':
                    buf.append("\\\"");
                    continue;
                case '\\':
                    buf.append("\\\\");
                    continue;
                case '\n':
                    buf.append("\\n");
                    continue;
                case '\r':
                    buf.append("\\r");
                    continue;
                case '\t':
                    buf.append("\\t");
                    continue;
                case '\f':
                    buf.append("\\f");
                    continue;
                case '\b':
                    buf.append("\\b");
                    continue;

                default:
                    buf.append(c);
                continue;
            }
        }
        buf.append('"');

    }
    
    /* ------------------------------------------------------------ */
    /** Unquote a string.
     * @param s The string to unquote.
     * @return quoted string
     */
    public static String unquote(String s)
    {
        if (s==null)
            return null;
        if (s.length()<2)
            return s;

        char first=s.charAt(0);
        char last=s.charAt(s.length()-1);
        if (first!=last || (first!='"' && first!='\''))
            return s;
        
        StringBuffer b=new StringBuffer(s.length()-2);
        synchronized(b)
        {
            boolean escape=false;
            for (int i=1;i<s.length()-1;i++)
            {
                char c = s.charAt(i);

                if (escape)
                {
                    escape=false;
                    switch (c)
                    {
                        case 'n':
                            b.append('\n');
                            break;
                        case 'r':
                            b.append('\r');
                            break;
                        case 't':
                            b.append('\t');
                            break;
                        case 'f':
                            b.append('\f');
                            break;
                        case 'b':
                            b.append('\b');
                            break;
                        case 'u':
                            b.append((char)(
                                    (TypeUtil.convertHexDigit((byte)s.charAt(i++))<<24)+
                                    (TypeUtil.convertHexDigit((byte)s.charAt(i++))<<16)+
                                    (TypeUtil.convertHexDigit((byte)s.charAt(i++))<<8)+
                                    (TypeUtil.convertHexDigit((byte)s.charAt(i++)))
                                    ) 
                            );
                            break;
                        default:
                            b.append(c);
                    }
                }
                else if (c=='\\')
                {
                    escape=true;
                    continue;
                }
                else
                    b.append(c);
            }
            
            return b.toString();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @return handle double quotes if true
     */
    public boolean getDouble()
    {
        return _double;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param d handle double quotes if true
     */
    public void setDouble(boolean d)
    {
        _double=d;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return handle single quotes if true
     */
    public boolean getSingle()
    {
        return _single;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param single handle single quotes if true
     */
    public void setSingle(boolean single)
    {
        _single=single;
    }
}












