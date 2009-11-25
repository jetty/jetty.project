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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeaderValues;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersions;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.BufferCache.CachedBuffer;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.ByteArrayISO8859Writer;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;

/* ------------------------------------------------------------ */
/** Response.
 * <p>
 * Implements {@link javax.servlet.HttpServletResponse} from the {@link javax.servlet} package.
 * </p>
 *
 * 
 *
 */
public class Response implements HttpServletResponse
{
    public static final int
        NONE=0,
        STREAM=1,
        WRITER=2;

    /**
     * If a header name starts with this string,  the header (stripped of the prefix)
     * can be set during include using only {@link #setHeader(String, String)} or
     * {@link #addHeader(String, String)}.
     */
    public final static String SET_INCLUDE_HEADER_PREFIX = "org.eclipse.jetty.server.include.";

    private static final PrintWriter __nullPrintWriter;
    private static final ServletOutputStream __nullServletOut;

    static
    {
        __nullPrintWriter = new PrintWriter(IO.getNullWriter());
        __nullServletOut = new NullOutput();
    }

    private final HttpConnection _connection;
    private int _status=SC_OK;
    private String _reason;
    private Locale _locale;
    private String _mimeType;
    private CachedBuffer _cachedMimeType;
    private String _characterEncoding;
    private boolean _explicitEncoding;
    private String _contentType;
    private int _outputState;
    private PrintWriter _writer;

