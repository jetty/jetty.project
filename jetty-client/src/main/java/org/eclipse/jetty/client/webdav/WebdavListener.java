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


package org.eclipse.jetty.client.webdav;

import java.io.IOException;

import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpEventListenerWrapper;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.security.SecurityListener;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * WebdavListener
 * 
 * 
 * 
 * 
 */
public class WebdavListener extends HttpEventListenerWrapper
{
    private static final Logger LOG = Log.getLogger(WebdavListener.class);

    private HttpDestination _destination;
    private HttpExchange _exchange;
    private boolean _requestComplete;
    private boolean _responseComplete; 
    private boolean _webdavEnabled;
    private boolean _needIntercept;

    public WebdavListener(HttpDestination destination, HttpExchange ex)
    {
        // Start of sending events through to the wrapped listener
        // Next decision point is the onResponseStatus
        super(ex.getEventListener(),true);
        _destination=destination;
        _exchange=ex;

        // We'll only enable webdav if this is a PUT request
        if ( HttpMethods.PUT.equalsIgnoreCase( _exchange.getMethod() ) )
        {
            _webdavEnabled = true;
        }
    }

    @Override
    public void onResponseStatus(Buffer version, int status, Buffer reason) throws IOException
    {
        if ( !_webdavEnabled )
        {
            _needIntercept = false;
            super.onResponseStatus(version, status, reason);
            return;
        }
        
        if (LOG.isDebugEnabled())
            LOG.debug("WebdavListener:Response Status: " + status );

        // The dav spec says that CONFLICT should be returned when the parent collection doesn't exist but I am seeing
        // FORBIDDEN returned instead so running with that.
        if ( status == HttpStatus.FORBIDDEN_403 || status == HttpStatus.CONFLICT_409 )
        {
            if ( _webdavEnabled )
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("WebdavListener:Response Status: dav enabled, taking a stab at resolving put issue" );
                setDelegatingResponses( false ); // stop delegating, we can try and fix this request
                _needIntercept = true;
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("WebdavListener:Response Status: Webdav Disabled" );
                setDelegatingResponses( true ); // just make sure we delegate
                setDelegatingRequests( true );
                _needIntercept = false;
            }
        }
        else
        {
            _needIntercept = false;
            setDelegatingResponses( true );
            setDelegatingRequests( true );
        }

