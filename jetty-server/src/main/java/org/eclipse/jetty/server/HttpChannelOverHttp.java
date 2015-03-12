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
    
    private final HttpFields _fields = new HttpFields();
    private final MetaData.Request _metadata = new MetaData.Request(_fields);
    private final HttpConnection _httpConnection;
    private HttpField _connection;
    private boolean _delayedForContent;
    private boolean _unknownExpectation = false;
    private boolean _expect100Continue = false;
    private boolean _expect102Processing = false;
    private HttpField _http2Upgrade = null;

    
    public HttpChannelOverHttp(HttpConnection httpConnection, Connector connector, HttpConfiguration config, EndPoint endPoint, HttpTransport transport)
    {
        super(connector,config,endPoint,transport);
        _httpConnection = httpConnection;
        _metadata.setURI(new HttpURI());
    }
    
    @Override
    protected HttpInput newHttpInput(HttpChannelState state)
    {
        return new HttpInputOverHTTP(state);
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
        _http2Upgrade=null;
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

                case UPGRADE:
                    if (value.startsWith("h2c"))
                        _http2Upgrade=field;
                    break;

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
        HttpInput.Content c = _httpConnection.newContent(content);        
        boolean handle = onContent(c) || _delayedForContent;
        _delayedForContent=false;
        return handle;
    }

    public void asyncReadFillInterested()
    {
        _httpConnection.asyncReadFillInterested();        
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
                
                if (_http2Upgrade!=null && http2Upgrade())
                    return true;
                
                break;
            }
            
            case HTTP_2:
            {
                // Allow sneaky "upgrade" to HTTP_2_0 only if the connector supports h2, but not protocol negotiation
                return http2Upgrade();
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
        _delayedForContent =  (getHttpConfiguration().isDelayDispatchUntilContent() && _httpConnection.getParser().getContentLength()>0 && !isExpecting100Continue() && !isCommitted() && _httpConnection.isRequestBufferEmpty());
        
        return !_delayedForContent;
    }

    private boolean http2Upgrade()
    {
        LOG.debug("h2c upgrade {}",this);
        // Find the h2 factory
        ConnectionFactory h2=null;
        if (!(getConnector().getDefaultConnectionFactory() instanceof NegotiatingServerConnectionFactory))
        {
            loop: for (ConnectionFactory factory : getConnector().getConnectionFactories())
                for (String protocol : factory.getProtocols())
                    if (protocol.startsWith("h2c"))
                    {
                        h2=factory;
                        break loop;
                    }
        }
        Connection old_connection=getEndPoint().getConnection();
        Connection new_connection;
        
        
        if (_http2Upgrade==null)
        {
            LOG.debug("h2c preamble upgrade {}",this);
            // This must be a sneaky upgrade triggered by the http2 preamble!
            // If we don't have a HTTP factory or the preamble does not look right, then bad message
            if (h2==null ||
                _metadata.getMethod()!=HttpMethod.PRI.asString() ||
                !"*".equals(_metadata.getURI().toString()) ||
                _fields.size()>0)
            {
                badMessage(HttpStatus.UPGRADE_REQUIRED_426,null);
                return false;
            }  
            
            getResponse().setStatus(101);  // wont be sent    
            new_connection = h2.newConnection(getConnector(),getEndPoint(), null);          
        }
        else
        {
            // This is a standard upgrade, so failures are not bad message, just a false return
            if (h2==null)
            {
                LOG.debug("No h2c factory for {}",this);
                return false;
            }
            
            if (!h2.getProtocols().contains(_http2Upgrade.getValue()))
            {
                LOG.debug("No h2c version {} for {}",_http2Upgrade.getValue(),this);
                return false;
            }
            
            if (_connection==null || !_connection.getValue().contains("Upgrade") || !_connection.getValue().contains("HTTP2-Settings"))
            {
                LOG.debug("Bad h2c {} for {}",_connection,this);
                return false;
            }

            getResponse().setStatus(101); 
            HttpFields fields = new HttpFields();
            
            try
            {
                sendResponse(new MetaData.Response(HttpVersion.HTTP_1_1,HttpStatus.SWITCHING_PROTOCOLS_101,fields,0),null,true);
            }
            catch(IOException e)
            {
                LOG.warn(e);
                badMessage(HttpStatus.INTERNAL_SERVER_ERROR_500,null);
                return false;
            }

            new_connection = h2.newConnection(getConnector(),getEndPoint(),_metadata);
        }
        
        if (LOG.isDebugEnabled())
            LOG.debug("Upgrade from {} to {}", old_connection,new_connection);
        getRequest().setAttribute(HttpConnection.UPGRADE_CONNECTION_ATTRIBUTE,new_connection);
        getHttpTransport().onCompleted();                
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
        return onRequestComplete();
    }

    @Override
    public int getHeaderCacheSize()
    {
        return getHttpConfiguration().getHeaderCacheSize();
    }
}
