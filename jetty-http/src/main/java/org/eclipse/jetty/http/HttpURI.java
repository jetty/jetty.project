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

package org.eclipse.jetty.http;

import java.io.UnsupportedEncodingException;
import java.net.URI;

import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.Utf8StringBuilder;


/* ------------------------------------------------------------ */
/** Http URI.
 * Parse a HTTP URI from a string or byte array.  Given a URI
 * <code>http://user@host:port/path/info;param?query#fragment</code>
 * this class will split it into the following undecoded optional elements:<ul>
 * <li>{@link #getScheme()} - http:</li>
 * <li>{@link #getAuthority()} - //name@host:port</li>
 * <li>{@link #getHost()} - host</li>
 * <li>{@link #getPort()} - port</li>
 * <li>{@link #getPath()} - /path/info</li>
 * <li>{@link #getParam()} - param</li>
 * <li>{@link #getQuery()} - query</li>
 * <li>{@link #getFragment()} - fragment</li>
 * </ul>
 *
 */
public class HttpURI
{
    private static final byte[] __empty={};
    private final static int
    START=0,
    AUTH_OR_PATH=1,
    SCHEME_OR_PATH=2,
    AUTH=4,
    IPV6=5,
    PORT=6,
    PATH=7,
    PARAM=8,
    QUERY=9,
    ASTERISK=10;

    boolean _partial=false;
    byte[] _raw=__empty;
    String _rawString;
    int _scheme;
    int _authority;
    int _host;
    int _port;
    int _portValue;
    int _path;
    int _param;
    int _query;
    int _fragment;
    int _end;
    boolean _encoded=false;

    final Utf8StringBuilder _utf8b = new Utf8StringBuilder(64);

    public HttpURI()
    {

    }

    /* ------------------------------------------------------------ */
    /**
     * @param parsePartialAuth If True, parse auth without prior scheme, else treat all URIs starting with / as paths
     */
    public HttpURI(boolean parsePartialAuth)
    {
        _partial=parsePartialAuth;
    }

    public HttpURI(String raw)
    {
        _rawString=raw;
        byte[] b;
        try
        {
            b = raw.getBytes(StringUtil.__UTF8);
        }
        catch (UnsupportedEncodingException e)
        {
           throw new RuntimeException(e.getMessage());
        }
        parse(b,0,b.length);
    }

    public HttpURI(byte[] raw,int offset, int length)
    {
        parse2(raw,offset,length);
    }
    
    public HttpURI(URI uri)
    {
        parse(uri.toASCIIString());
    }

    public void parse(String raw)
    {
        byte[] b = raw.getBytes();
        parse2(b,0,b.length);
        _rawString=raw;
    }

    public void parse(byte[] raw,int offset, int length)
    {
        _rawString=null;
        parse2(raw,offset,length);
    }


    public void parseConnect(byte[] raw,int offset, int length)
    {
        _rawString=null;
        _encoded=false;
        _raw=raw;
        int i=offset;
        int e=offset+length;
        int state=AUTH;
        _end=offset+length;
        _scheme=offset;
        _authority=offset;
        _host=offset;
        _port=_end;
        _portValue=-1;
        _path=_end;
        _param=_end;
        _query=_end;
        _fragment=_end;

        loop: while (i<e)
        {
            char c=(char)(0xff&_raw[i]);
            int s=i++;

            switch (state)
            {
                case AUTH:
                {
                    switch (c)
                    {
                        case ':':
                        {
                            _port = s;
                            break loop;
                        }
                        case '[':
                        {
                            state = IPV6;
                            break;
                        }
                    }
                    continue;
                }

                case IPV6:
                {
                    switch (c)
                    {
                        case '/':
                        {
                            throw new IllegalArgumentException("No closing ']' for " + StringUtil.toString(_raw,offset,length,URIUtil.__CHARSET));
                        }
                        case ']':
                        {
                            state = AUTH;
                            break;
                        }
                    }

                    continue;
                }
            }
        }

        if (_port<_path)
            _portValue=TypeUtil.parseInt(_raw, _port+1, _path-_port-1,10);
        else
            throw new IllegalArgumentException("No port");
        _path=offset;
    }