        super.onResponseStatus(version, status, reason);
    }

    @Override
    public void onResponseComplete() throws IOException
    {
        _responseComplete = true;
        if (_needIntercept)
        {
            if ( _requestComplete && _responseComplete)
            {
                try
                {
                    // we have some work to do before retrying this
                    if ( resolveCollectionIssues() )
                    {
                        setDelegatingRequests( true );
                        setDelegatingResponses(true);
                        _requestComplete = false;
                        _responseComplete = false;
                        _destination.resend(_exchange);
                    }
                    else
                    {
                        // admit defeat but retry because someone else might have 
                        setDelegationResult(false);
                        setDelegatingRequests( true );
                        setDelegatingResponses(true);
                        super.onResponseComplete();
                    }
                }
                catch ( IOException ioe )
                {
                    LOG.debug("WebdavListener:Complete:IOException: might not be dealing with dav server, delegate");
                    super.onResponseComplete();
                }
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("WebdavListener:Not ready, calling super");
                super.onResponseComplete();
            }
        }
        else
        {
            super.onResponseComplete();
        }
    }

    
    
    @Override
    public void onRequestComplete () throws IOException
    {
        _requestComplete = true;
        if (_needIntercept)
        {
            if ( _requestComplete && _responseComplete)
            {
                try
                {
                    // we have some work to do before retrying this
                    if ( resolveCollectionIssues() )
                    {
                        setDelegatingRequests( true );
                        setDelegatingResponses(true);
                        _requestComplete = false;
                        _responseComplete = false;
                        _destination.resend(_exchange);
                    }
                    else
                    {
                        // admit defeat but retry because someone else might have 
                        setDelegatingRequests( true );
                        setDelegatingResponses(true);
                        super.onRequestComplete();
                    }
                }
                catch ( IOException ioe )
                {
                    LOG.debug("WebdavListener:Complete:IOException: might not be dealing with dav server, delegate");
                    super.onRequestComplete();
                }
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("WebdavListener:Not ready, calling super");
                super.onRequestComplete();
            }
        }
        else
        {
            super.onRequestComplete();
        } 
    }

   
    
    
    /**
     * walk through the steps to try and resolve missing parent collection issues via webdav
     *
     * TODO this really ought to use URI itself for this resolution
     *
     * @return
     * @throws IOException
     */
    private boolean resolveCollectionIssues() throws IOException
    {

        String uri = _exchange.getURI();
        String[] uriCollection = _exchange.getURI().split("/");
        int checkNum = uriCollection.length;
        int rewind = 0;

        String parentUri = URIUtil.parentPath( uri );
        while ( parentUri != null && !checkExists(parentUri) )
        {
            ++rewind;
            parentUri = URIUtil.parentPath( parentUri );
        }

        // confirm webdav is supported for this collection
        if ( checkWebdavSupported() )
        {
            for (int i = 0; i < rewind;)
            {
                makeCollection(parentUri + "/" + uriCollection[checkNum - rewind - 1]);
                parentUri = parentUri + "/" + uriCollection[checkNum - rewind - 1];
                --rewind;
            }
        }
        else
        {
            return false;
        }

        return true;
    }

    private boolean checkExists( String uri ) throws IOException
    {
        if (uri == null)
        {
            System.out.println("have failed miserably");
            return false;
        }
        
        PropfindExchange propfindExchange = new PropfindExchange();
        propfindExchange.setAddress( _exchange.getAddress() );
        propfindExchange.setMethod( HttpMethods.GET ); // PROPFIND acts wonky, just use get
        propfindExchange.setScheme( _exchange.getScheme() );
        propfindExchange.setEventListener( new SecurityListener( _destination, propfindExchange ) );
        propfindExchange.setConfigureListeners( false );
        propfindExchange.setRequestURI( uri );

        _destination.send( propfindExchange );

        try
        {
            propfindExchange.waitForDone();

            return propfindExchange.exists();
        }
        catch ( InterruptedException ie )
        {
            LOG.ignore( ie );                  
            return false;
        }
    }

    private boolean makeCollection( String uri ) throws IOException
    {
        MkcolExchange mkcolExchange = new MkcolExchange();
        mkcolExchange.setAddress( _exchange.getAddress() );
        mkcolExchange.setMethod( "MKCOL " + uri + " HTTP/1.1" );
        mkcolExchange.setScheme( _exchange.getScheme() );
        mkcolExchange.setEventListener( new SecurityListener( _destination, mkcolExchange ) );
        mkcolExchange.setConfigureListeners( false );
        mkcolExchange.setRequestURI( uri );

        _destination.send( mkcolExchange );

        try
        {
            mkcolExchange.waitForDone();

            return mkcolExchange.exists();
        }
        catch ( InterruptedException ie )
        {
            LOG.ignore( ie );
            return false;
        }
    }

    
    private boolean checkWebdavSupported() throws IOException
    {
        WebdavSupportedExchange supportedExchange = new WebdavSupportedExchange();
        supportedExchange.setAddress( _exchange.getAddress() );
        supportedExchange.setMethod( HttpMethods.OPTIONS );
        supportedExchange.setScheme( _exchange.getScheme() );
        supportedExchange.setEventListener( new SecurityListener( _destination, supportedExchange ) );
        supportedExchange.setConfigureListeners( false );
        supportedExchange.setRequestURI( _exchange.getURI() );

        _destination.send( supportedExchange );

        try
        {
            supportedExchange.waitTilCompletion();
            return supportedExchange.isWebdavSupported();
        }
        catch (InterruptedException ie )
        {            
            LOG.ignore( ie );
            return false;
        }

    }

}
