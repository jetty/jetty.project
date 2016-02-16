//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import static org.eclipse.jetty.util.TypeUtil.convertHexDigit;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.util.Utf8Appendable.NotUtf8Exception;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/** 
 * Handles coding of MIME  "x-www-form-urlencoded".
 * <p>
 * This class handles the encoding and decoding for either
 * the query string of a URL or the _content of a POST HTTP request.
 * </p>
 * <b>Notes</b>
 * <p>
 * The UTF-8 charset is assumed, unless otherwise defined by either
 * passing a parameter or setting the "org.eclipse.jetty.util.UrlEncoding.charset"
 * System property.
 * </p>
 * <p>
 * The hashtable either contains String single values, vectors
 * of String or arrays of Strings.
 * </p>
 * <p>
 * This class is only partially synchronised.  In particular, simple
 * get operations are not protected from concurrent updates.
 * </p>
 *
 * @see java.net.URLEncoder
 */
@SuppressWarnings("serial")
public class UrlEncoded extends MultiMap<String> implements Cloneable
{
    static final Logger LOG = Log.getLogger(UrlEncoded.class);

    public static final Charset ENCODING;
    static
    {
        Charset encoding;
        try
        {
            String charset = System.getProperty("org.eclipse.jetty.util.UrlEncoding.charset");
            encoding = charset == null ? StandardCharsets.UTF_8 : Charset.forName(charset);
        }
        catch(Exception e)
        {
            LOG.warn(e);
            encoding=StandardCharsets.UTF_8;
        }
        ENCODING=encoding;
    }
    
    /* ----------------------------------------------------------------- */
    public UrlEncoded(UrlEncoded url)
    {
        super(url);
    }
    
    /* ----------------------------------------------------------------- */
    public UrlEncoded()
    {
    }
    
    public UrlEncoded(String query)
    {
        decodeTo(query,this,ENCODING);
    }

    /* ----------------------------------------------------------------- */
    public void decode(String query)
    {
        decodeTo(query,this,ENCODING);
    }
    
    /* ----------------------------------------------------------------- */
    public void decode(String query,Charset charset)
    {
        decodeTo(query,this,charset);
    }
    
    /* -------------------------------------------------------------- */
    /** Encode MultiMap with % encoding for UTF8 sequences.
     * @return the MultiMap as a string with % encoding
     */
    public String encode()
    {
        return encode(ENCODING,false);
    }
    
    /* -------------------------------------------------------------- */
    /** Encode MultiMap with % encoding for arbitrary Charset sequences.
     * @param charset the charset to use for encoding
     * @return the MultiMap as a string encoded with % encodings
     */
    public String encode(Charset charset)
    {
        return encode(charset,false);
    }
    
    /* -------------------------------------------------------------- */
    /** 
     * Encode MultiMap with % encoding.
     * @param charset the charset to encode with
     * @param equalsForNullValue if True, then an '=' is always used, even
     * for parameters without a value. e.g. <code>"blah?a=&amp;b=&amp;c="</code>.
     * @return the MultiMap as a string encoded with % encodings
     */
    public synchronized String encode(Charset charset, boolean equalsForNullValue)
    {
        return encode(this,charset,equalsForNullValue);
    }
    
    /* -------------------------------------------------------------- */
    /** Encode MultiMap with % encoding.
     * @param map the map to encode
     * @param charset the charset to use for encoding (uses default encoding if null)
     * @param equalsForNullValue if True, then an '=' is always used, even
     * for parameters without a value. e.g. <code>"blah?a=&amp;b=&amp;c="</code>.
     * @return the MultiMap as a string encoded with % encodings. 
     */
    public static String encode(MultiMap<String> map, Charset charset, boolean equalsForNullValue)
    {
        if (charset==null)
            charset=ENCODING;

        StringBuilder result = new StringBuilder(128);

        boolean delim = false;
        for(Map.Entry<String, List<String>> entry: map.entrySet())
        {
            String key = entry.getKey().toString();
            List<String> list = entry.getValue();
            int s=list.size();
            
            if (delim)
            {
                result.append('&');
            }

            if (s==0)
            {
                result.append(encodeString(key,charset));
                if(equalsForNullValue)
                    result.append('=');
            }
            else
            {
                for (int i=0;i<s;i++)
                {
                    if (i>0)
                        result.append('&');
                    String val=list.get(i);
                    result.append(encodeString(key,charset));

                    if (val!=null)
                    {
                        String str=val.toString();
                        if (str.length()>0)
                        {
                            result.append('=');
                            result.append(encodeString(str,charset));
                        }
                        else if (equalsForNullValue)
                            result.append('=');
                    }
                    else if (equalsForNullValue)
                        result.append('=');
                }
            }
            delim = true;
        }
        return result.toString();
    }