    /* ------------------------------------------------------------ */
    /**
     *
     */
    public Response(HttpConnection connection)
    {
        _connection=connection;
    }


    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#reset()
     */
    protected void recycle()
    {
        _status=SC_OK;
        _reason=null;
        _locale=null;
        _mimeType=null;
        _cachedMimeType=null;
        _characterEncoding=null;
        _explicitEncoding=false;
        _contentType=null;
        _outputState=NONE;
        _writer=null;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#addCookie(javax.servlet.http.Cookie)
     */
    public void addCookie(HttpCookie cookie)
    {
        _connection.getResponseFields().addSetCookie(cookie);
    }
    
    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#addCookie(javax.servlet.http.Cookie)
     */
    public void addCookie(Cookie cookie)
    {
        _connection.getResponseFields().addSetCookie(cookie.getName(),
                cookie.getValue(),
                cookie.getDomain(),
                cookie.getPath(),
                cookie.getMaxAge(),
                cookie.getComment(),
                cookie.getSecure(),
                false,//cookie.isHttpOnly(),
                cookie.getVersion());
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#containsHeader(java.lang.String)
     */
    public boolean containsHeader(String name)
    {
        return _connection.getResponseFields().containsKey(name);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#encodeURL(java.lang.String)
     */
    public String encodeURL(String url)
    {
        Request request=_connection.getRequest();
        SessionManager sessionManager = request.getSessionManager();
        if (sessionManager==null)
            return url;
        String sessionURLPrefix = sessionManager.getSessionIdPathParameterNamePrefix();
        if (sessionURLPrefix==null)
            return url;

        if (url==null)
            return null;
        // should not encode if cookies in evidence
        if (request.isRequestedSessionIdFromCookie())
        {
            int prefix=url.indexOf(sessionURLPrefix);
            if (prefix!=-1)
            {
                int suffix=url.indexOf("?",prefix);
                if (suffix<0)
                    suffix=url.indexOf("#",prefix);

                if (suffix<=prefix)
                    return url.substring(0,prefix);
                return url.substring(0,prefix)+url.substring(suffix);
            }
            return url;
        }

        // get session;
        HttpSession session=request.getSession(false);

        // no session
        if (session == null)
            return url;


        // invalid session
        if (!sessionManager.isValid(session))
            return url;

        String id=sessionManager.getNodeId(session);


        // TODO Check host and port are for this server
        // Already encoded
        int prefix=url.indexOf(sessionURLPrefix);
        if (prefix!=-1)
        {
            int suffix=url.indexOf("?",prefix);
            if (suffix<0)
                suffix=url.indexOf("#",prefix);

            if (suffix<=prefix)
                return url.substring(0,prefix+sessionURLPrefix.length())+id;
            return url.substring(0,prefix+sessionURLPrefix.length())+id+
                url.substring(suffix);
        }

        // edit the session
        int suffix=url.indexOf('?');
        if (suffix<0)
            suffix=url.indexOf('#');
        if (suffix<0)
            return url+sessionURLPrefix+id;
        return url.substring(0,suffix)+
            sessionURLPrefix+id+url.substring(suffix);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#encodeRedirectURL(java.lang.String)
     */
    public String encodeRedirectURL(String url)
    {
        return encodeURL(url);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#encodeUrl(java.lang.String)
     */
    public String encodeUrl(String url)
    {
        return encodeURL(url);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#encodeRedirectUrl(java.lang.String)
     */
    public String encodeRedirectUrl(String url)
    {
        return encodeURL(url);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#sendError(int, java.lang.String)
     */
    public void sendError(int code, String message) throws IOException
    {
    	if (_connection.isIncluding())
    		return;

        if (isCommitted())
            Log.warn("Committed before "+code+" "+message);

        resetBuffer();
        _characterEncoding=null;
        setHeader(HttpHeaders.EXPIRES,null);
        setHeader(HttpHeaders.LAST_MODIFIED,null);
        setHeader(HttpHeaders.CACHE_CONTROL,null);
        setHeader(HttpHeaders.CONTENT_TYPE,null);
        setHeader(HttpHeaders.CONTENT_LENGTH,null);

        _outputState=NONE;
        setStatus(code,message);

        if (message==null)
            message=HttpStatus.getMessage(code);

        // If we are allowed to have a body
        if (code!=SC_NO_CONTENT &&
            code!=SC_NOT_MODIFIED &&
            code!=SC_PARTIAL_CONTENT &&
            code>=SC_OK)
        {
            Request request = _connection.getRequest();

            ErrorHandler error_handler = null;
            ContextHandler.Context context = request.getContext();
            if (context!=null)
                error_handler=context.getContextHandler().getErrorHandler();
            if (error_handler!=null)
            {
                // TODO - probably should reset these after the request?
                request.setAttribute(Dispatcher.ERROR_STATUS_CODE,new Integer(code));
                request.setAttribute(Dispatcher.ERROR_MESSAGE, message);
                request.setAttribute(Dispatcher.ERROR_REQUEST_URI, request.getRequestURI());
                request.setAttribute(Dispatcher.ERROR_SERVLET_NAME,request.getServletName());

                error_handler.handle(null,_connection.getRequest(),_connection.getRequest(),this );
            }
            else
            {
                setHeader(HttpHeaders.CACHE_CONTROL, "must-revalidate,no-cache,no-store");
                setContentType(MimeTypes.TEXT_HTML_8859_1);
                ByteArrayISO8859Writer writer= new ByteArrayISO8859Writer(2048);
                if (message != null)
                {
                    message= StringUtil.replace(message, "&", "&amp;");
                    message= StringUtil.replace(message, "<", "&lt;");
                    message= StringUtil.replace(message, ">", "&gt;");
                }
                String uri= request.getRequestURI();
                if (uri!=null)
                {
                    uri= StringUtil.replace(uri, "&", "&amp;");
                    uri= StringUtil.replace(uri, "<", "&lt;");
                    uri= StringUtil.replace(uri, ">", "&gt;");
                }

                writer.write("<html>\n<head>\n<meta http-equiv=\"Content-Type\" content=\"text/html;charset=ISO-8859-1\"/>\n");
                writer.write("<title>Error ");
                writer.write(Integer.toString(code));
                writer.write(' ');
                if (message==null)
                    message=HttpStatus.getMessage(code);
                writer.write(message);
                writer.write("</title>\n</head>\n<body>\n<h2>HTTP ERROR: ");
                writer.write(Integer.toString(code));
                writer.write("</h2>\n<p>Problem accessing ");
                writer.write(uri);
                writer.write(". Reason:\n<pre>    ");
                writer.write(message);
                writer.write("</pre>");
                writer.write("</p>\n<hr /><i><small>Powered by Jetty://</small></i>");

                for (int i= 0; i < 20; i++)
                    writer.write("\n                                                ");
                writer.write("\n</body>\n</html>\n");

                writer.flush();
                setContentLength(writer.size());
                writer.writeTo(getOutputStream());
                writer.destroy();
            }
        }
        else if (code!=SC_PARTIAL_CONTENT)
        {
            _connection.getRequestFields().remove(HttpHeaders.CONTENT_TYPE_BUFFER);
            _connection.getRequestFields().remove(HttpHeaders.CONTENT_LENGTH_BUFFER);
            _characterEncoding=null;
            _mimeType=null;
            _cachedMimeType=null;
        }

        complete();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#sendError(int)
     */
    public void sendError(int sc) throws IOException
    {
        if (sc==102)
            sendProcessing();
        else
            sendError(sc,null);
    }

    /* ------------------------------------------------------------ */
    /* Send a 102-Processing response.
     * If the connection is a HTTP connection, the version is 1.1 and the
     * request has a Expect header starting with 102, then a 102 response is
     * sent. This indicates that the request still be processed and real response
     * can still be sent.   This method is called by sendError if it is passed 102.
     * @see javax.servlet.http.HttpServletResponse#sendError(int)
     */
    public void sendProcessing() throws IOException
    {
        if (_connection.isExpecting102Processing() && !isCommitted())
            ((HttpGenerator)_connection.getGenerator()).send1xx(HttpStatus.PROCESSING_102);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#sendRedirect(java.lang.String)
     */
    public void sendRedirect(String location) throws IOException
    {
    	if (_connection.isIncluding())
    		return;

        if (location==null)
            throw new IllegalArgumentException();

        if (!URIUtil.hasScheme(location))
        {
            StringBuilder buf = _connection.getRequest().getRootURL();
            if (location.startsWith("/"))
                buf.append(location);
            else
            {
                String path=_connection.getRequest().getRequestURI();
                String parent=(path.endsWith("/"))?path:URIUtil.parentPath(path);
                location=URIUtil.addPaths(parent,location);
                if(location==null)
                    throw new IllegalStateException("path cannot be above root");
                if (!location.startsWith("/"))
                    buf.append('/');
                buf.append(location);
            }

            location=buf.toString();
            HttpURI uri = new HttpURI(location);
            String path=uri.getDecodedPath();
            String canonical=URIUtil.canonicalPath(path);
            if (canonical==null)
                throw new IllegalArgumentException();
            if (!canonical.equals(path))
            {
                buf = _connection.getRequest().getRootURL();
                buf.append(canonical);
                if (uri.getQuery()!=null)
                {
                    buf.append('?');
                    buf.append(uri.getQuery());
                }
                if (uri.getFragment()!=null)
                {
                    buf.append('#');
                    buf.append(uri.getFragment());
                }
                location=buf.toString();
            }
        }
        resetBuffer();

        setHeader(HttpHeaders.LOCATION,location);
        setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY);
        complete();

    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#setDateHeader(java.lang.String, long)
     */
    public void setDateHeader(String name, long date)
    {
        if (!_connection.isIncluding())
            _connection.getResponseFields().putDateField(name, date);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#addDateHeader(java.lang.String, long)
     */
    public void addDateHeader(String name, long date)
    {
        if (!_connection.isIncluding())
            _connection.getResponseFields().addDateField(name, date);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#setHeader(java.lang.String, java.lang.String)
     */
    public void setHeader(String name, String value)
    {
        if (_connection.isIncluding())
        {
            if (name.startsWith(SET_INCLUDE_HEADER_PREFIX))
                name=name.substring(SET_INCLUDE_HEADER_PREFIX.length());
            else
                return;
        }
        _connection.getResponseFields().put(name, value);
        if (HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name))
        {
            if (value==null)
                _connection._generator.setContentLength(-1);
            else
                _connection._generator.setContentLength(Long.parseLong(value));
        }
    }

    /* ------------------------------------------------------------ */
    /*
     */
    public String getHeader(String name)
    {
        return _connection.getResponseFields().getStringField(name);
    }

    /* ------------------------------------------------------------ */
    /*
     */
    public Enumeration getHeaders(String name)
    {
        Enumeration e = _connection.getResponseFields().getValues(name);
        if (e==null)
            return Collections.enumeration(Collections.EMPTY_LIST);
        return e;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#addHeader(java.lang.String, java.lang.String)
     */
    public void addHeader(String name, String value)
    {
        if (_connection.isIncluding())
        {
            if (name.startsWith(SET_INCLUDE_HEADER_PREFIX))
                name=name.substring(SET_INCLUDE_HEADER_PREFIX.length());
            else
                return;
        }

        _connection.getResponseFields().add(name, value);
        if (HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name))
            _connection._generator.setContentLength(Long.parseLong(value));
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#setIntHeader(java.lang.String, int)
     */
    public void setIntHeader(String name, int value)
    {
        if (!_connection.isIncluding())
        {
            _connection.getResponseFields().putLongField(name, value);
            if (HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name))
                _connection._generator.setContentLength(value);
        }
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#addIntHeader(java.lang.String, int)
     */
    public void addIntHeader(String name, int value)
    {
        if (!_connection.isIncluding())
        {
            _connection.getResponseFields().addLongField(name, value);
            if (HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name))
                _connection._generator.setContentLength(value);
        }
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#setStatus(int)
     */
    public void setStatus(int sc)
    {
        setStatus(sc,null);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpServletResponse#setStatus(int, java.lang.String)
     */
    public void setStatus(int sc, String sm)
    {
        if (sc<=0)
            throw new IllegalArgumentException();
        if (!_connection.isIncluding())
        {
            _status=sc;
            _reason=sm;
        }
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#getCharacterEncoding()
     */
    public String getCharacterEncoding()
    {
        if (_characterEncoding==null)
            _characterEncoding=StringUtil.__ISO_8859_1;
        return _characterEncoding;
    }
    
    /* ------------------------------------------------------------ */
    String getSetCharacterEncoding()
    {
        return _characterEncoding;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#getContentType()
     */
    public String getContentType()
    {
        return _contentType;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#getOutputStream()
     */
    public ServletOutputStream getOutputStream() throws IOException
    {
        if (_outputState!=NONE && _outputState!=STREAM)
            throw new IllegalStateException("WRITER");

        _outputState=STREAM;
        return _connection.getOutputStream();
    }

    /* ------------------------------------------------------------ */
    public boolean isWriting()
    {
        return _outputState==WRITER;
    }

    /* ------------------------------------------------------------ */
    public boolean isOutputing()
    {
        return _outputState!=NONE;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#getWriter()
     */
    public PrintWriter getWriter() throws IOException
    {
        if (_outputState!=NONE && _outputState!=WRITER)
            throw new IllegalStateException("STREAM");

        /* if there is no writer yet */
        if (_writer==null)
        {
            /* get encoding from Content-Type header */
            String encoding = _characterEncoding;

            if (encoding==null)
            {
                /* implementation of educated defaults */
                if(_mimeType!=null)
                    encoding = null; // TODO getHttpContext().getEncodingByMimeType(_mimeType);

                if (encoding==null)
                    encoding = StringUtil.__ISO_8859_1;

                setCharacterEncoding(encoding);
            }

            /* construct Writer using correct encoding */
            _writer = _connection.getPrintWriter(encoding);
        }
        _outputState=WRITER;
        return _writer;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#setCharacterEncoding(java.lang.String)
     */
    public void setCharacterEncoding(String encoding)
    {
    	if (_connection.isIncluding())
    		return;

        if (this._outputState==0 && !isCommitted())
        {
            _explicitEncoding=true;

            if (encoding==null)
            {
                // Clear any encoding.
                if (_characterEncoding!=null)
                {
                    _characterEncoding=null;
                    if (_cachedMimeType!=null)
                        _connection.getResponseFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,_cachedMimeType);
                    else
                        _connection.getResponseFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,_mimeType);
                }
            }
            else
            {
                // No, so just add this one to the mimetype
                _characterEncoding=encoding;
                if (_contentType!=null)
                {
                    int i0=_contentType.indexOf(';');
                    if (i0<0)
                    {
                        _contentType=null;
                        if(_cachedMimeType!=null)
                        {
                            CachedBuffer content_type = _cachedMimeType.getAssociate(_characterEncoding);
                            if (content_type!=null)
                            {
                                _contentType=content_type.toString();
                                _connection.getResponseFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,content_type);
                            }
                        }

                        if (_contentType==null)
                        {
                            _contentType = _mimeType+";charset="+QuotedStringTokenizer.quote(_characterEncoding,";= ");
                            _connection.getResponseFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,_contentType);
                        }
                    }
                    else
                    {
                        int i1=_contentType.indexOf("charset=",i0);
                        if (i1<0)
                        {
                            _contentType = _contentType+" charset="+QuotedStringTokenizer.quote(_characterEncoding,";= ");
                        }
                        else
                        {
                            int i8=i1+8;
                            int i2=_contentType.indexOf(" ",i8);
                            if (i2<0)
                                _contentType=_contentType.substring(0,i8)+QuotedStringTokenizer.quote(_characterEncoding,";= ");
                            else
                                _contentType=_contentType.substring(0,i8)+QuotedStringTokenizer.quote(_characterEncoding,";= ")+_contentType.substring(i2);
                        }
                        _connection.getResponseFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,_contentType);
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#setContentLength(int)
     */
    public void setContentLength(int len)
    {
        // Protect from setting after committed as default handling
        // of a servlet HEAD request ALWAYS sets _content length, even
        // if the getHandling committed the response!
        if (isCommitted() || _connection.isIncluding())
            return;
        _connection._generator.setContentLength(len);
        if (len>=0)
        {
            _connection.getResponseFields().putLongField(HttpHeaders.CONTENT_LENGTH, len);
            if (_connection._generator.isContentWritten())
            {
                if (_outputState==WRITER)
                    _writer.close();
                else if (_outputState==STREAM)
                {
                    try
                    {
                        getOutputStream().close();
                    }
                    catch(IOException e)
                    {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#setContentLength(int)
     */
    public void setLongContentLength(long len)
    {
        // Protect from setting after committed as default handling
        // of a servlet HEAD request ALWAYS sets _content length, even
        // if the getHandling committed the response!
        if (isCommitted() || _connection.isIncluding())
        	return;
        _connection._generator.setContentLength(len);
        _connection.getResponseFields().putLongField(HttpHeaders.CONTENT_LENGTH, len);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#setContentType(java.lang.String)
     */
    public void setContentType(String contentType)
    {
        if (isCommitted() || _connection.isIncluding())
            return;

        // Yes this method is horribly complex.... but there are lots of special cases and
        // as this method is called on every request, it is worth trying to save string creation.
        //

        if (contentType==null)
        {
            if (_locale==null)
                _characterEncoding=null;
            _mimeType=null;
            _cachedMimeType=null;
            _contentType=null;
            _connection.getResponseFields().remove(HttpHeaders.CONTENT_TYPE_BUFFER);
        }
        else
        {
            // Look for encoding in contentType
            int i0=contentType.indexOf(';');

            if (i0>0)
            {
                // we have content type parameters

                // Extract params off mimetype
                _mimeType=contentType.substring(0,i0).trim();
                _cachedMimeType=MimeTypes.CACHE.get(_mimeType);

                // Look for charset
                int i1=contentType.indexOf("charset=",i0+1);
                if (i1>=0)
                {
                    _explicitEncoding=true;
                    int i8=i1+8;
                    int i2 = contentType.indexOf(' ',i8);

                    if (_outputState==WRITER)
                    {
                        // strip the charset and ignore;
                        if ((i1==i0+1 && i2<0) || (i1==i0+2 && i2<0 && contentType.charAt(i0+1)==' '))
                        {
                            if (_cachedMimeType!=null)
                            {
                                CachedBuffer content_type = _cachedMimeType.getAssociate(_characterEncoding);
                                if (content_type!=null)
                                {
                                    _contentType=content_type.toString();
                                    _connection.getResponseFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,content_type);
                                }
                                else
                                {
                                    _contentType=_mimeType+";charset="+_characterEncoding;
                                    _connection.getResponseFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,_contentType);
                                }
                            }
                            else
                            {
                                _contentType=_mimeType+";charset="+_characterEncoding;
                                _connection.getResponseFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,_contentType);
                            }
                        }
                        else if (i2<0)
                        {
                            _contentType=contentType.substring(0,i1)+" charset="+QuotedStringTokenizer.quote(_characterEncoding,";= ");
                            _connection.getResponseFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,_contentType);
                        }
                        else
                        {
                            _contentType=contentType.substring(0,i1)+contentType.substring(i2)+" charset="+QuotedStringTokenizer.quote(_characterEncoding,";= ");
                            _connection.getResponseFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,_contentType);
                        }
                    }
                    else if ((i1==i0+1 && i2<0) || (i1==i0+2 && i2<0 && contentType.charAt(i0+1)==' '))
                    {
                        // The params are just the char encoding
                        _cachedMimeType=MimeTypes.CACHE.get(_mimeType);
                        _characterEncoding = QuotedStringTokenizer.unquote(contentType.substring(i8));

                        if (_cachedMimeType!=null)
                        {
                            CachedBuffer content_type = _cachedMimeType.getAssociate(_characterEncoding);
                            if (content_type!=null)
                            {
                                _contentType=content_type.toString();
                                _connection.getResponseFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,content_type);
                            }
                            else
                            {
                                _contentType=contentType;
                                _connection.getResponseFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,_contentType);
                            }
                        }
                        else
                        {
                            _contentType=contentType;
                            _connection.getResponseFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,_contentType);
                        }
                    }
                    else if (i2>0)
                    {
                        _characterEncoding = QuotedStringTokenizer.unquote(contentType.substring(i8,i2));
                        _contentType=contentType;
                        _connection.getResponseFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,_contentType);
                    }
                    else
                    {
                        _characterEncoding = QuotedStringTokenizer.unquote(contentType.substring(i8));
                        _contentType=contentType;
                        _connection.getResponseFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,_contentType);
                    }
                }
                else // No encoding in the params.
                {
                    _cachedMimeType=null;
                    _contentType=_characterEncoding==null?contentType:contentType+" charset="+QuotedStringTokenizer.quote(_characterEncoding,";= ");
                    _connection.getResponseFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,_contentType);
                }
            }
            else // No params at all
            {
                _mimeType=contentType;
                _cachedMimeType=MimeTypes.CACHE.get(_mimeType);

                if (_characterEncoding!=null)
                {
                    if (_cachedMimeType!=null)
                    {
                        CachedBuffer content_type = _cachedMimeType.getAssociate(_characterEncoding);
                        if (content_type!=null)
                        {
                            _contentType=content_type.toString();
                            _connection.getResponseFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,content_type);
                        }
                        else
                        {
                            _contentType=_mimeType+";charset="+QuotedStringTokenizer.quote(_characterEncoding,";= ");
                            _connection.getResponseFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,_contentType);
                        }
                    }
                    else
                    {
                        _contentType=contentType+";charset="+QuotedStringTokenizer.quote(_characterEncoding,";= ");
                        _connection.getResponseFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,_contentType);
                    }
                }
                else if (_cachedMimeType!=null)
                {
                    _contentType=_cachedMimeType.toString();
                    _connection.getResponseFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,_cachedMimeType);
                }
                else
                {
                    _contentType=contentType;
                    _connection.getResponseFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,_contentType);
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#setBufferSize(int)
     */
    public void setBufferSize(int size)
    {
        if (isCommitted() || getContentCount()>0)
            throw new IllegalStateException("Committed or content written");
        _connection.getGenerator().increaseContentBufferSize(size);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#getBufferSize()
     */
    public int getBufferSize()
    {
        return _connection.getGenerator().getContentBufferSize();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#flushBuffer()
     */
    public void flushBuffer() throws IOException
    {
        _connection.flushResponse();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#reset()
     */
    public void reset()
    {
        resetBuffer();
        fwdReset();
        _status=200;
        _reason=null;
        
        HttpFields response_fields=_connection.getResponseFields();
        
        response_fields.clear();
        String connection=_connection.getRequestFields().getStringField(HttpHeaders.CONNECTION_BUFFER);
        if (connection!=null)
        {
            String[] values = connection.split(",");
            for  (int i=0;values!=null && i<values.length;i++)
            {
                CachedBuffer cb = HttpHeaderValues.CACHE.get(values[0].trim());

                if (cb!=null)
                {
                    switch(cb.getOrdinal())
                    {
                        case HttpHeaderValues.CLOSE_ORDINAL:
                            response_fields.put(HttpHeaders.CONNECTION_BUFFER,HttpHeaderValues.CLOSE_BUFFER);
                            break;

                        case HttpHeaderValues.KEEP_ALIVE_ORDINAL:
                            if (HttpVersions.HTTP_1_0.equalsIgnoreCase(_connection.getRequest().getProtocol()))
                                response_fields.put(HttpHeaders.CONNECTION_BUFFER,HttpHeaderValues.KEEP_ALIVE);
                            break;
                        case HttpHeaderValues.TE_ORDINAL:
                            response_fields.put(HttpHeaders.CONNECTION_BUFFER,HttpHeaderValues.TE);
                            break;
                    }
                }
            }
        }
    }
    
    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#reset()
     */
    public void fwdReset()
    {
        resetBuffer();
        _mimeType=null;
        _cachedMimeType=null;
        _contentType=null;
        _characterEncoding=null;
        _explicitEncoding=false;
        _locale=null;
        _outputState=NONE;
        _writer=null;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#resetBuffer()
     */
    public void resetBuffer()
    {
        if (isCommitted())
            throw new IllegalStateException("Committed");
        _connection.getGenerator().resetBuffer();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#isCommitted()
     */
    public boolean isCommitted()
    {
        return _connection.isResponseCommitted();
    }


    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#setLocale(java.util.Locale)
     */
    public void setLocale(Locale locale)
    {
        if (locale == null || isCommitted() ||_connection.isIncluding())
            return;

        _locale = locale;
        _connection.getResponseFields().put(HttpHeaders.CONTENT_LANGUAGE_BUFFER,locale.toString().replace('_','-'));

        if (_explicitEncoding || _outputState!=0 )
            return;

        if (_connection.getRequest().getContext()==null)
            return;

        String charset = _connection.getRequest().getContext().getContextHandler().getLocaleEncoding(locale);

        if (charset!=null && charset.length()>0)
        {
            _characterEncoding=charset;

            /* get current MIME type from Content-Type header */
            String type=getContentType();
            if (type!=null)
            {
                _characterEncoding=charset;
                int semi=type.indexOf(';');
                if (semi<0)
                {
                    _mimeType=type;
                    _contentType= type += ";charset="+charset;
                }
                else
                {
                    _mimeType=type.substring(0,semi);
                    _contentType= _mimeType += ";charset="+charset;
                }

                _cachedMimeType=MimeTypes.CACHE.get(_mimeType);
                _connection.getResponseFields().put(HttpHeaders.CONTENT_TYPE_BUFFER,_contentType);
            }
        }
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.ServletResponse#getLocale()
     */
    public Locale getLocale()
    {
        if (_locale==null)
            return Locale.getDefault();
        return _locale;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The HTTP status code that has been set for this request. This will be <code>200<code>
     *    ({@link HttpServletResponse#SC_OK}), unless explicitly set through one of the <code>setStatus</code> methods.
     */
    public int getStatus()
    {
        return _status;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The reason associated with the current {@link #getStatus() status}. This will be <code>null</code>,
     *    unless one of the <code>setStatus</code> methods have been called.
     */
    public String getReason()
    {
        return _reason;
    }

    /* ------------------------------------------------------------ */
    /**
     */
    public void complete()
        throws IOException
    {
        _connection.completeResponse();
    }

    /* ------------------------------------------------------------- */
    /**
     * @return the number of bytes actually written in response body
     */
    public long getContentCount()
    {
        if (_connection==null || _connection.getGenerator()==null)
            return -1;
        return _connection.getGenerator().getContentWritten();
    }

    /* ------------------------------------------------------------ */
    public HttpFields getHttpFields()
    {
        return _connection.getResponseFields();
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return "HTTP/1.1 "+_status+" "+ (_reason==null?"":_reason) +System.getProperty("line.separator")+
        _connection.getResponseFields().toString();
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private static class NullOutput extends ServletOutputStream
    {
        @Override
        public void write(int b) throws IOException
        {
        }

        @Override
        public void print(String s) throws IOException
        {
        }

        @Override
        public void println(String s) throws IOException
        {
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException
        {
        }

    }

}
