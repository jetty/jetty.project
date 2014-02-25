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

package org.eclipse.jetty.util;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/** Fast String Utilities.
 *
 * These string utilities provide both convenience methods and
 * performance improvements over most standard library versions. The
 * main aim of the optimizations is to avoid object creation unless
 * absolutely required.
 *
 * 
 */
public class StringUtil
{
    private static final Logger LOG = Log.getLogger(StringUtil.class);
    
    
    private final static Trie<String> CHARSETS= new ArrayTrie<>(256);
    
    public static final String ALL_INTERFACES="0.0.0.0";
    public static final String CRLF="\015\012";
    public static final String __LINE_SEPARATOR=
        System.getProperty("line.separator","\n");
       
    public static final String __ISO_8859_1="ISO-8859-1";
    public final static String __UTF8="UTF-8";
    public final static String __UTF16="UTF-16";

    /**
     * @deprecated Use {@link StandardCharsets#UTF_8}
     */
    @Deprecated
    public final static Charset __UTF8_CHARSET=StandardCharsets.UTF_8;
    /**
     * @deprecated Use {@link StandardCharsets#ISO_8859_1}
     */
    @Deprecated
    public final static Charset __ISO_8859_1_CHARSET=StandardCharsets.ISO_8859_1;
    /**
     * @deprecated Use {@link StandardCharsets#UTF_16}
     */
    @Deprecated
    public final static Charset __UTF16_CHARSET=StandardCharsets.UTF_16;
    /**
     * @deprecated Use {@link StandardCharsets#US_ASCII}
     */
    @Deprecated
    public final static Charset __US_ASCII_CHARSET=StandardCharsets.US_ASCII;
    
    static
    {
        CHARSETS.put("UTF-8",__UTF8);
        CHARSETS.put("UTF8",__UTF8);
        CHARSETS.put("UTF-16",__UTF16);
        CHARSETS.put("UTF16",__UTF16);
        CHARSETS.put("ISO-8859-1",__ISO_8859_1);
        CHARSETS.put("ISO_8859_1",__ISO_8859_1);
    }
    
    /* ------------------------------------------------------------ */
    /** Convert alternate charset names (eg utf8) to normalized
     * name (eg UTF-8).
     */
    public static String normalizeCharset(String s)
    {
        String n=CHARSETS.get(s);
        return (n==null)?s:n;
    }
    
    /* ------------------------------------------------------------ */
    /** Convert alternate charset names (eg utf8) to normalized
     * name (eg UTF-8).
     */
    public static String normalizeCharset(String s,int offset,int length)
    {
        String n=CHARSETS.get(s,offset,length);       
        return (n==null)?s.substring(offset,offset+length):n;
    }
    

    /* ------------------------------------------------------------ */
    public static final char[] lowercases = {
          '\000','\001','\002','\003','\004','\005','\006','\007',
          '\010','\011','\012','\013','\014','\015','\016','\017',
          '\020','\021','\022','\023','\024','\025','\026','\027',
          '\030','\031','\032','\033','\034','\035','\036','\037',
          '\040','\041','\042','\043','\044','\045','\046','\047',
          '\050','\051','\052','\053','\054','\055','\056','\057',
          '\060','\061','\062','\063','\064','\065','\066','\067',
          '\070','\071','\072','\073','\074','\075','\076','\077',
          '\100','\141','\142','\143','\144','\145','\146','\147',
          '\150','\151','\152','\153','\154','\155','\156','\157',
          '\160','\161','\162','\163','\164','\165','\166','\167',
          '\170','\171','\172','\133','\134','\135','\136','\137',
          '\140','\141','\142','\143','\144','\145','\146','\147',
          '\150','\151','\152','\153','\154','\155','\156','\157',
          '\160','\161','\162','\163','\164','\165','\166','\167',
          '\170','\171','\172','\173','\174','\175','\176','\177' };

    /* ------------------------------------------------------------ */
    /**
     * fast lower case conversion. Only works on ascii (not unicode)
     * @param s the string to convert
     * @return a lower case version of s
     */
    public static String asciiToLowerCase(String s)
    {
        char[] c = null;
        int i=s.length();

        // look for first conversion
        while (i-->0)
        {
            char c1=s.charAt(i);
            if (c1<=127)
            {
                char c2=lowercases[c1];
                if (c1!=c2)
                {
                    c=s.toCharArray();
                    c[i]=c2;
                    break;
                }
            }
        }

        while (i-->0)
        {
            if(c[i]<=127)
                c[i] = lowercases[c[i]];
        }
        
        return c==null?s:new String(c);
    }