    /* -------------------------------------------------------------- */
    /** Decoded parameters to Map.
     * @param content the string containing the encoded parameters
     * @param map the MultiMap to put parsed query parameters into
     * @param charset the charset to use for decoding
     */
    public static void decodeTo(String content, MultiMap<String> map, String charset)
    {
        decodeTo(content,map,charset==null?null:Charset.forName(charset));
    }
    
    /* -------------------------------------------------------------- */
    /** Decoded parameters to Map.
     * @param content the string containing the encoded parameters
     * @param map the MultiMap to put parsed query parameters into
     * @param charset the charset to use for decoding
     */
    public static void decodeTo(String content, MultiMap<String> map, Charset charset)
    {
        if (charset==null)
            charset=ENCODING;

        if (charset==StandardCharsets.UTF_8)
        {
            decodeUtf8To(content,0,content.length(),map);
            return;
        }
        
        synchronized(map)
        {
            String key = null;
            String value = null;
            int mark=-1;
            boolean encoded=false;
            for (int i=0;i<content.length();i++)
            {
                char c = content.charAt(i);
                switch (c)
                {
                  case '&':
                      int l=i-mark-1;
                      value = l==0?"":
                          (encoded?decodeString(content,mark+1,l,charset):content.substring(mark+1,i));
                      mark=i;
                      encoded=false;
                      if (key != null)
                      {
                          map.add(key,value);
                      }
                      else if (value!=null&&value.length()>0)
                      {
                          map.add(value,"");
                      }
                      key = null;
                      value=null;
                      break;
                  case '=':
                      if (key!=null)
                          break;
                      key = encoded?decodeString(content,mark+1,i-mark-1,charset):content.substring(mark+1,i);
                      mark=i;
                      encoded=false;
                      break;
                  case '+':
                      encoded=true;
                      break;
                  case '%':
                      encoded=true;
                      break;
                }                
            }
            
            if (key != null)
            {
                int l=content.length()-mark-1;
                value = l==0?"":(encoded?decodeString(content,mark+1,l,charset):content.substring(mark+1));
                map.add(key,value);
            }
            else if (mark<content.length())
            {
                key = encoded
                    ?decodeString(content,mark+1,content.length()-mark-1,charset)
                    :content.substring(mark+1);
                if (key != null && key.length() > 0)
                {
                    map.add(key,"");
                }
            }
        }
    }

    /* -------------------------------------------------------------- */
    public static void decodeUtf8To(String query, MultiMap<String> map)
    {
        decodeUtf8To(query,0,query.length(),map);
    }
    