    private void parse2(byte[] raw,int offset, int length)
    {
        _encoded=false;
        _raw=raw;
        int i=offset;
        int e=offset+length;
        int state=START;
        int m=offset;
        _end=offset+length;
        _scheme=offset;
        _authority=offset;
        _host=offset;
        _port=offset;
        _portValue=-1;
        _path=offset;
        _param=_end;
        _query=_end;
        _fragment=_end;
        while (i<e)
        {
            char c=(char)(0xff&_raw[i]);
            int s=i++;

            state: switch (state)
            {
                case START:
                {
                    m=s;
                    switch(c)
                    {
                        case '/':
                            state=AUTH_OR_PATH;
                            break;
                        case ';':
                            _param=s;
                            state=PARAM;
                            break;
                        case '?':
                            _param=s;
                            _query=s;
                            state=QUERY;
                            break;
                        case '#':
                            _param=s;
                            _query=s;
                            _fragment=s;
                            break;
                        case '*':
                            _path=s;
                            state=ASTERISK;
                            break;

                        default:
                            state=SCHEME_OR_PATH;
                    }

                    continue;
                }

                case AUTH_OR_PATH:
                {
                    if ((_partial||_scheme!=_authority) && c=='/')
                    {
                        _host=i;
                        _port=_end;
                        _path=_end;
                        state=AUTH;
                    }
                    else if (c==';' || c=='?' || c=='#')
                    {
                        i--;
                        state=PATH;
                    }
                    else
                    {
                        _host=m;
                        _port=m;
                        state=PATH;
                    }
                    continue;
                }

                case SCHEME_OR_PATH:
                {
                    // short cut for http and https
                    if (length>6 && c=='t')
                    {
                        if (_raw[offset+3]==':')
                        {
                            s=offset+3;
                            i=offset+4;
                            c=':';
                        }
                        else if (_raw[offset+4]==':')
                        {
                            s=offset+4;
                            i=offset+5;
                            c=':';
                        }
                        else if (_raw[offset+5]==':')
                        {
                            s=offset+5;
                            i=offset+6;
                            c=':';
                        }
                    }

                    switch (c)
                    {
                        case ':':
                        {
                            m = i++;
                            _authority = m;
                            _path = m;
                            c = (char)(0xff & _raw[i]);
                            if (c == '/')
                                state = AUTH_OR_PATH;
                            else
                            {
                                _host = m;
                                _port = m;
                                state = PATH;
                            }
                            break;
                        }

                        case '/':
                        {
                            state = PATH;
                            break;
                        }

                        case ';':
                        {
                            _param = s;
                            state = PARAM;
                            break;
                        }

                        case '?':
                        {
                            _param = s;
                            _query = s;
                            state = QUERY;
                            break;
                        }

                        case '#':
                        {
                            _param = s;
                            _query = s;
                            _fragment = s;
                            break;
                        }
                    }
                    continue;
                }

                case AUTH:
                {
                    switch (c)
                    {

                        case '/':
                        {
                            m = s;
                            _path = m;
                            _port = _path;
                            state = PATH;
                            break;
                        }
                        case '@':
                        {
                            _host = i;
                            break;
                        }
                        case ':':
                        {
                            _port = s;
                            state = PORT;
                            break;
                        }
                        case '[':
                        {
                            state = IPV6;
                            break;
                        }
                    }
                    continue;
                }

                case IPV6:
                {
                    switch (c)
                    {
                        case '/':
                        {
                            throw new IllegalArgumentException("No closing ']' for " + StringUtil.toString(_raw,offset,length,URIUtil.__CHARSET));
                        }
                        case ']':
                        {
                            state = AUTH;
                            break;
                        }
                    }

                    continue;
                }

                case PORT:
                {
                    if (c=='/')
                    {
                        m=s;
                        _path=m;
                        if (_port<=_authority)
                            _port=_path;
                        state=PATH;
                    }
                    continue;
                }

                case PATH:
                {
                    switch (c)
                    {
                        case ';':
                        {
                            _param = s;
                            state = PARAM;
                            break;
                        }
                        case '?':
                        {
                            _param = s;
                            _query = s;
                            state = QUERY;
                            break;
                        }
                        case '#':
                        {
                            _param = s;
                            _query = s;
                            _fragment = s;
                            break state;
                        }
                        case '%':
                        {
                            _encoded=true;
                        }
                    }
                    continue;
                }

                case PARAM:
                {
                    switch (c)
                    {
                        case '?':
                        {
                            _query = s;
                            state = QUERY;
                            break;
                        }
                        case '#':
                        {
                            _query = s;
                            _fragment = s;
                            break state;
                        }
                    }
                    continue;
                }

                case QUERY:
                {
                    if (c=='#')
                    {
                        _fragment=s;
                        break state;
                    }
                    continue;
                }

                case ASTERISK:
                {
                    throw new IllegalArgumentException("only '*'");
                }
            }
        }

        if (_port<_path)
            _portValue=TypeUtil.parseInt(_raw, _port+1, _path-_port-1,10);
    }

