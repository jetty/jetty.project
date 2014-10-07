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


package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.EndPoint;

/**
 * A HttpChannel customized to be transported over the HTTP/1 protocol
 */
class HttpChannelOverHttp extends HttpChannel implements HttpParser.RequestHandler, HttpParser.ProxyHandler
{
    private final HttpConnection _httpConnection;
    private final HttpFields _fields = new HttpFields();
    private HttpField _connection;
    private boolean _expect = false;
    private boolean _expect100Continue = false;
    private boolean _expect102Processing = false;
    
    private final MetaData.Request _metadata = new MetaData.Request();
    
    
    public HttpChannelOverHttp(HttpConnection httpConnection, Connector connector, HttpConfiguration config, EndPoint endPoint, HttpTransport transport, HttpInput input)
    {
        super(connector,config,endPoint,transport,input);
        _httpConnection = httpConnection;
        _metadata.setFields(_fields);
        _metadata.setURI(new HttpURI());
    }

    @Override
    public void reset()
    {
        super.reset();
        _expect = false;
        _expect100Continue = false;
        _expect102Processing = false;
        _metadata.getURI().clear();
        _connection=null;
        _fields.clear();
    }

    @Override
    public boolean isExpecting100Continue()
    {
        return _expect100Continue;
    }

    @Override
    public boolean isExpecting102Processing()
    {
        return _expect102Processing;
    }

    @Override
    public boolean startRequest(String method, String uri, HttpVersion version)
    {
        _metadata.setMethod(method);
        _metadata.getURI().parse(uri);
        _metadata.setHttpVersion(version);
        _expect = false;
        _expect100Continue = false;
        _expect102Processing = false;
        return false;
    }
    
    @Override
    public void proxied(String protocol, String sAddr, String dAddr, int sPort, int dPort)
    {
        _metadata.setMethod(HttpMethod.CONNECT.asString());
        Request request = getRequest();
        request.setAttribute("PROXY", protocol);
        request.setAuthority(sAddr,dPort);
        request.setRemoteAddr(InetSocketAddress.createUnresolved(sAddr,sPort));
    }

    @Override
    public void parsedHeader(HttpField field)
    {
        HttpHeader header=field.getHeader();
        String value=field.getValue();
        if (header!=null)
        {
            switch(header)
            {
                case CONNECTION:
                    _connection=field;
                    break;

                case HOST:
                    if (!_metadata.getURI().isAbsolute() && field instanceof HostPortHttpField)
                    {
                        HostPortHttpField hp = (HostPortHttpField)field;
                        _metadata.getURI().setAuthority(hp.getHost(),hp.getPort());
                    }
                    break;

                case EXPECT:
                {
                    if (_metadata.getVersion()==HttpVersion.HTTP_1_1)
                    {
                        HttpHeaderValue expect = HttpHeaderValue.CACHE.get(value);
                        switch (expect == null ? HttpHeaderValue.UNKNOWN : expect)
                        {
                            case CONTINUE:
                                _expect100Continue = true;
                                break;

                            case PROCESSING:
                                _expect102Processing = true;
                                break;

                            default:
                                String[] values = field.getValues();
                                for (int i = 0; values != null && i < values.length; i++)
                                {
                                    expect = HttpHeaderValue.CACHE.get(values[i].trim());
                                    if (expect == null)
                                        _expect = true;
                                    else
                                    {
                                        switch (expect)
                                        {
                                            case CONTINUE:
                                                _expect100Continue = true;
                                                break;
                                            case PROCESSING:
                                                _expect102Processing = true;
                                                break;
                                            default:
                                                _expect = true;
                                        }
                                    }
                                }
                        }
                    }
                    break;
                }
                default:
                    break;
            }
        }
        _fields.add(field);
    }
    
    /**
     * If the associated response has the Expect header set to 100 Continue,
     * then accessing the input stream indicates that the handler/servlet
     * is ready for the request body and thus a 100 Continue response is sent.
     *
     * @throws IOException if the InputStream cannot be created
     */
    @Override
    public void continue100(int available) throws IOException
    {
        // If the client is expecting 100 CONTINUE, then send it now.
        // TODO: consider using an AtomicBoolean ?
        if (isExpecting100Continue())
        {
            _expect100Continue = false;

            // is content missing?
            if (available == 0)
            {
                if (getResponse().isCommitted())
                    throw new IOException("Committed before 100 Continues");

                boolean committed = sendResponse(HttpGenerator.CONTINUE_100_INFO, null, false);
                if (!committed)
                    throw new IOException("Concurrent commit while trying to send 100-Continue");
            }
        }
    }

    @Override
    public void earlyEOF()
    {
        // If we have no request yet, just close
        if (_metadata.getMethod()==null)
            _httpConnection.close();
        else
            onEarlyEOF();
    }

    @Override
    public boolean content(ByteBuffer content)
    {
        // TODO avoid creating the Content object with wrapper?
        onContent(new HttpInput.Content(content));
        return true;
    }

    @Override
    public void badMessage(int status, String reason)
    {
        _httpConnection._generator.setPersistent(false);
        onBadMessage(status,reason);
    }

    @Override
    public boolean headerComplete()
    {
        boolean persistent;

        switch (_metadata.getVersion())
        {
            case HTTP_1_0:
            {
                if (_connection!=null)
                {
                    if (_connection.contains(HttpHeaderValue.KEEP_ALIVE.asString()))
                        persistent=true;
                    else
                        persistent=_fields.contains(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString());
                }
                else
                    persistent=false;
                        
                if (!persistent)
                    persistent = HttpMethod.CONNECT.is(_metadata.getMethod());
                if (persistent)
                    getResponse().getHttpFields().add(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE);
                    
                break;
            }
            
            case HTTP_1_1:
            {
                if (_expect)
                {
                    badMessage(HttpStatus.EXPECTATION_FAILED_417,null);
                    return true;
                }
                
                if (_connection!=null)
                {
                    if (_connection.contains(HttpHeaderValue.CLOSE.asString()))
                        persistent=false;
                    else
                        persistent=!_fields.contains(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString()); // handle multiple connection fields
                }
                else
                    persistent=true;
                
                if (!persistent)
                    persistent = HttpMethod.CONNECT.is(_metadata.getMethod());
                if (!persistent)
                    getResponse().getHttpFields().add(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);
                break;
            }
            
            default:
            {
                throw new IllegalStateException();
            }
        }

        if (!persistent)
            _httpConnection._generator.setPersistent(false);

        onRequest(_metadata);
        return true;
    }

    @Override
    protected void handleException(Throwable x)
    {
        _httpConnection._generator.setPersistent(false);
        super.handleException(x);
    }

    @Override
    public void abort(Throwable failure)
    {
        super.abort(failure);
        _httpConnection._generator.setPersistent(false);
    }

    @Override
    public boolean messageComplete()
    {
        onRequestComplete();
        return false;
    }

    @Override
    public int getHeaderCacheSize()
    {
        return getHttpConfiguration().getHeaderCacheSize();
    }
}