    /* -------------------------------------------------------------- */
    /** Decoded parameters to Map.
     * @param query the string containing the encoded parameters
     * @param offset the offset within raw to decode from
     * @param length the length of the section to decode
     * @param map the {@link MultiMap} to populate
     */
    public static void decodeUtf8To(String query,int offset, int length, MultiMap<String> map)
    {
        Utf8StringBuilder buffer = new Utf8StringBuilder();
        synchronized(map)
        {
            String key = null;
            String value = null;

            int end=offset+length;
            for (int i=offset;i<end;i++)
            {
                char c=query.charAt(i);
                try
                {
                    switch (c)
                    {
                        case '&':
                            value = buffer.toReplacedString();
                            buffer.reset();
                            if (key != null)
                            {
                                map.add(key,value);
                            }
                            else if (value!=null&&value.length()>0)
                            {
                                map.add(value,"");
                            }
                            key = null;
                            value=null;
                            break;

                        case '=':
                            if (key!=null)
                            {
                                buffer.append(c);
                                break;
                            }
                            key = buffer.toReplacedString();
                            buffer.reset();
                            break;

                        case '+':
                            buffer.append((byte)' ');
                            break;

                        case '%':
                            if (i+2<end)
                            {
                                if ('u'==query.charAt(i+1))
                                {
                                    i++;
                                    if (i+4<end)
                                    {
                                        char top=query.charAt(++i);
                                        char hi=query.charAt(++i);
                                        char lo=query.charAt(++i);
                                        char bot=query.charAt(++i);
                                        buffer.getStringBuilder().append(Character.toChars((convertHexDigit(top)<<12) +(convertHexDigit(hi)<<8) + (convertHexDigit(lo)<<4) +convertHexDigit(bot)));
                                    }
                                    else
                                    {
                                        buffer.getStringBuilder().append(Utf8Appendable.REPLACEMENT);
                                        i=end;
                                    }
                                }
                                else
                                {
                                    char hi=query.charAt(++i);
                                    char lo=query.charAt(++i);
                                    buffer.append((byte)((convertHexDigit(hi)<<4) + convertHexDigit(lo)));
                                }
                            }
                            else
                            {
                                buffer.getStringBuilder().append(Utf8Appendable.REPLACEMENT);
                                i=end;
                            }
                            break;
                            
                        default:
                            buffer.append(c);
                            break;
                    }
                }
                catch(NotUtf8Exception e)
                {
                    LOG.warn(e.toString());
                    LOG.debug(e);
                }
                catch(NumberFormatException e)
                {
                    buffer.append(Utf8Appendable.REPLACEMENT_UTF8,0,3);
                    LOG.warn(e.toString());
                    LOG.debug(e);
                }
            }
            
            if (key != null)
            {
                value = buffer.toReplacedString();
                buffer.reset();
                map.add(key,value);
            }
            else if (buffer.length()>0)
            {
                map.add(buffer.toReplacedString(),"");
            }
        }
    }

    /* -------------------------------------------------------------- */
    /** Decoded parameters to MultiMap, using ISO8859-1 encodings.
     * 
     * @param in InputSteam to read
     * @param map MultiMap to add parameters to
     * @param maxLength maximum length of form to read
     * @param maxKeys maximum number of keys to read or -1 for no limit
     * @throws IOException if unable to decode inputstream as ISO8859-1
     */
    public static void decode88591To(InputStream in, MultiMap<String> map, int maxLength, int maxKeys)
    throws IOException
    {
        synchronized(map)
        {
            StringBuffer buffer = new StringBuffer();
            String key = null;
            String value = null;
            
            int b;

            int totalLength=0;
            while ((b=in.read())>=0)
            {
                switch ((char) b)
                {
                    case '&':
                        value = buffer.length()==0?"":buffer.toString();
                        buffer.setLength(0);
                        if (key != null)
                        {
                            map.add(key,value);
                        }
                        else if (value!=null&&value.length()>0)
                        {
                            map.add(value,"");
                        }
                        key = null;
                        value=null;
                        if (maxKeys>0 && map.size()>maxKeys)
                            throw new IllegalStateException(String.format("Form with too many keys [%d > %d]",map.size(),maxKeys));
                        break;
                        
                    case '=':
                        if (key!=null)
                        {
                            buffer.append((char)b);
                            break;
                        }
                        key = buffer.toString();
                        buffer.setLength(0);
                        break;
                        
                    case '+':
                        buffer.append(' ');
                        break;
                        
                    case '%':
                        int code0=in.read();
                        if ('u'==code0)
                        {
                            int code1=in.read();
                            if (code1>=0)
                            {
                                int code2=in.read();
                                if (code2>=0)
                                {
                                    int code3=in.read();
                                    if (code3>=0)
                                        buffer.append(Character.toChars((convertHexDigit(code0)<<12)+(convertHexDigit(code1)<<8)+(convertHexDigit(code2)<<4)+convertHexDigit(code3)));
                                }
                            }
                        }
                        else if (code0>=0)
                        {
                            int code1=in.read();
                            if (code1>=0)
                                buffer.append((char)((convertHexDigit(code0)<<4)+convertHexDigit(code1)));
                        }
                        break;
                     
                    default:
                        buffer.append((char)b);
                    break;
                }
                if (maxLength>=0 && (++totalLength > maxLength))
                    throw new IllegalStateException(String.format("Form with too many keys [%d > %d]",map.size(),maxKeys));
            }
            
            if (key != null)
            {
                value = buffer.length()==0?"":buffer.toString();
                buffer.setLength(0);
                map.add(key,value);
            }
            else if (buffer.length()>0)
            {
                map.add(buffer.toString(), "");
            }
        }
    }
    
