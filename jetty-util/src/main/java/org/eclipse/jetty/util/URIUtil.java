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

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

import org.eclipse.jetty.util.Utf8Appendable.NotUtf8Exception;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/** 
 * URI Utility methods.
 * <p>
 * This class assists with the decoding and encoding or HTTP URI's.
 * It differs from the java.net.URL class as it does not provide
 * communications ability, but it does assist with query string
 * formatting.
 * </p>
 * 
 * @see UrlEncoded
 */
public class URIUtil
    implements Cloneable
{
    private static final Logger LOG = Log.getLogger(URIUtil.class);
    public static final String SLASH="/";
    public static final String HTTP="http";
    public static final String HTTPS="https";
    private static final Pattern __PATH_SPLIT = Pattern.compile("(?<=\\/)");

    // Use UTF-8 as per http://www.w3.org/TR/html40/appendix/notes.html#non-ascii-chars
    public static final Charset __CHARSET=StandardCharsets.UTF_8 ;

    private URIUtil()
    {}
    
    /* ------------------------------------------------------------ */
    /** Encode a URI path.
     * This is the same encoding offered by URLEncoder, except that
     * the '/' character is not encoded.
     * @param path The path the encode
     * @return The encoded path
     */
    public static String encodePath(String path)
    {
        if (path==null || path.length()==0)
            return path;
        
        StringBuilder buf = encodePath(null,path,0);
        return buf==null?path:buf.toString();
    }

    /* ------------------------------------------------------------ */
    /** Encode a URI path.
     * @param path The path the encode
     * @param buf StringBuilder to encode path into (or null)
     * @return The StringBuilder or null if no substitutions required.
     */
    public static StringBuilder encodePath(StringBuilder buf, String path)
    {
        return encodePath(buf,path,0);
    }

    /* ------------------------------------------------------------ */
    /** Encode a URI path.
     * @param path The path the encode
     * @param buf StringBuilder to encode path into (or null)
     * @return The StringBuilder or null if no substitutions required.
     */
    private static StringBuilder encodePath(StringBuilder buf, String path, int offset)
    {
        byte[] bytes=null;
        if (buf==null)
        {
            loop: for (int i=offset;i<path.length();i++)
            {
                char c=path.charAt(i);
                switch(c)
                {
                    case '%':
                    case '?':
                    case ';':
                    case '#':
                    case '"':
                    case '\'':
                    case '<':
                    case '>':
                    case ' ':
                    case '[':
                    case '\\':
                    case ']':
                    case '^':
                    case '`':
                    case '{':
                    case '|':
                    case '}':
                        buf=new StringBuilder(path.length()*2);
                        break loop;
                    default:
                        if (c>127)
                        {
                            bytes=path.getBytes(URIUtil.__CHARSET);
                            buf=new StringBuilder(path.length()*2);
                            break loop;
                        }
                }
            }
            if (buf==null)
                return null;
        }

        int i;

        loop: for (i=offset;i<path.length();i++)
        {
            char c=path.charAt(i);
            switch(c)
            {
                case '%':
                    buf.append("%25");
                    continue;
                case '?':
                    buf.append("%3F");
                    continue;
                case ';':
                    buf.append("%3B");
                    continue;
                case '#':
                    buf.append("%23");
                    continue;
                case '"':
                    buf.append("%22");
                    continue;
                case '\'':
                    buf.append("%27");
                    continue;
                case '<':
                    buf.append("%3C");
                    continue;
                case '>':
                    buf.append("%3E");
                    continue;
                case ' ':
                    buf.append("%20");
                    continue;
                case '[':
                    buf.append("%5B");
                    continue;
                case '\\':
                    buf.append("%5C");
                    continue;
                case ']':
                    buf.append("%5D");
                    continue;
                case '^':
                    buf.append("%5E");
                    continue;
                case '`':
                    buf.append("%60");
                    continue;
                case '{':
                    buf.append("%7B");
                    continue;
                case '|':
                    buf.append("%7C");
                    continue;
                case '}':
                    buf.append("%7D");
                    continue;

                default:
                    if (c>127)
                    {
                        bytes=path.getBytes(URIUtil.__CHARSET);
                        break loop;
                    }
                    buf.append(c);
            }
        }

        if (bytes!=null)
        {
            for (;i<bytes.length;i++)
            {
                byte c=bytes[i];
                switch(c)
                {
                    case '%':
                        buf.append("%25");
                        continue;
                    case '?':
                        buf.append("%3F");
                        continue;
                    case ';':
                        buf.append("%3B");
                        continue;
                    case '#':
                        buf.append("%23");
                        continue;
                    case '"':
                        buf.append("%22");
                        continue;
                    case '\'':
                        buf.append("%27");
                        continue;
                    case '<':
                        buf.append("%3C");
                        continue;
                    case '>':
                        buf.append("%3E");
                        continue;
                    case ' ':
                        buf.append("%20");
                        continue;
                    case '[':
                        buf.append("%5B");
                        continue;
                    case '\\':
                        buf.append("%5C");
                        continue;
                    case ']':
                        buf.append("%5D");
                        continue;
                    case '^':
                        buf.append("%5E");
                        continue;
                    case '`':
                        buf.append("%60");
                        continue;
                    case '{':
                        buf.append("%7B");
                        continue;
                    case '|':
                        buf.append("%7C");
                        continue;
                    case '}':
                        buf.append("%7D");
                        continue;
                    default:
                        if (c<0)
                        {
                            buf.append('%');
                            TypeUtil.toHex(c,buf);
                        }
                        else
                            buf.append((char)c);
                }
            }
        }

        return buf;
    }
    
    /* ------------------------------------------------------------ */
    /** Encode a URI path.
     * @param path The path the encode
     * @param buf StringBuilder to encode path into (or null)
     * @param encode String of characters to encode. % is always encoded.
     * @return The StringBuilder or null if no substitutions required.
     */
    public static StringBuilder encodeString(StringBuilder buf,
                                             String path,
                                             String encode)
    {
        if (buf==null)
        {
            for (int i=0;i<path.length();i++)
            {
                char c=path.charAt(i);
                if (c=='%' || encode.indexOf(c)>=0)
                {    
                    buf=new StringBuilder(path.length()<<1);
                    break;
                }
            }
            if (buf==null)
                return null;
        }

        for (int i=0;i<path.length();i++)
        {
            char c=path.charAt(i);
            if (c=='%' || encode.indexOf(c)>=0)
            {
                buf.append('%');
                StringUtil.append(buf,(byte)(0xff&c),16);
            }
            else
                buf.append(c);
        }

        return buf;
    }
    
    /* ------------------------------------------------------------ */
    /* Decode a URI path and strip parameters
     */
    public static String decodePath(String path)
    {
        return decodePath(path,0,path.length());
    }

    /* ------------------------------------------------------------ */
    /* Decode a URI path and strip parameters of UTF-8 path
     */
    public static String decodePath(String path, int offset, int length)
    {
        try
        {
            Utf8StringBuilder builder=null;
            int end=offset+length;
            for (int i=offset;i<end;i++)
            {
                char c = path.charAt(i);
                switch(c)
                {
                    case '%':
                        if (builder==null)
                        {
                            builder=new Utf8StringBuilder(path.length());
                            builder.append(path,offset,i-offset);
                        }
                        if ((i+2)<end)
                        {
                            char u=path.charAt(i+1);
                            if (u=='u')
                            {
                                // TODO this is wrong. This is a codepoint not a char
                                builder.append((char)(0xffff&TypeUtil.parseInt(path,i+2,4,16)));
                                i+=5;
                            }
                            else
                            {
                                builder.append((byte)(0xff&(TypeUtil.convertHexDigit(u)*16+TypeUtil.convertHexDigit(path.charAt(i+2)))));
                                i+=2;
                            }
                        }
                        else
                        {
                            throw new IllegalArgumentException("Bad URI % encoding");
                        }

                        break;

                    case ';':
                        if (builder==null)
                        {
                            builder=new Utf8StringBuilder(path.length());
                            builder.append(path,offset,i-offset);
                        }
                        
                        while(++i<end)
                        {
                            if (path.charAt(i)=='/')
                            {
                                builder.append('/');
                                break;
                            }
                        }
                        
                        break;

                    default:
                        if (builder!=null)
                            builder.append(c);
                        break;
                }
            }

            if (builder!=null)
                return builder.toString();
            if (offset==0 && length==path.length())
                return path;
            return path.substring(offset,end);   
        }
        catch(NotUtf8Exception e)
        {
            LOG.warn(path.substring(offset,offset+length)+" "+e);
            LOG.debug(e);
            return decodeISO88591Path(path,offset,length);
        }
    }

    
    /* ------------------------------------------------------------ */
    /* Decode a URI path and strip parameters of ISO-8859-1 path
     */
    private static String decodeISO88591Path(String path, int offset, int length)
    {
        StringBuilder builder=null;
        int end=offset+length;
        for (int i=offset;i<end;i++)
        {
            char c = path.charAt(i);
            switch(c)
            {
                case '%':
                    if (builder==null)
                    {
                        builder=new StringBuilder(path.length());
                        builder.append(path,offset,i-offset);
                    }
                    if ((i+2)<end)
                    {
                        char u=path.charAt(i+1);
                        if (u=='u')
                        {
                            // TODO this is wrong. This is a codepoint not a char
                            builder.append((char)(0xffff&TypeUtil.parseInt(path,i+2,4,16)));
                            i+=5;
                        }
                        else
                        {
                            builder.append((byte)(0xff&(TypeUtil.convertHexDigit(u)*16+TypeUtil.convertHexDigit(path.charAt(i+2)))));
                            i+=2;
                        }
                    }
                    else
                    {
                        throw new IllegalArgumentException();
                    }
                    
                    break;
                    
                case ';':
                    if (builder==null)
                    {
                        builder=new StringBuilder(path.length());
                        builder.append(path,offset,i-offset);
                    }
                    while(++i<end)
                    {
                        if (path.charAt(i)=='/')
                        {
                            builder.append('/');
                            break;
                        }
                    }
                    break;
                    
                    
                default:
                    if (builder!=null)
                        builder.append(c);
                    break;
            }
        }

        if (builder!=null)
            return builder.toString();
        if (offset==0 && length==path.length())
            return path;
        return path.substring(offset,end);        
    }

    
    /* ------------------------------------------------------------ */
    /** Add two URI path segments.
     * Handles null and empty paths, path and query params (eg ?a=b or
     * ;JSESSIONID=xxx) and avoids duplicate '/'
     * @param p1 URI path segment (should be encoded)
     * @param p2 URI path segment (should be encoded)
     * @return Legally combined path segments.
     */
    public static String addPaths(String p1, String p2)
    {
        if (p1==null || p1.length()==0)
        {
            if (p1!=null && p2==null)
                return p1;
            return p2;
        }
        if (p2==null || p2.length()==0)
            return p1;
        
        int split=p1.indexOf(';');
        if (split<0)
            split=p1.indexOf('?');
        if (split==0)
            return p2+p1;
        if (split<0)
            split=p1.length();

        StringBuilder buf = new StringBuilder(p1.length()+p2.length()+2);
        buf.append(p1);
        
        if (buf.charAt(split-1)=='/')
        {
            if (p2.startsWith(URIUtil.SLASH))
            {
                buf.deleteCharAt(split-1);
                buf.insert(split-1,p2);
            }
            else
                buf.insert(split,p2);
        }
        else
        {
            if (p2.startsWith(URIUtil.SLASH))
                buf.insert(split,p2);
            else
            {
                buf.insert(split,'/');
                buf.insert(split+1,p2);
            }
        }

        return buf.toString();
    }
    
    /* ------------------------------------------------------------ */
    /** Return the parent Path.
     * Treat a URI like a directory path and return the parent directory.
     * @param p the path to return a parent reference to
     * @return the parent path of the URI
     */
    public static String parentPath(String p)
    {
        if (p==null || URIUtil.SLASH.equals(p))
            return null;
        int slash=p.lastIndexOf('/',p.length()-2);
        if (slash>=0)
            return p.substring(0,slash+1);
        return null;
    }
    
    /* ------------------------------------------------------------ */
    /** Convert a path to a cananonical form.
     * All instances of "." and ".." are factored out.  Null is returned
     * if the path tries to .. above its root.
     * @param path the path to convert
     * @return path or null.
     */
    public static String canonicalPath(String path)
    {
        if (path == null || path.isEmpty() || !path.contains("."))
            return path;

        if(path.startsWith("/.."))
            return null;

        List<String> directories = new ArrayList<>();
        Collections.addAll(directories, __PATH_SPLIT.split(path));
        
        for(ListIterator<String> iterator = directories.listIterator(); iterator.hasNext();)
        {
            switch (iterator.next()) {
                case "./":
                case ".":
                    if (iterator.hasNext() && directories.get(iterator.nextIndex()).equals("/"))
                        break;

                    iterator.remove();
                    break;
                case "../":
                case "..":
                    if(iterator.previousIndex() == 0)
                        return null;

                    iterator.remove();
                    if(iterator.previous().equals("/") && iterator.nextIndex() == 0)
                        return null;

                    iterator.remove();
                    break;
            }
        }

        return String.join("", directories);
    }

    /* ------------------------------------------------------------ */
    /** Convert a path to a compact form.
     * All instances of "//" and "///" etc. are factored out to single "/" 
     * @param path the path to compact 
     * @return the compacted path 
     */
    public static String compactPath(String path)
    {
        if (path==null || path.length()==0)
            return path;

        int state=0;
        int end=path.length();
        int i=0;
        
        loop:
        while (i<end)
        {
            char c=path.charAt(i);
            switch(c)
            {
                case '?':
                    return path;
                case '/':
                    state++;
                    if (state==2)
                        break loop;
                    break;
                default:
                    state=0;
            }
            i++;
        }
        
        if (state<2)
            return path;
        
        StringBuilder buf = new StringBuilder(path.length());
        buf.append(path,0,i);
        
        loop2:
        while (i<end)
        {
            char c=path.charAt(i);
            switch(c)
            {
                case '?':
                    buf.append(path,i,end);
                    break loop2;
                case '/':
                    if (state++==0)
                        buf.append(c);
                    break;
                default:
                    state=0;
                    buf.append(c);
            }
            i++;
        }
        
        return buf.toString();
    }

    /* ------------------------------------------------------------ */
    /** 
     * @param uri URI
     * @return True if the uri has a scheme
     */
    public static boolean hasScheme(String uri)
    {
        for (int i=0;i<uri.length();i++)
        {
            char c=uri.charAt(i);
            if (c==':')
                return true;
            if (!(c>='a'&&c<='z' ||
                  c>='A'&&c<='Z' ||
                  (i>0 &&(c>='0'&&c<='9' ||
                          c=='.' ||
                          c=='+' ||
                          c=='-'))
                  ))
                break;
        }
        return false;
    }

    /* ------------------------------------------------------------ */
    /**
     * Create a new URI from the arguments, handling IPv6 host encoding and default ports
     * @param scheme the URI scheme
     * @param server the URI server
     * @param port the URI port
     * @param path the URI path
     * @param query the URI query
     * @return A String URI
     */
    public static String newURI(String scheme,String server, int port,String path,String query)
    {
        StringBuilder builder = newURIBuilder(scheme, server, port);
        builder.append(path);
        if (query!=null && query.length()>0)
            builder.append('?').append(query);
        return builder.toString();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Create a new URI StringBuilder from the arguments, handling IPv6 host encoding and default ports
     * @param scheme the URI scheme
     * @param server the URI server
     * @param port the URI port
     * @return a StringBuilder containing URI prefix
     */
    public static StringBuilder newURIBuilder(String scheme,String server, int port)
    {
        StringBuilder builder = new StringBuilder();
        appendSchemeHostPort(builder, scheme, server, port);
        return builder;
    }

    /* ------------------------------------------------------------ */
    /** 
     * Append scheme, host and port URI prefix, handling IPv6 address encoding and default ports
     * @param url StringBuilder to append to
     * @param scheme the URI scheme
     * @param server the URI server
     * @param port the URI port
     */
    public static void appendSchemeHostPort(StringBuilder url,String scheme,String server, int port)
    {
        url.append(scheme).append("://").append(HostPort.normalizeHost(server));

        if (port > 0)
        {
            switch(scheme)
            {
                case "http":
                    if (port!=80) 
                        url.append(':').append(port);
                    break;
                    
                case "https":
                    if (port!=443) 
                        url.append(':').append(port);
                    break;

                default:
                    url.append(':').append(port);
            }
        }
    }
    
    /* ------------------------------------------------------------ */
    /** 
     * Append scheme, host and port URI prefix, handling IPv6 address encoding and default ports
     * @param url StringBuffer to append to
     * @param scheme the URI scheme
     * @param server the URI server
     * @param port the URI port
     */
    public static void appendSchemeHostPort(StringBuffer url,String scheme,String server, int port)
    {
        synchronized (url)
        {
            url.append(scheme).append("://").append(HostPort.normalizeHost(server));

            if (port > 0)
            {
                switch(scheme)
                {
                    case "http":
                        if (port!=80) 
                            url.append(':').append(port);
                        break;
                        
                    case "https":
                        if (port!=443) 
                            url.append(':').append(port);
                        break;

                    default:
                        url.append(':').append(port);
                }
            }
        }
    }

    public static boolean equalsIgnoreEncodings(String uriA, String uriB)
    {
        int lenA=uriA.length();
        int lenB=uriB.length();
        int a=0;
        int b=0;
        
        while (a<lenA && b<lenB)
        {
            int oa=uriA.charAt(a++);
            int ca=oa;
            if (ca=='%')
                ca=TypeUtil.convertHexDigit(uriA.charAt(a++))*16+TypeUtil.convertHexDigit(uriA.charAt(a++));
            
            int ob=uriB.charAt(b++);
            int cb=ob;
            if (cb=='%')
                cb=TypeUtil.convertHexDigit(uriB.charAt(b++))*16+TypeUtil.convertHexDigit(uriB.charAt(b++));
            
            if (ca=='/' && oa!=ob)
                return false;
            
            if (ca!=cb )
                return URIUtil.decodePath(uriA).equals(URIUtil.decodePath(uriB));
        }
        return a==lenA && b==lenB;
    }

    public static boolean equalsIgnoreEncodings(URI uriA, URI uriB)
    {
        if (uriA.equals(uriB))
            return true;

        if (uriA.getScheme()==null)
        {
            if (uriB.getScheme()!=null)
                return false;
        }
        else if (!uriA.getScheme().equals(uriB.getScheme()))
            return false;

        if (uriA.getAuthority()==null)
        {
            if (uriB.getAuthority()!=null)
                return false;
        }
        else if (!uriA.getAuthority().equals(uriB.getAuthority()))
            return false;

        return equalsIgnoreEncodings(uriA.getPath(),uriB.getPath());
    }

    public static URI addDecodedPath(URI uri, String path)
    {
        String base = uri.toASCIIString();
        StringBuilder buf = new StringBuilder(base.length()+path.length()*3);
        buf.append(base);
        if (buf.charAt(base.length()-1)!='/')
            buf.append('/');

        byte[] bytes=null;
        int offset=path.charAt(0)=='/'?1:0;
        encodePath(buf,path,offset);

        return URI.create(buf.toString());
    }
    
    public static URI getJarSource(URI uri)
    {
        try
        {
            if (!"jar".equals(uri.getScheme()))
                return uri;
            String s = uri.getSchemeSpecificPart();
            int bang_slash = s.indexOf("!/");
            if (bang_slash>=0)
                s=s.substring(0,bang_slash);
            return new URI(s);
        }
        catch(URISyntaxException e)
        {
            throw new IllegalArgumentException(e);
        }
    }
    
    public static String getJarSource(String uri)
    {
        if (!uri.startsWith("jar:"))
            return uri;
        int bang_slash = uri.indexOf("!/");
        return (bang_slash>=0)?uri.substring(4,bang_slash):uri.substring(4);
    }
}