    private String toUtf8String(int offset,int length)
    {
        _utf8b.reset();
        _utf8b.append(_raw,offset,length);
        return _utf8b.toString();
    }

    public String getScheme()
    {
        if (_scheme==_authority)
            return null;
        int l=_authority-_scheme;
        if (l==5 &&
            _raw[_scheme]=='h' &&
            _raw[_scheme+1]=='t' &&
            _raw[_scheme+2]=='t' &&
            _raw[_scheme+3]=='p' )
            return HttpSchemes.HTTP;
        if (l==6 &&
            _raw[_scheme]=='h' &&
            _raw[_scheme+1]=='t' &&
            _raw[_scheme+2]=='t' &&
            _raw[_scheme+3]=='p' &&
            _raw[_scheme+4]=='s' )
            return HttpSchemes.HTTPS;

        return toUtf8String(_scheme,_authority-_scheme-1);
    }

    public String getAuthority()
    {
        if (_authority==_path)
            return null;
        return toUtf8String(_authority,_path-_authority);
    }

    public String getHost()
    {
        if (_host==_port)
            return null;
        return toUtf8String(_host,_port-_host);
    }

    public int getPort()
    {
        return _portValue;
    }

    public String getPath()
    {
        if (_path==_param)
            return null;
        return toUtf8String(_path,_param-_path);
    }

    public String getDecodedPath()
    {
        if (_path==_param)
            return null;

        int length = _param-_path;
        boolean decoding=false;

        for (int i=_path;i<_param;i++)
        {
            byte b = _raw[i];

            if (b=='%')
            {
                if (!decoding)
                {
                    _utf8b.reset();
                    _utf8b.append(_raw,_path,i-_path);
                    decoding=true;
                }
                
                if ((i+2)>=_param)
                    throw new IllegalArgumentException("Bad % encoding: "+this);
                if (_raw[i+1]=='u')
                {
                    if ((i+5)>=_param)
                        throw new IllegalArgumentException("Bad %u encoding: "+this);
                    try
                    {
                        String unicode = new String(Character.toChars(TypeUtil.parseInt(_raw,i+2,4,16)));
                        _utf8b.getStringBuilder().append(unicode);
                        i+=5;
                    }
                    catch(Exception e)
                    {
                        throw new RuntimeException(e);
                    }
                }
                else
                {
                    b=(byte)(0xff&TypeUtil.parseInt(_raw,i+1,2,16));
                    _utf8b.append(b);
                    i+=2;
                }
                continue;
            }
            else if (decoding)
            {
                _utf8b.append(b);
            }
        }

        if (!decoding)
            return toUtf8String(_path,length);
        return _utf8b.toString();
    }
    