    /* -------------------------------------------------------------- */
    /** Decoded parameters to Map.
     * @param in InputSteam to read
     * @param map MultiMap to add parameters to
     * @param maxLength maximum form length to decode
     * @param maxKeys the maximum number of keys to read or -1 for no limit 
     * @throws IOException if unable to decode input stream
     */
    public static void decodeUtf8To(InputStream in, MultiMap<String> map, int maxLength, int maxKeys)
    throws IOException
    {
        synchronized(map)
        {
            Utf8StringBuilder buffer = new Utf8StringBuilder();
            String key = null;
            String value = null;
            
            int b;
            
            int totalLength=0;
            while ((b=in.read())>=0)
            {
                try
                {
                    switch ((char) b)
                    {
                        case '&':
                            value = buffer.toReplacedString();
                            buffer.reset();
                            if (key != null)
                            {
                                map.add(key,value);
                            }
                            else if (value!=null&&value.length()>0)
                            {
                                map.add(value,"");
                            }
                            key = null;
                            value=null;
                            if (maxKeys>0 && map.size()>maxKeys)
                                throw new IllegalStateException(String.format("Form with too many keys [%d > %d]",map.size(),maxKeys));
                            break;

                        case '=':
                            if (key!=null)
                            {
                                buffer.append((byte)b);
                                break;
                            }
                            key = buffer.toReplacedString(); 
                            buffer.reset();
                            break;

                        case '+':
                            buffer.append((byte)' ');
                            break;

                        case '%':
                            int code0=in.read();
                            boolean decoded=false;
                            if ('u'==code0)
                            {
                                code0=in.read(); // XXX: we have to read the next byte, otherwise code0 is always 'u'
                                if (code0>=0)
                                {
                                    int code1=in.read();
                                    if (code1>=0)
                                    {
                                        int code2=in.read();
                                        if (code2>=0)
                                        {
                                            int code3=in.read();
                                            if (code3>=0)
                                            {
                                                buffer.getStringBuilder().append(Character.toChars
                                                    ((convertHexDigit(code0)<<12)+(convertHexDigit(code1)<<8)+(convertHexDigit(code2)<<4)+convertHexDigit(code3)));
                                                decoded=true;
                                            }
                                        }
                                    }
                                }
                            }
                            else if (code0>=0)
                            {
                                int code1=in.read();
                                if (code1>=0)
                                {
                                    buffer.append((byte)((convertHexDigit(code0)<<4)+convertHexDigit(code1)));
                                    decoded=true;
                                }
                            }
                            
                            if (!decoded)
                                buffer.getStringBuilder().append(Utf8Appendable.REPLACEMENT);

                            break;
                          
                        default:
                            buffer.append((byte)b);
                            break;
                    }
                }
                catch(NotUtf8Exception e)
                {
                    LOG.warn(e.toString());
                    LOG.debug(e);
                }
                catch(NumberFormatException e)
                {
                    buffer.append(Utf8Appendable.REPLACEMENT_UTF8,0,3);
                    LOG.warn(e.toString());
                    LOG.debug(e);
                }
                if (maxLength>=0 && (++totalLength > maxLength))
                    throw new IllegalStateException(String.format("Form with too many keys [%d > %d]",map.size(),maxKeys));
            }
            
            if (key != null)
            {
                value = buffer.toReplacedString();
                buffer.reset();
                map.add(key,value);
            }
            else if (buffer.length()>0)
            {
                map.add(buffer.toReplacedString(), "");
            }
        }
    }
    
    /* -------------------------------------------------------------- */
    public static void decodeUtf16To(InputStream in, MultiMap<String> map, int maxLength, int maxKeys) throws IOException
    {
        InputStreamReader input = new InputStreamReader(in,StandardCharsets.UTF_16);
        StringWriter buf = new StringWriter(8192);
        IO.copy(input,buf,maxLength);
        
        // TODO implement maxKeys
        decodeTo(buf.getBuffer().toString(),map,StandardCharsets.UTF_16);
    }

