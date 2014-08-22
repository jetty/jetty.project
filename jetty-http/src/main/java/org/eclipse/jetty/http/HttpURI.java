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
import java.net.URISyntaxException;
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
    private enum State {
    START,
    HOST_OR_PATH,
    SCHEME_OR_PATH,
    HOST,
    IPV6,
    PORT,
    PATH,
    PARAM,
    QUERY,
    FRAGMENT,
    ASTERISK};

    private String _scheme;
    private String _host;
    private int _port;
    private String _path;
    private String _param;
    private String _query;
    private String _fragment;
    
    String _uri;
    String _decodedPath;

    /* ------------------------------------------------------------ */
    /**
     * Construct a normalized URI.
     * Port is not set if it is the default port.
     */
    public static HttpURI createHttpURI(String scheme, String host, int port, String path, String param, String query, String fragment)
    {
        if (port==80 && HttpScheme.HTTP.is(scheme))
            port=0;
        if (port==443 && HttpScheme.HTTPS.is(scheme))
            port=0;
        return new HttpURI(scheme,host,port,path,param,query,fragment);
    }
    
    /* ------------------------------------------------------------ */
    public HttpURI()
    {
    }

    /* ------------------------------------------------------------ */
    public HttpURI(String scheme, String host, int port, String path, String param, String query, String fragment)
    {
        _scheme = scheme;
        _host = host;
        _port = port;
        _path = path;
        _param = param;
        _query = query;
        _fragment = fragment;
    }

    /* ------------------------------------------------------------ */
    public HttpURI(String uri)
    {
        parse(uri);
        parse(State.START,uri,0,uri.length());
    }

    /* ------------------------------------------------------------ */
    public HttpURI(URI uri)
    {
        _uri=null;
        
        _scheme=uri.getScheme();
        _host=uri.getHost();
        _port=uri.getPort();
        _path=uri.getRawPath();
        _decodedPath=uri.getPath();
        int p=_path.lastIndexOf(';');
        if (p>=0)
            _param=_path.substring(p+1);
        _query=uri.getRawQuery();
        _fragment=uri.getFragment();
        
        _decodedPath=null;
    }

    /* ------------------------------------------------------------ */
    public HttpURI(String scheme, String host, int port, String pathQuery)
    {
        _uri=null;
        
        _scheme=scheme;
        _host=host;
        _port=port;

        parse(State.PATH,pathQuery,0,pathQuery.length());
        
    }

    /* ------------------------------------------------------------ */
    public void parse(String uri)
    {
        clear();
        _uri=uri;
        parse(State.START,uri,0,uri.length());
    }

    /* ------------------------------------------------------------ */
    public void parse(String uri, int offset, int length)
    {
        clear();
        int end=offset+length;
        _uri=uri.substring(offset,end);
        parse(State.START,uri,offset,end);
    }

    /* ------------------------------------------------------------ */
    private void parse(State state, final String uri, final int offset, final int end)
    {
        boolean encoded=false;
        int m=offset;
        int p=0;
        
        for (int i=offset; i<end; i++)
        {
            char c=uri.charAt(i);

            switch (state)
            {
                case START:
                {
                    switch(c)
                    {
                        case '/':
                            p=m=i;
                            state=State.PATH;
                            break;
                        case ';':
                            m=i+1;
                            state=State.PARAM;
                            break;
                        case '?':
                            m=i+1;
                            state=State.QUERY;
                            break;
                        case '#':
                            m=i+1;
                            state=State.FRAGMENT;
                            break;
                        case '*':
                            _path="*";
                            state=State.ASTERISK;
                            break;

                        default:
                            m=i;
                            state=State.SCHEME_OR_PATH;
                    }

                    continue;
                }

                case SCHEME_OR_PATH:
                {
                    switch (c)
                    {
                        case ':':
                        {
                            // must have been a scheme
                            _scheme=uri.substring(m,i);
                            c = uri.charAt(++i);
                            m=i;
                            if (c == '/')
                                state=State.HOST_OR_PATH;
                            else
                                state=State.PATH;
                            break;
                        }

                        case '/':
                        {
                            // must have been in a path and still are
                            state=State.PATH;
                            break;
                        }

                        case ';':
                        {
                            // must have been in a path 
                            p=m;
                            state=State.PARAM;
                            break;
                        }

                        case '?':
                        {
                            // must have been in a path 
                            _path=uri.substring(m,i);
                            state=State.QUERY;
                            break;
                        }

                        case '%':
                        {
                            encoded=true;
                        }
                        
                        case '#':
                        {
                            // must have been in a path 
                            _path=uri.substring(m,i);
                            state=State.FRAGMENT;
                            break;
                        }
                    }
                    continue;
                }
                
                case HOST_OR_PATH:
                {
                    switch(c)
                    {
                        case '/':
                            m=i+1;
                            state=State.HOST;
                            break;
                            
                        case ';':
                        case '?':
                        case '#':
                            // was a path, look again
                            i--;
                            p=m;
                            state=State.PATH;
                            break;
                        default:
                            // it is a path
                            p=m;
                            state=State.PATH;
                    }
                    continue;
                }

                case HOST:
                {
                    switch (c)
                    {
                        case '/':
                        {
                            _host=uri.substring(m,i);
                            p=m=i;
                            state=State.PATH;
                            break;
                        }
                        case ':':
                        {
                            _host=uri.substring(m,i);
                            m=i+1;
                            state=State.PORT;
                            break;
                        }
                        case '@':
                            // ignore user
                            m=i+1;
                            break;
                            
                        case '[':
                        {
                            state=State.IPV6;
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
                            c = uri.charAt(++i);
                            _host=uri.substring(m,i);
                            if (c == ':')
                            {
                                m=i+1;
                                state=State.PORT;
                            }
                            else
                            {
                                p=m=i;
                                state=State.PATH;
                            }
                            break;
                        }
                    }

                    continue;
                }

                case PORT:
                {
                    if (c=='/')
                    {
                        _port=TypeUtil.parseInt(uri,m,i-m,10);
                        p=m=i;
                        state=State.PATH;
                    }
                    continue;
                }

                case PATH:
                {
                    switch (c)
                    {
                        case ';':
                        {
                            m=i+1;
                            state=State.PARAM;
                            break;
                        }
                        case '?':
                        {
                            _path=uri.substring(p,i);
                            m=i+1;
                            state=State.QUERY;
                            break;
                        }
                        case '#':
                        {
                            _path=uri.substring(p,i);
                            m=i+1;
                            state=State.FRAGMENT;
                            break;
                        }
                        case '%':
                        {
                            encoded=true;
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
                            _path=uri.substring(p,i);
                            _param=uri.substring(m,i);
                            m=i+1;
                            state=State.QUERY;
                            break;
                        }
                        case '#':
                        {
                            _path=uri.substring(p,i);
                            _param=uri.substring(m,i);
                            m=i+1;
                            state=State.FRAGMENT;
                            break;
                        }
                        case '/':
                        {
                            encoded=true;
                            // ignore internal params
                            state=State.PATH;
                            break;
                        }
                        case ';':
                        {
                            // multiple parameters
                            m=i+1;
                            break;
                        }
                    }
                    continue;
                }

                case QUERY:
                {
                    if (c=='#')
                    {
                        _query=uri.substring(m,i);
                        m=i+1;
                        state=State.FRAGMENT;
                    }
                    continue;
                }

                case ASTERISK:
                {
                    throw new IllegalArgumentException("only '*'");
                }
                
                case FRAGMENT:
                {
                    _fragment=uri.substring(m,end);
                    i=end;
                }
            }
        }

        
        switch(state)
        {
            case START:
                break;
            case SCHEME_OR_PATH:
                _path=uri.substring(m,end);
                break;

            case HOST_OR_PATH:
                _path=uri.substring(m,end);
                break;
                
            case HOST:
                _host=uri.substring(m,end);
                break;
                
            case IPV6:
                throw new IllegalArgumentException("No closing ']' for ipv6 in " + uri);

            case PORT:
                _port=TypeUtil.parseInt(uri,m,end-m,10);
                break;
                
            case ASTERISK:
                break;
                
            case FRAGMENT:
                _fragment=uri.substring(m,end);
                break;
                
            case PARAM:
                _path=uri.substring(p,end);
                _param=uri.substring(m,end);
                break;
                
            case PATH:
                _path=uri.substring(p,end);
                break;
                
            case QUERY:
                _query=uri.substring(m,end);
                break;
        }
        
        if (!encoded)
        {
            if (_param==null)
                _decodedPath=_path;
            else
                _decodedPath=_path.substring(0,_path.length()-_param.length()-1);
        }
    }

    /* ------------------------------------------------------------ */
    public String getScheme()
    {
        return _scheme;
    }

    /* ------------------------------------------------------------ */
    public String getHost()
    {
        return _host;
    }

    /* ------------------------------------------------------------ */
    public int getPort()
    {
        return _port;
    }

    /* ------------------------------------------------------------ */
    public String getPath()
    {
        return _path;
    }

    /* ------------------------------------------------------------ */
    public String getDecodedPath()
    {
        if (_decodedPath==null && _path!=null)
            _decodedPath=URIUtil.decodePath(_path);
        return _decodedPath;
    }

    /* ------------------------------------------------------------ */
    public String getParam()
    {
        return _param;
    }

    /* ------------------------------------------------------------ */
    public String getQuery()
    {
        return _query;
    }

    /* ------------------------------------------------------------ */
    public boolean hasQuery()
    {
        return _query!=null && _query.length()>0;
    }

    /* ------------------------------------------------------------ */
    public String getFragment()
    {
        return _fragment;
    }

    /* ------------------------------------------------------------ */
    public void decodeQueryTo(MultiMap<String> parameters)
    {
        if (_query==_fragment)
            return;
        UrlEncoded.decodeUtf8To(_query,parameters);
    }

    /* ------------------------------------------------------------ */
    public void decodeQueryTo(MultiMap<String> parameters, String encoding) throws UnsupportedEncodingException
    {
        decodeQueryTo(parameters,Charset.forName(encoding));
    }

    /* ------------------------------------------------------------ */
    public void decodeQueryTo(MultiMap<String> parameters, Charset encoding) throws UnsupportedEncodingException
    {
        if (_query==_fragment)
            return;

        if (encoding==null || StandardCharsets.UTF_8.equals(encoding))
            UrlEncoded.decodeUtf8To(_query,parameters);
        else
            UrlEncoded.decodeTo(_query,parameters,encoding);
    }

    /* ------------------------------------------------------------ */
    public void clear()
    {
        _uri=null;
        
        _scheme=null;
        _host=null;
        _port=-1;
        _path=null;
        _param=null;
        _query=null;
        _fragment=null;
        
        _decodedPath=null;
    }

    /* ------------------------------------------------------------ */
    public boolean isAbsolute()
    {
        return _scheme!=null && _scheme.length()>0;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        if (_uri==null)
        {
            StringBuilder out = new StringBuilder();
            
            if (_scheme!=null)
                out.append(_scheme).append(':');
            
            if (_host!=null)
                out.append("//").append(_host);
            
            if (_port>0)
                out.append(':').append(_port);
            
            if (_path!=null)
                out.append(_path);
            
            if (_query!=null)
                out.append('?').append(_query);
            
            if (_fragment!=null)
                out.append('#').append(_fragment);
            
            if (out.length()>0)
                _uri=out.toString();
            else
                _uri="";
        }
        return _uri;
    }

    /* ------------------------------------------------------------ */
    public boolean equals(Object o)
    {
        if (o==this)
            return true;
        if (!(o instanceof HttpURI))
            return false;
        return toString().equals(o.toString());
    }

    /* ------------------------------------------------------------ */
    public void setScheme(String scheme)
    {
        _scheme=scheme;
        _uri=null;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param host
     * @param port
     */
    public void setAuthority(String host, int port)
    {
        _host=host;
        _port=port;
        _uri=null;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param path
     */
    public void setPath(String path)
    {
        _uri=null;
        _path=path;
        _decodedPath=null;
    }
    
    /* ------------------------------------------------------------ */
    public void setPathQuery(String path)
    {
        _uri=null;
        _path=null;
        _decodedPath=null;
        _param=null;
        _fragment=null;
        if (path!=null)
            parse(State.PATH,path,0,path.length());
    }
    
    /* ------------------------------------------------------------ */
    public void setQuery(String query)
    {
        _query=query;
        _uri=null;
    }
    
    /* ------------------------------------------------------------ */
    public URI toURI() throws URISyntaxException
    {
        return new URI(_scheme,null,_host,_port,_path,_query==null?null:UrlEncoded.decodeString(_query),_fragment);
    }

    /* ------------------------------------------------------------ */
    public String getPathQuery()
    {
        if (_query==null)
            return _path;
        return _path+"?"+_query;
    }
    
    /* ------------------------------------------------------------ */
    public String getAuthority()
    {
        if (_port>0)
            return _host+":"+_port;
        return _host;
    }


}