    /* ------------------------------------------------------------ */
    public static boolean startsWithIgnoreCase(String s,String w)
    {
        if (w==null)
            return true;
        
        if (s==null || s.length()<w.length())
            return false;
        
        for (int i=0;i<w.length();i++)
        {
            char c1=s.charAt(i);
            char c2=w.charAt(i);
            if (c1!=c2)
            {
                if (c1<=127)
                    c1=lowercases[c1];
                if (c2<=127)
                    c2=lowercases[c2];
                if (c1!=c2)
                    return false;
            }
        }
        return true;
    }
    
    /* ------------------------------------------------------------ */
    public static boolean endsWithIgnoreCase(String s,String w)
    {
        if (w==null)
            return true;

        if (s==null)
            return false;
            
        int sl=s.length();
        int wl=w.length();
        
        if (sl<wl)
            return false;
        
        for (int i=wl;i-->0;)
        {
            char c1=s.charAt(--sl);
            char c2=w.charAt(i);
            if (c1!=c2)
            {
                if (c1<=127)
                    c1=lowercases[c1];
                if (c2<=127)
                    c2=lowercases[c2];
                if (c1!=c2)
                    return false;
            }
        }
        return true;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * returns the next index of a character from the chars string
     */
    public static int indexFrom(String s,String chars)
    {
        for (int i=0;i<s.length();i++)
           if (chars.indexOf(s.charAt(i))>=0)
              return i;
        return -1;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * replace substrings within string.
     */
    public static String replace(String s, String sub, String with)
    {
        int c=0;
        int i=s.indexOf(sub,c);
        if (i == -1)
            return s;
    
        StringBuilder buf = new StringBuilder(s.length()+with.length());

        do
        {
            buf.append(s.substring(c,i));
            buf.append(with);
            c=i+sub.length();
        } while ((i=s.indexOf(sub,c))!=-1);

        if (c<s.length())
            buf.append(s.substring(c,s.length()));

        return buf.toString();
        
    }


    /* ------------------------------------------------------------ */
    /** Remove single or double quotes.
     */
    public static String unquote(String s)
    {
        return QuotedStringTokenizer.unquote(s);
    }


    /* ------------------------------------------------------------ */
    /** Append substring to StringBuilder 
     * @param buf StringBuilder to append to
     * @param s String to append from
     * @param offset The offset of the substring
     * @param length The length of the substring
     */
    public static void append(StringBuilder buf,
                              String s,
                              int offset,
                              int length)
    {
        synchronized(buf)
        {
            int end=offset+length;
            for (int i=offset; i<end;i++)
            {
                if (i>=s.length())
                    break;
                buf.append(s.charAt(i));
            }
        }
    }

    
    /* ------------------------------------------------------------ */
    /**
     * append hex digit
     * 
     */
    public static void append(StringBuilder buf,byte b,int base)
    {
        int bi=0xff&b;
        int c='0'+(bi/base)%base;
        if (c>'9')
            c= 'a'+(c-'0'-10);
        buf.append((char)c);
        c='0'+bi%base;
        if (c>'9')
            c= 'a'+(c-'0'-10);
        buf.append((char)c);
    }

    /* ------------------------------------------------------------ */
    public static void append2digits(StringBuffer buf,int i)
    {
        if (i<100)
        {
            buf.append((char)(i/10+'0'));
            buf.append((char)(i%10+'0'));
        }
    }
    
    /* ------------------------------------------------------------ */
    public static void append2digits(StringBuilder buf,int i)
    {
        if (i<100)
        {
            buf.append((char)(i/10+'0'));
            buf.append((char)(i%10+'0'));
        }
    }
    
    /* ------------------------------------------------------------ */
    /** Return a non null string.
     * @param s String
     * @return The string passed in or empty string if it is null. 
     */
    public static String nonNull(String s)
    {
        if (s==null)
            return "";
        return s;
    }
    
    /* ------------------------------------------------------------ */
    public static boolean equals(String s,char[] buf, int offset, int length)
    {
        if (s.length()!=length)
            return false;
        for (int i=0;i<length;i++)
            if (buf[offset+i]!=s.charAt(i))
                return false;
        return true;
    }

    /* ------------------------------------------------------------ */
    public static String toUTF8String(byte[] b,int offset,int length)
    {
        return new String(b,offset,length,StandardCharsets.UTF_8);
    }

    /* ------------------------------------------------------------ */
    public static String toString(byte[] b,int offset,int length,String charset)
    {
        try
        {
            return new String(b,offset,length,charset);
        }
        catch (UnsupportedEncodingException e)
        {
            throw new IllegalArgumentException(e);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Test if a string is null or only has whitespace characters in it.
     * <p>
     * Note: uses codepoint version of {@link Character#isWhitespace(int)} to support Unicode better.
     * 
     * <pre>
     *   isBlank(null)   == true
     *   isBlank("")     == true
     *   isBlank("\r\n") == true
     *   isBlank("\t")   == true
     *   isBlank("   ")  == true
     *   isBlank("a")    == false
     *   isBlank(".")    == false
     *   isBlank(";\n")  == false
     * </pre>
     * 
     * @param str
     *            the string to test.
     * @return true if string is null or only whitespace characters, false if non-whitespace characters encountered.
     */
    public static boolean isBlank(String str)
    {
        if (str == null)
        {
            return true;
        }
        int len = str.length();
        for (int i = 0; i < len; i++)
        {
            if (!Character.isWhitespace(str.codePointAt(i)))
            {
                // found a non-whitespace, we can stop searching  now
                return false;
            }
        }
        // only whitespace
        return true;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Test if a string is not null and contains at least 1 non-whitespace characters in it.
     * <p>
     * Note: uses codepoint version of {@link Character#isWhitespace(int)} to support Unicode better.
     * 
     * <pre>
     *   isNotBlank(null)   == false
     *   isNotBlank("")     == false
     *   isNotBlank("\r\n") == false
     *   isNotBlank("\t")   == false
     *   isNotBlank("   ")  == false
     *   isNotBlank("a")    == true
     *   isNotBlank(".")    == true
     *   isNotBlank(";\n")  == true
     * </pre>
     * 
     * @param str
     *            the string to test.
     * @return true if string is not null and has at least 1 non-whitespace character, false if null or all-whitespace characters.
     */
    public static boolean isNotBlank(String str)
    {
        if (str == null)
        {
            return false;
        }
        int len = str.length();
        for (int i = 0; i < len; i++)
        {
            if (!Character.isWhitespace(str.codePointAt(i)))
            {
                // found a non-whitespace, we can stop searching  now
                return true;
            }
        }
        // only whitespace
        return false;
    }

    /* ------------------------------------------------------------ */
    public static boolean isUTF8(String charset)
    {
        return __UTF8.equalsIgnoreCase(charset)||__UTF8.equalsIgnoreCase(normalizeCharset(charset));
    }


    /* ------------------------------------------------------------ */
    public static String printable(String name)
    {
        if (name==null)
            return null;
        StringBuilder buf = new StringBuilder(name.length());
        for (int i=0;i<name.length();i++)
        {
            char c=name.charAt(i);
            if (!Character.isISOControl(c))
                buf.append(c);
        }
        return buf.toString();
    }
    
    /* ------------------------------------------------------------ */
    public static String printable(byte[] b)
    {
        StringBuilder buf = new StringBuilder();
        for (int i=0;i<b.length;i++)
        {
            char c=(char)b[i];
            if (Character.isWhitespace(c)|| c>' ' && c<0x7f)
                buf.append(c);
            else 
            {
                buf.append("0x");
                TypeUtil.toHex(b[i],buf);
            }
        }
        return buf.toString();
    }
    
    public static byte[] getBytes(String s)
    {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }
    
    public static byte[] getUtf8Bytes(String s)
    {
        return s.getBytes(StandardCharsets.UTF_8);
    }
    
    public static byte[] getBytes(String s,String charset)
    {
        try
        {
            return s.getBytes(charset);
        }
        catch(Exception e)
        {
            LOG.warn(e);
            return s.getBytes();
        }
    }
    
    
    
    /**
     * Converts a binary SID to a string SID
     * 
     * http://en.wikipedia.org/wiki/Security_Identifier
     * 
     * S-1-IdentifierAuthority-SubAuthority1-SubAuthority2-...-SubAuthorityn
     */
    public static String sidBytesToString(byte[] sidBytes)
    {
        StringBuilder sidString = new StringBuilder();
        
        // Identify this as a SID
        sidString.append("S-");
        
        // Add SID revision level (expect 1 but may change someday)
        sidString.append(Byte.toString(sidBytes[0])).append('-');
        
        StringBuilder tmpBuilder = new StringBuilder();
        
        // crunch the six bytes of issuing authority value
        for (int i = 2; i <= 7; ++i)
        {
            tmpBuilder.append(Integer.toHexString(sidBytes[i] & 0xFF));
        }
        
        sidString.append(Long.parseLong(tmpBuilder.toString(), 16)); // '-' is in the subauth loop
   
        // the number of subAuthorities we need to attach
        int subAuthorityCount = sidBytes[1];

        // attach each of the subAuthorities
        for (int i = 0; i < subAuthorityCount; ++i)
        {
            int offset = i * 4;
            tmpBuilder.setLength(0);
            // these need to be zero padded hex and little endian
            tmpBuilder.append(String.format("%02X%02X%02X%02X", 
                    (sidBytes[11 + offset] & 0xFF),
                    (sidBytes[10 + offset] & 0xFF),
                    (sidBytes[9 + offset] & 0xFF),
                    (sidBytes[8 + offset] & 0xFF)));  
            sidString.append('-').append(Long.parseLong(tmpBuilder.toString(), 16));
        }
        
        return sidString.toString();
    }
    
    /**
     * Converts a string SID to a binary SID
     * 
     * http://en.wikipedia.org/wiki/Security_Identifier
     * 
     * S-1-IdentifierAuthority-SubAuthority1-SubAuthority2-...-SubAuthorityn
     */
    public static byte[] sidStringToBytes( String sidString )
    {
        String[] sidTokens = sidString.split("-");
        
        int subAuthorityCount = sidTokens.length - 3; // S-Rev-IdAuth-
        
        int byteCount = 0;
        byte[] sidBytes = new byte[1 + 1 + 6 + (4 * subAuthorityCount)];
        
        // the revision byte
        sidBytes[byteCount++] = (byte)Integer.parseInt(sidTokens[1]);

        // the # of sub authorities byte
        sidBytes[byteCount++] = (byte)subAuthorityCount;

        // the certAuthority
        String hexStr = Long.toHexString(Long.parseLong(sidTokens[2]));
        
        while( hexStr.length() < 12) // pad to 12 characters
        {
            hexStr = "0" + hexStr;
        }

        // place the certAuthority 6 bytes
        for ( int i = 0 ; i < hexStr.length(); i = i + 2)
        {
            sidBytes[byteCount++] = (byte)Integer.parseInt(hexStr.substring(i, i + 2),16);
        }
                
        
        for ( int i = 3; i < sidTokens.length ; ++i)
        {
            hexStr = Long.toHexString(Long.parseLong(sidTokens[i]));
            
            while( hexStr.length() < 8) // pad to 8 characters
            {
                hexStr = "0" + hexStr;
            }     
            
            // place the inverted sub authorities, 4 bytes each
            for ( int j = hexStr.length(); j > 0; j = j - 2)
            {          
                sidBytes[byteCount++] = (byte)Integer.parseInt(hexStr.substring(j-2, j),16);
            }
        }
      
        return sidBytes;
    }
    

    /**
     * Convert String to an integer. Parses up to the first non-numeric character. If no number is found an IllegalArgumentException is thrown
     * 
     * @param string
     *            A String containing an integer.
     * @return an int
     */
    public static int toInt(String string)
    {
        int val = 0;
        boolean started = false;
        boolean minus = false;

        for (int i = 0; i < string.length(); i++)
        {
            char b = string.charAt(i);
            if (b <= ' ')
            {
                if (started)
                    break;
            }
            else if (b >= '0' && b <= '9')
            {
                val = val * 10 + (b - '0');
                started = true;
            }
            else if (b == '-' && !started)
            {
                minus = true;
            }
            else
                break;
        }

        if (started)
            return minus?(-val):val;
        throw new NumberFormatException(string);
    }

    /**
     * Convert String to an long. Parses up to the first non-numeric character. If no number is found an IllegalArgumentException is thrown
     * 
     * @param string
     *            A String containing an integer.
     * @return an int
     */
    public static long toLong(String string)
    {
        long val = 0;
        boolean started = false;
        boolean minus = false;

        for (int i = 0; i < string.length(); i++)
        {
            char b = string.charAt(i);
            if (b <= ' ')
            {
                if (started)
                    break;
            }
            else if (b >= '0' && b <= '9')
            {
                val = val * 10L + (b - '0');
                started = true;
            }
            else if (b == '-' && !started)
            {
                minus = true;
            }
            else
                break;
        }

        if (started)
            return minus?(-val):val;
        throw new NumberFormatException(string);
    }
    
    /**
     * Truncate a string to a max size.
     * 
     * @param str the string to possibly truncate
     * @param maxSize the maximum size of the string
     * @return the truncated string.  if <code>str</code> param is null, then the returned string will also be null.
     */
    public static String truncate(String str, int maxSize)
    {
        if (str == null)
        {
            return null;
        }

        if (str.length() <= maxSize)
        {
            return str;
        }

        return str.substring(0,maxSize);
    }

    public static String[] arrayFromString(String s) 
    {
        if (s==null)
            return new String[]{};

        if (!s.startsWith("[") || !s.endsWith("]"))
            throw new IllegalArgumentException();
        if (s.length()==2)
            return new String[]{};

        return s.substring(1,s.length()-1).split(" *, *");
    }

}