    public String getDecodedPath(String encoding)
    {
        if (_path==_param)
            return null;

        int length = _param-_path;
        byte[] bytes=null;
        int n=0;

        for (int i=_path;i<_param;i++)
        {
            byte b = _raw[i];

            if (b=='%')
            {
                if (bytes==null)
                {
                    bytes=new byte[length];
                    System.arraycopy(_raw,_path,bytes,0,n);
                }
                
                if ((i+2)>=_param)
                    throw new IllegalArgumentException("Bad % encoding: "+this);
                if (_raw[i+1]=='u')
                {
                    if ((i+5)>=_param)
                        throw new IllegalArgumentException("Bad %u encoding: "+this);

                    try
                    {
                        String unicode = new String(Character.toChars(TypeUtil.parseInt(_raw,i+2,4,16)));
                        byte[] encoded = unicode.getBytes(encoding);
                        System.arraycopy(encoded,0,bytes,n,encoded.length);
                        n+=encoded.length;
                        i+=5;
                    }
                    catch(Exception e)
                    {
                        throw new RuntimeException(e);
                    }
                }
                else
                {
                    b=(byte)(0xff&TypeUtil.parseInt(_raw,i+1,2,16));
                    bytes[n++]=b;
                    i+=2;
                }
                continue;
            }
            else if (bytes==null)
            {
                n++;
                continue;
            }

            bytes[n++]=b;
        }


        if (bytes==null)
            return StringUtil.toString(_raw,_path,_param-_path,encoding);

        return StringUtil.toString(bytes,0,n,encoding);
    }
    
    
    
    
    


    public String getPathAndParam()
    {
        if (_path==_query)
            return null;
        return toUtf8String(_path,_query-_path);
    }

    public String getCompletePath()
    {
        if (_path==_end)
            return null;
        return toUtf8String(_path,_end-_path);
    }

    public String getParam()
    {
        if (_param==_query)
            return null;
        return toUtf8String(_param+1,_query-_param-1);
    }

    public String getQuery()
    {
        if (_query==_fragment)
            return null;
        return toUtf8String(_query+1,_fragment-_query-1);
    }

    public String getQuery(String encoding)
    {
        if (_query==_fragment)
            return null;
        return StringUtil.toString(_raw,_query+1,_fragment-_query-1,encoding);
    }

    public boolean hasQuery()
    {
        return (_fragment>_query);
    }

    public String getFragment()
    {
        if (_fragment==_end)
            return null;
        return toUtf8String(_fragment+1,_end-_fragment-1);
    }

    public void decodeQueryTo(MultiMap parameters)
    {
        if (_query==_fragment)
            return;
        _utf8b.reset();
        UrlEncoded.decodeUtf8To(_raw,_query+1,_fragment-_query-1,parameters,_utf8b);
    }

    public void decodeQueryTo(MultiMap parameters, String encoding)
        throws UnsupportedEncodingException
    {
        if (_query==_fragment)
            return;

        if (encoding==null || StringUtil.isUTF8(encoding))
            UrlEncoded.decodeUtf8To(_raw,_query+1,_fragment-_query-1,parameters);
        else
            UrlEncoded.decodeTo(StringUtil.toString(_raw,_query+1,_fragment-_query-1,encoding),parameters,encoding);
    }

    public void clear()
    {
        _scheme=_authority=_host=_port=_path=_param=_query=_fragment=_end=0;
        _raw=__empty;
        _rawString="";
        _encoded=false;
    }

    @Override
    public String toString()
    {
        if (_rawString==null)
            _rawString=toUtf8String(_scheme,_end-_scheme);
        return _rawString;
    }

    public void writeTo(Utf8StringBuilder buf)
    {
        buf.append(_raw,_scheme,_end-_scheme);
    }

}
