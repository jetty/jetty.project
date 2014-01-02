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

package org.eclipse.jetty.client;

import java.io.IOException;

import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpSchemes;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Buffer;

/**
 * RedirectListener
 *
 * Detect and handle the redirect responses
 */
public class RedirectListener extends HttpEventListenerWrapper
{
    private final HttpExchange _exchange;
    private HttpDestination _destination;
    private String _location;
    private int _attempts;
    private boolean _requestComplete;
    private boolean _responseComplete;
    private boolean _redirected;

    public RedirectListener(HttpDestination destination, HttpExchange ex)
    {
        // Start of sending events through to the wrapped listener
        // Next decision point is the onResponseStatus
        super(ex.getEventListener(),true);

        _destination = destination;
        _exchange = ex;
    }

    @Override
    public void onResponseStatus( Buffer version, int status, Buffer reason )
        throws IOException
    {
        _redirected = ((status == HttpStatus.MOVED_PERMANENTLY_301 ||
                        status == HttpStatus.MOVED_TEMPORARILY_302) &&
                       _attempts < _destination.getHttpClient().maxRedirects());

        if (_redirected)
        {
            setDelegatingRequests(false);
            setDelegatingResponses(false);
        }

        super.onResponseStatus(version,status,reason);
    }


    @Override
    public void onResponseHeader( Buffer name, Buffer value )
        throws IOException
    {
        if (_redirected)
        {
            int header = HttpHeaders.CACHE.getOrdinal(name);
            switch (header)
            {
                case HttpHeaders.LOCATION_ORDINAL:
                    _location = value.toString();
                    break;
            }
        }
        super.onResponseHeader(name,value);
    }

    @Override
    public void onRequestComplete() throws IOException
    {
        _requestComplete = true;

        if (checkExchangeComplete())
        {
            super.onRequestComplete();
        }
    }

    @Override
    public void onResponseComplete() throws IOException
    {
        _responseComplete = true;

        if (checkExchangeComplete())
        {
            super.onResponseComplete();
        }
    }

    public boolean checkExchangeComplete()
        throws IOException
    {
        if (_redirected && _requestComplete && _responseComplete)
        {
            if (_location != null)
            {
                if (_location.indexOf("://")>0)
                {
                    _exchange.setURL(_location);
                }
                else
                {
                    _exchange.setRequestURI(_location);
                }

                // destination may have changed
                boolean isHttps = HttpSchemes.HTTPS.equals(String.valueOf(_exchange.getScheme()));
                HttpDestination destination=_destination.getHttpClient().getDestination(_exchange.getAddress(),isHttps);

                if (_destination==destination)
                {
                    _destination.resend(_exchange);
                }
                else
                {
                    // unwrap to find ultimate listener.
                    HttpEventListener listener=this;
                    while(listener instanceof HttpEventListenerWrapper)
                    {
                        listener=((HttpEventListenerWrapper)listener).getEventListener();
                    }
                    
                    //reset the listener
                    _exchange.getEventListener().onRetry();
                    _exchange.reset();
                    _exchange.setEventListener(listener);

                    // Set the new Host header
                    Address address = _exchange.getAddress();
                    int port = address.getPort();
                    StringBuilder hostHeader = new StringBuilder( 64 );
                    hostHeader.append( address.getHost() );
                    if( !( ( port == 80 && !isHttps ) || ( port == 443 && isHttps ) ) ) 
                    {
                        hostHeader.append( ':' );
                        hostHeader.append( port );
                    }
                    
                    _exchange.setRequestHeader( HttpHeaders.HOST, hostHeader.toString() );

                    destination.send(_exchange);
                }

                return false;
            }
            else
            {
                setDelegationResult(false);
            }
        }

        return true;
    }

    public void onRetry()
    {
        _redirected=false;
        _attempts++;

        setDelegatingRequests(true);
        setDelegatingResponses(true);

        _requestComplete=false;
        _responseComplete=false;

        super.onRetry();
    }

    /**
     * Delegate failed connection
     */
    @Override
    public void onConnectionFailed( Throwable ex )
    {
        setDelegatingRequests(true);
        setDelegatingResponses(true);

        super.onConnectionFailed( ex );
    }

    /**
     * Delegate onException
     */
    @Override
    public void onException( Throwable ex )
    {
        setDelegatingRequests(true);
        setDelegatingResponses(true);

        super.onException( ex );
    }
}