    /* -------------------------------------------------------------- */
    /** Decoded parameters to Map.
     * @param in the stream containing the encoded parameters
     * @param map the MultiMap to decode into
     * @param charset the charset to use for decoding
     * @param maxLength the maximum length of the form to decode
     * @param maxKeys the maximum number of keys to decode
     * @throws IOException if unable to decode input stream
     */
    public static void decodeTo(InputStream in, MultiMap<String> map, String charset, int maxLength, int maxKeys)
    throws IOException
    {
        if (charset==null)
        {
            if (ENCODING.equals(StandardCharsets.UTF_8))
                decodeUtf8To(in,map,maxLength,maxKeys);
            else
                decodeTo(in,map,ENCODING,maxLength,maxKeys);
        }
        else if (StringUtil.__UTF8.equalsIgnoreCase(charset))
            decodeUtf8To(in,map,maxLength,maxKeys);
        else if (StringUtil.__ISO_8859_1.equalsIgnoreCase(charset))
            decode88591To(in,map,maxLength,maxKeys);
        else if (StringUtil.__UTF16.equalsIgnoreCase(charset))
            decodeUtf16To(in,map,maxLength,maxKeys);
        else
            decodeTo(in,map,Charset.forName(charset),maxLength,maxKeys);
    }
    
    /* -------------------------------------------------------------- */
    /** Decoded parameters to Map.
     * @param in the stream containing the encoded parameters
     * @param map the MultiMap to decode into
     * @param charset the charset to use for decoding
     * @param maxLength the maximum length of the form to decode
     * @param maxKeys the maximum number of keys to decode
     * @throws IOException if unable to decode input stream
     */
    public static void decodeTo(InputStream in, MultiMap<String> map, Charset charset, int maxLength, int maxKeys)
    throws IOException
    {
        //no charset present, use the configured default
        if (charset==null) 
           charset=ENCODING;
            
        if (StandardCharsets.UTF_8.equals(charset))
        {
            decodeUtf8To(in,map,maxLength,maxKeys);
            return;
        }
        
        if (StandardCharsets.ISO_8859_1.equals(charset))
        {
            decode88591To(in,map,maxLength,maxKeys);
            return;
        }

        if (StandardCharsets.UTF_16.equals(charset)) // Should be all 2 byte encodings
        {
            decodeUtf16To(in,map,maxLength,maxKeys);
            return;
        }

        synchronized(map)
        {
            String key = null;
            String value = null;
            
            int c;
            
            int totalLength = 0;
            
            try(ByteArrayOutputStream2 output = new ByteArrayOutputStream2();)
            {
                int size=0;

                while ((c=in.read())>0)
                {
                    switch ((char) c)
                    {
                        case '&':
                            size=output.size();
                            value = size==0?"":output.toString(charset);
                            output.setCount(0);
                            if (key != null)
                            {
                                map.add(key,value);
                            }
                            else if (value!=null&&value.length()>0)
                            {
                                map.add(value,"");
                            }
                            key = null;
                            value=null;
                            if (maxKeys>0 && map.size()>maxKeys)
                                throw new IllegalStateException(String.format("Form with too many keys [%d > %d]",map.size(),maxKeys));
                            break;
                        case '=':
                            if (key!=null)
                            {
                                output.write(c);
                                break;
                            }
                            size=output.size();
                            key = size==0?"":output.toString(charset);
                            output.setCount(0);
                            break;
                        case '+':
                            output.write(' ');
                            break;
                        case '%':
                            int code0=in.read();
                            if ('u'==code0)
                            {
                                int code1=in.read();
                                if (code1>=0)
                                {
                                    int code2=in.read();
                                    if (code2>=0)
                                    {
                                        int code3=in.read();
                                        if (code3>=0)
                                            output.write(new String(Character.toChars((convertHexDigit(code0)<<12)+(convertHexDigit(code1)<<8)+(convertHexDigit(code2)<<4)+convertHexDigit(code3))).getBytes(charset));
                                    }
                                }

                            }
                            else if (code0>=0)
                            {
                                int code1=in.read();
                                if (code1>=0)
                                    output.write((convertHexDigit(code0)<<4)+convertHexDigit(code1));
                            }
                            break;
                        default:
                            output.write(c);
                            break;
                    }

                    totalLength++;
                    if (maxLength>=0 && totalLength > maxLength)
                        throw new IllegalStateException(String.format("Form with too many keys [%d > %d]",map.size(),maxKeys));
                }

                size=output.size();
                if (key != null)
                {
                    value = size==0?"":output.toString(charset);
                    output.setCount(0);
                    map.add(key,value);
                }
                else if (size>0)
                    map.add(output.toString(charset),"");
            }
        }
    }

