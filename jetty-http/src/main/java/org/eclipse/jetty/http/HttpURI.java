//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
    private String _user;
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
     * @param scheme the URI scheme
     * @param host the URI hose
     * @param port the URI port
     * @param path the URI path
     * @param param the URI param
     * @param query the URI query
     * @param fragment the URI fragment
     * @return the normalized URI
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
    public HttpURI(HttpURI uri)
    {
        this(uri._scheme,uri._host,uri._port,uri._path,uri._param,uri._query,uri._fragment);
        _uri=uri._uri;
    }
    
    /* ------------------------------------------------------------ */
    public HttpURI(String uri)
    {
        _port=-1;
        parse(State.START,uri,0,uri.length());
    }

    /* ------------------------------------------------------------ */
    public HttpURI(URI uri)
    {
        _uri=null;
        
        _scheme=uri.getScheme();
        _host=uri.getHost();
        if (_host==null && uri.getRawSchemeSpecificPart().startsWith("//"))
            _host="";
        _port=uri.getPort();
        _user = uri.getUserInfo();
        _path=uri.getRawPath();
        
        _decodedPath = uri.getPath();
        if (_decodedPath != null)
        {
            int p = _decodedPath.lastIndexOf(';');
            if (p >= 0)
                _param = _decodedPath.substring(p + 1);
        }
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
    public void parseConnect(String uri)
    {
        clear();
        _uri=uri;
        _path=uri;
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
        int mark=offset;
        int path_mark=0;
        
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
                            mark = i;
                            state = State.HOST_OR_PATH;
                            break;
                        case ';':
                            mark=i+1;
                            state=State.PARAM;
                            break;
                        case '?':
                            // assume empty path (if seen at start)
                            _path = "";
                            mark=i+1;
                            state=State.QUERY;
                            break;
                        case '#':
                            mark=i+1;
                            state=State.FRAGMENT;
                            break;
                        case '*':
                            _path="*";
                            state=State.ASTERISK;
                            break;

                        default:
                            mark=i;
                            if (_scheme==null)
                                state=State.SCHEME_OR_PATH;
                            else
                            {
                                path_mark=i;
                                state=State.PATH;
                            }
                    }

                    continue;
                }

                case SCHEME_OR_PATH:
                {
                    switch (c)
                    {
                        case ':':
                            // must have been a scheme
                            _scheme=uri.substring(mark,i);
                            // Start again with scheme set
                            state=State.START;
                            break;

                        case '/':
                            // must have been in a path and still are
                            state=State.PATH;
                            break;

                        case ';':
                            // must have been in a path 
                            mark=i+1;
                            state=State.PARAM;
                            break;

                        case '?':
                            // must have been in a path 
                            _path=uri.substring(mark,i);
                            mark=i+1;
                            state=State.QUERY;
                            break;

                        case '%':
                            // must have be in an encoded path 
                            encoded=true;
                            state=State.PATH;
                            break;
                        
                        case '#':
                            // must have been in a path 
                            _path=uri.substring(mark,i);
                            state=State.FRAGMENT;
                            break;
                    }
                    continue;
                }
                
                case HOST_OR_PATH:
                {
                    switch(c)
                    {
                        case '/':
                            _host="";
                            mark=i+1;
                            state=State.HOST;
                            break;
                            
                        case '@':
                        case ';':
                        case '?':
                        case '#':
                            // was a path, look again
                            i--;
                            path_mark=mark;
                            state=State.PATH;
                            break;
                        default:
                            // it is a path
                            path_mark=mark;
                            state=State.PATH;
                    }
                    continue;
                }

                case HOST:
                {
                    switch (c)
                    {
                        case '/':
                            _host = uri.substring(mark,i);
                            path_mark=mark=i;
                            state=State.PATH;
                            break;
                        case ':':
                            if (i > mark)
                                _host=uri.substring(mark,i);
                            mark=i+1;
                            state=State.PORT;
                            break;
                        case '@':
                            _user=uri.substring(mark,i);
                            mark=i+1;
                            break;
                            
                        case '[':
                            state=State.IPV6;
                            break;
                    }
                    continue;
                }

                case IPV6:
                {
                    switch (c)
                    {
                        case '/':
                            throw new IllegalArgumentException("No closing ']' for ipv6 in " + uri);
                        case ']':
                            c = uri.charAt(++i);
                            _host=uri.substring(mark,i);
                            if (c == ':')
                            {
                                mark=i+1;
                                state=State.PORT;
                            }
                            else
                            {
                                path_mark=mark=i;
                                state=State.PATH;
                            }
                            break;
                    }

                    continue;
                }

                case PORT:
                {
                    if (c=='/')
                    {
                        _port=TypeUtil.parseInt(uri,mark,i-mark,10);
                        path_mark=mark=i;
                        state=State.PATH;
                    }
                    continue;
                }

                case PATH:
                {
                    switch (c)
                    {
                        case ';':
                            mark=i+1;
                            state=State.PARAM;
                            break;
                        case '?':
                            _path=uri.substring(path_mark,i);
                            mark=i+1;
                            state=State.QUERY;
                            break;
                        case '#':
                            _path=uri.substring(path_mark,i);
                            mark=i+1;
                            state=State.FRAGMENT;
                            break;
                        case '%':
                            encoded=true;
                            break;
                    }
                    continue;
                }

                case PARAM:
                {
                    switch (c)
                    {
                        case '?':
                            _path=uri.substring(path_mark,i);
                            _param=uri.substring(mark,i);
                            mark=i+1;
                            state=State.QUERY;
                            break;
                        case '#':
                            _path=uri.substring(path_mark,i);
                            _param=uri.substring(mark,i);
                            mark=i+1;
                            state=State.FRAGMENT;
                            break;
                        case '/':
                            encoded=true;
                            // ignore internal params
                            state=State.PATH;
                            break;
                        case ';':
                            // multiple parameters
                            mark=i+1;
                            break;
                    }
                    continue;
                }

                case QUERY:
                {
                    if (c=='#')
                    {
                        _query=uri.substring(mark,i);
                        mark=i+1;
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
                    _fragment=uri.substring(mark,end);
                    i=end;
                }
            }
        }

        
        switch(state)
        {
            case START:
                break;
            case SCHEME_OR_PATH:
                _path=uri.substring(mark,end);
                break;

            case HOST_OR_PATH:
                _path=uri.substring(mark,end);
                break;
                
            case HOST:
                if(end>mark)
                    _host=uri.substring(mark,end);
                break;
                
            case IPV6:
                throw new IllegalArgumentException("No closing ']' for ipv6 in " + uri);

            case PORT:
                _port=TypeUtil.parseInt(uri,mark,end-mark,10);
                break;
                
            case ASTERISK:
                break;
                
            case FRAGMENT:
                _fragment=uri.substring(mark,end);
                break;
                
            case PARAM:
                _path=uri.substring(path_mark,end);
                _param=uri.substring(mark,end);
                break;
                
            case PATH:
                _path=uri.substring(path_mark,end);
                break;
                
            case QUERY:
                _query=uri.substring(mark,end);
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
        // Return null for empty host to retain compatibility with java.net.URI
        if (_host!=null && _host.length()==0)
            return null;
        return _host;
    }

    /* ------------------------------------------------------------ */
    public int getPort()
    {
        return _port;
    }

    /* ------------------------------------------------------------ */
    /**
     * The parsed Path.
     * 
     * @return the path as parsed on valid URI.  null for invalid URI.
     */
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
            
            if (_host != null)
            {
                out.append("//");
                if (_user != null)
                    out.append(_user).append('@');
                out.append(_host);
            }
            
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
     * @param host the host
     * @param port the port
     */
    public void setAuthority(String host, int port)
    {
        _host=host;
        _port=port;
        _uri=null;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param path the path
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
