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


package org.eclipse.jetty.server;

import java.io.IOException;
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
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * A HttpChannel customized to be transported over the HTTP/1 protocol
 */
class HttpChannelOverHttp extends HttpChannel implements HttpParser.RequestHandler
{
    private static final Logger LOG = Log.getLogger(HttpChannelOverHttp.class);
    
    private final HttpConnection _httpConnection;
    private final HttpFields _fields = new HttpFields();
    private HttpField _connection;
    private boolean _unknownExpectation = false;
    private boolean _expect100Continue = false;
    private boolean _expect102Processing = false;
    
    private final MetaData.Request _metadata = new MetaData.Request();

    public HttpChannelOverHttp(HttpConnection httpConnection, Connector connector, HttpConfiguration config, EndPoint endPoint, HttpTransport transport)
    {
        this(httpConnection,connector,config,endPoint,transport,new HttpInputOverHTTP(httpConnection));
    }
    
    public HttpChannelOverHttp(HttpConnection httpConnection, Connector connector, HttpConfiguration config, EndPoint endPoint, HttpTransport transport,HttpInput input)
    {
        super(connector,config,endPoint,transport,input);
        _httpConnection = httpConnection;
        _metadata.setFields(_fields);
        _metadata.setURI(new HttpURI());
    }
    
    protected HttpInput newHttpInput()
    {
        throw new IllegalStateException();
    }

    @Override
    public void recycle()
    {
        super.recycle();
        _unknownExpectation = false;
        _expect100Continue = false;
        _expect102Processing = false;
        _metadata.recycle();
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
        _unknownExpectation = false;
        _expect100Continue = false;
        _expect102Processing = false;
        return false;
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
                                        _unknownExpectation = true;
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
                                                _unknownExpectation = true;
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
        _httpConnection.getGenerator().setPersistent(false);
        try
        {
            // Need to call onRequest, so RequestLog can reports as much as possible
            onRequest(_metadata);
        }
        catch (Exception e)
        {
            LOG.ignore(e);
        }
        
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
                if (_unknownExpectation)
                {
                    badMessage(HttpStatus.EXPECTATION_FAILED_417,null);
                    return false;
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
            
            case HTTP_2:
            {
                // Allow sneaky "upgrade" to HTTP_2_0 only if the connector supports h2, but not protocol negotiation
                ConnectionFactory h2=null;
                if (!(getConnector().getDefaultConnectionFactory() instanceof NegotiatingServerConnectionFactory))
                    for (ConnectionFactory factory : getConnector().getConnectionFactories())
                        if (factory.getProtocols().contains("h2c"))
                            h2=factory;
                
                // If now a sneaky "upgrade" then a real upgrade is required 
                if (h2==null ||
                    _metadata.getMethod()!=HttpMethod.PRI.asString() ||
                    !"*".equals(_metadata.getURI().toString()) ||
                    _fields.size()>0)
                {
                    badMessage(HttpStatus.UPGRADE_REQUIRED_426,null);
                    return false;
                }                    
                
                // Do a direct upgrade. Even though this is a HTTP/1 connector, we have seen a
                // HTTP/2.0 prefix, so let the request through
                Connection old_connection=getEndPoint().getConnection();
                Connection new_connection = h2.newConnection(getConnector(),getEndPoint());
                if (LOG.isDebugEnabled())
                    LOG.debug("Direct Upgrade from {} to {}", old_connection,new_connection);
                getResponse().setStatus(101); // This will not get sent
                getRequest().setAttribute(HttpConnection.UPGRADE_CONNECTION_ATTRIBUTE,new_connection);
                getHttpTransport().onCompleted();                
                return true;
            }
                
            default:
            {
                throw new IllegalStateException();
            }
        }

        if (!persistent)
            _httpConnection.getGenerator().setPersistent(false);

        onRequest(_metadata);

        // Should we delay dispatch until we have some content?
        // We should not delay if there is no content expect or client is expecting 100 or the response is already committed or the request buffer already has something in it to parse
        if (getHttpConfiguration().isDelayDispatchUntilContent() && _httpConnection.getParser().getContentLength()>0 && !isExpecting100Continue() && !isCommitted() && _httpConnection.isRequestBufferEmpty())
            return false;
            
        return true;
    }

    @Override
    protected void handleException(Throwable x)
    {
        _httpConnection.getGenerator().setPersistent(false);
        super.handleException(x);
    }

    @Override
    public void abort(Throwable failure)
    {
        super.abort(failure);
        _httpConnection.getGenerator().setPersistent(false);
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