    /* -------------------------------------------------------------- */
    /** Decode String with % encoding.
     * This method makes the assumption that the majority of calls
     * will need no decoding.
     * @param encoded the encoded string to decode
     * @return the decoded string
     */
    public static String decodeString(String encoded)
    {
        return decodeString(encoded,0,encoded.length(),ENCODING);
    }
    
    /* -------------------------------------------------------------- */
    /** Decode String with % encoding.
     * This method makes the assumption that the majority of calls
     * will need no decoding.
     * @param encoded the encoded string to decode
     * @param offset the offset in the encoded string to decode from
     * @param length the length of characters in the encoded string to decode
     * @param charset the charset to use for decoding
     * @return the decoded string
     */
    public static String decodeString(String encoded,int offset,int length,Charset charset)
    {
        if (charset==null || StandardCharsets.UTF_8.equals(charset))
        {
            Utf8StringBuffer buffer=null;

            for (int i=0;i<length;i++)
            {
                char c = encoded.charAt(offset+i);
                if (c<0||c>0xff)
                {
                    if (buffer==null)
                    {
                        buffer=new Utf8StringBuffer(length);
                        buffer.getStringBuffer().append(encoded,offset,offset+i+1);
                    }
                    else
                        buffer.getStringBuffer().append(c);
                }
                else if (c=='+')
                {
                    if (buffer==null)
                    {
                        buffer=new Utf8StringBuffer(length);
                        buffer.getStringBuffer().append(encoded,offset,offset+i);
                    }
                    
                    buffer.getStringBuffer().append(' ');
                }
                else if (c=='%')
                {
                    if (buffer==null)
                    {
                        buffer=new Utf8StringBuffer(length);
                        buffer.getStringBuffer().append(encoded,offset,offset+i);
                    }
                    
                    if ((i+2)<length)
                    {
                        try
                        {
                            if ('u'==encoded.charAt(offset+i+1))
                            {
                                if((i+5)<length)
                                {
                                    int o=offset+i+2;
                                    i+=5;
                                    String unicode = new String(Character.toChars(TypeUtil.parseInt(encoded,o,4,16)));
                                    buffer.getStringBuffer().append(unicode); 
                                }
                                else
                                {
                                    i=length;
                                    buffer.getStringBuffer().append(Utf8Appendable.REPLACEMENT); 
                                }
                            }
                            else
                            {
                                int o=offset+i+1;
                                i+=2;
                                byte b=(byte)TypeUtil.parseInt(encoded,o,2,16);
                                buffer.append(b);
                            }
                        }
                        catch(NotUtf8Exception e)
                        {
                            LOG.warn(e.toString());
                            LOG.debug(e);
                        }
                        catch(NumberFormatException e)
                        {
                            LOG.warn(e.toString());
                            LOG.debug(e);
                            buffer.getStringBuffer().append(Utf8Appendable.REPLACEMENT);  
                        }
                    }
                    else
                    {
                        buffer.getStringBuffer().append(Utf8Appendable.REPLACEMENT); 
                        i=length;
                    }
                }
                else if (buffer!=null)
                    buffer.getStringBuffer().append(c);
            }

            if (buffer==null)
            {
                if (offset==0 && encoded.length()==length)
                    return encoded;
                return encoded.substring(offset,offset+length);
            }

            return buffer.toReplacedString();
        }
        else
        {
            StringBuffer buffer=null;

            for (int i=0;i<length;i++)
            {
                char c = encoded.charAt(offset+i);
                if (c<0||c>0xff)
                {
                    if (buffer==null)
                    {
                        buffer=new StringBuffer(length);
                        buffer.append(encoded,offset,offset+i+1);
                    }
                    else
                        buffer.append(c);
                }
                else if (c=='+')
                {
                    if (buffer==null)
                    {
                        buffer=new StringBuffer(length);
                        buffer.append(encoded,offset,offset+i);
                    }

                    buffer.append(' ');
                }
                else if (c=='%')
                {
                    if (buffer==null)
                    {
                        buffer=new StringBuffer(length);
                        buffer.append(encoded,offset,offset+i);
                    }

                    byte[] ba=new byte[length];
                    int n=0;
                    while(c>=0 && c<=0xff)
                    {
                        if (c=='%')
                        {   
                            if(i+2<length)
                            {
                                try
                                {
                                    if ('u'==encoded.charAt(offset+i+1))
                                    {
                                        if (i+6<length)
                                        {
                                            int o=offset+i+2;
                                            i+=6;
                                            String unicode = new String(Character.toChars(TypeUtil.parseInt(encoded,o,4,16)));
                                            byte[] reencoded = unicode.getBytes(charset);
                                            System.arraycopy(reencoded,0,ba,n,reencoded.length);
                                            n+=reencoded.length;
                                        }
                                        else
                                        {
                                            ba[n++] = (byte)'?';
                                            i=length;
                                        }
                                    }
                                    else
                                    {
                                        int o=offset+i+1;
                                        i+=3;
                                        ba[n]=(byte)TypeUtil.parseInt(encoded,o,2,16);
                                        n++;
                                    }
                                }
                                catch(Exception e)
                                {   
                                    LOG.warn(e.toString());
                                    LOG.debug(e);
                                    ba[n++] = (byte)'?';
                                }
                            }
                            else
                            {
                                    ba[n++] = (byte)'?';
                                    i=length;
                            }
                        }
                        else if (c=='+')
                        {
                            ba[n++]=(byte)' ';
                            i++;
                        }
                        else
                        {
                            ba[n++]=(byte)c;
                            i++;
                        }

                        if (i>=length)
                            break;
                        c = encoded.charAt(offset+i);
                    }

                    i--;
                    buffer.append(new String(ba,0,n,charset));

                }
                else if (buffer!=null)
                    buffer.append(c);
            }

            if (buffer==null)
            {
                if (offset==0 && encoded.length()==length)
                    return encoded;
                return encoded.substring(offset,offset+length);
            }

            return buffer.toString();
        }

    }
    
