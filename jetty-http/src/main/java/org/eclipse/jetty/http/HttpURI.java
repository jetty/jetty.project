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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;


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
 * <p>Any parameters will be returned from {@link #getPath()}, but are excluded from the
 * return value of {@link #getDecodedPath()}.   If there are multiple parameters, the 
 * {@link #getParam()} method returns only the last one.
 */
public class HttpURI
{
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
    String _uri;
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

    public HttpURI(String uri)
    {
        _uri=uri;
        parse(uri);
    }

    public HttpURI(URI uri)
    {
        this(uri.toASCIIString());
    }


    public void parseConnect(String authority)
    {
        parseConnect(authority,0,authority.length());
    }
    
    public void parseConnect(String authority,int offset,int length)
    {
        _uri=authority;
        _encoded=false;
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
            char c=authority.charAt(i);
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
                            throw new IllegalArgumentException("No closing ']' for " + _uri.substring(offset,length));
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
            _portValue=TypeUtil.parseInt(_uri, _port+1, _path-_port-1,10);
        else
            throw new IllegalArgumentException("No port");
        _path=offset;
    }


    public void parse(String uri)
    {
        _uri=uri;
        _encoded=false;
        int i=0;
        int e=uri.length();
        int state=START;
        int m=i;
        _end=e;
        _scheme=i;
        _authority=i;
        _host=i;
        _port=i;
        _portValue=-1;
        _path=i;
        _param=_end;
        _query=_end;
        _fragment=_end;
        while (i<e)
        {
            char c=uri.charAt(i);
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
                    switch (c)
                    {
                        case ':':
                        {
                            m = i++;
                            _authority = m;
                            _path = m;
                            c = uri.charAt(i);
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
                            throw new IllegalArgumentException("No closing ']' for ipv6 in " + uri);
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
                        case '/':
                        {
                            state=PATH;
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
            _portValue=TypeUtil.parseInt(_uri, _port+1, _path-_port-1,10);
    }

    public String getScheme()
    {
        if (_scheme==_authority)
            return null;
        int l=_authority-_scheme;
        if (l==5 && _uri.startsWith("http:"))
            return HttpScheme.HTTP.asString();
        if (l==6 && _uri.startsWith("https:"))
            return HttpScheme.HTTPS.asString();

        return _uri.substring(_scheme,_authority-_scheme-1);
    }

    public String getAuthority()
    {
        if (_authority==_path)
            return null;
        return _uri.substring(_authority,_path);
    }

    public String getHost()
    {
        if (_host==_port)
            return null;
        return _uri.substring(_host,_port);
    }

    public int getPort()
    {
        return _portValue;
    }

    public String getPath()
    {
        if (_path==_query)
            return null;
        return _uri.substring(_path,_query);
    }

    public String getDecodedPath()
    {
        if (_path==_query)
            return null;
        return URIUtil.decodePath(_uri,_path,_query-_path);
    }

    public String getPathAndParam()
    {
        if (_path==_query)
            return null;
        return _uri.substring(_path,_query);
    }

    public String getCompletePath()
    {
        if (_path==_end)
            return null;
        return _uri.substring(_path,_end);
    }

    public String getParam()
    {
        if (_param==_query)
            return null;
        return _uri.substring(_param+1,_query);
    }

    public String getQuery()
    {
        if (_query==_fragment)
            return null;
        return _uri.substring(_query+1,_fragment);
    }

    public boolean hasQuery()
    {
        return (_fragment>_query);
    }

    public String getFragment()
    {
        if (_fragment==_end)
            return null;
        
        return _uri.substring(_fragment+1,_end);
    }

    public void decodeQueryTo(MultiMap<String> parameters)
    {
        if (_query==_fragment)
            return;
        UrlEncoded.decodeUtf8To(_uri,_query+1,_fragment-_query-1,parameters);
    }

    public void decodeQueryTo(MultiMap<String> parameters, String encoding) throws UnsupportedEncodingException
    {
        if (_query==_fragment)
            return;
        decodeQueryTo(parameters,Charset.forName(encoding));
    }

    public void decodeQueryTo(MultiMap<String> parameters, Charset encoding) throws UnsupportedEncodingException
    {
        if (_query==_fragment)
            return;

        if (encoding==null || StandardCharsets.UTF_8.equals(encoding))
            UrlEncoded.decodeUtf8To(_uri,_query+1,_fragment-_query-1,parameters);
        else
            UrlEncoded.decodeTo(_uri.substring(_query+1,_fragment-_query-1),parameters,encoding);
    }

    public void clear()
    {
        _scheme=_authority=_host=_port=_path=_param=_query=_fragment=_end=0;
        _uri=null;
        _encoded=false;
    }

    @Override
    public String toString()
    {
        return _uri;
    }

    public boolean equals(Object o)
    {
        if (o==this)
            return true;
        if (!(o instanceof HttpURI))
            return false;
        return toString().equals(o.toString());
    }

}