    /* ------------------------------------------------------------ */
    /** Perform URL encoding.
     * @param string the string to encode
     * @return encoded string.
     */
    public static String encodeString(String string)
    {
        return encodeString(string,ENCODING);
    }
    
    /* ------------------------------------------------------------ */
    /** Perform URL encoding.
     * @param string the string to encode
     * @param charset the charset to use for encoding
     * @return encoded string.
     */
    public static String encodeString(String string,Charset charset)
    {
        if (charset==null)
            charset=ENCODING;
        byte[] bytes=null;
        bytes=string.getBytes(charset);
        
        int len=bytes.length;
        byte[] encoded= new byte[bytes.length*3];
        int n=0;
        boolean noEncode=true;
        
        for (int i=0;i<len;i++)
        {
            byte b = bytes[i];
            
            if (b==' ')
            {
                noEncode=false;
                encoded[n++]=(byte)'+';
            }
            else if (b>='a' && b<='z' ||
                     b>='A' && b<='Z' ||
                     b>='0' && b<='9')
            {
                encoded[n++]=b;
            }
            else
            {
                noEncode=false;
                encoded[n++]=(byte)'%';
                byte nibble= (byte) ((b&0xf0)>>4);
                if (nibble>=10)
                    encoded[n++]=(byte)('A'+nibble-10);
                else
                    encoded[n++]=(byte)('0'+nibble);
                nibble= (byte) (b&0xf);
                if (nibble>=10)
                    encoded[n++]=(byte)('A'+nibble-10);
                else
                    encoded[n++]=(byte)('0'+nibble);
            }
        }

        if (noEncode)
            return string;
        
        return new String(encoded,0,n,charset);
    }


    /* ------------------------------------------------------------ */
    /** 
     */
    @Override
    public Object clone()
    {
        return new UrlEncoded(this);
    }
}
