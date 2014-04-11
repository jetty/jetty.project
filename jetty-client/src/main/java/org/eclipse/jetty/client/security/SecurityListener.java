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

package org.eclipse.jetty.client.security;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;

import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpEventListenerWrapper;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/**
 * SecurityListener
 * 
 * Allow for insertion of security dialog when performing an
 * HttpExchange.
 */
public class SecurityListener extends HttpEventListenerWrapper
{
    private static final Logger LOG = Log.getLogger(SecurityListener.class);
	
    private HttpDestination _destination;
    private HttpExchange _exchange;
    private boolean _requestComplete;
    private boolean _responseComplete;  
    private boolean _needIntercept;
    
    private int _attempts = 0; // TODO remember to settle on winning solution

    public SecurityListener(HttpDestination destination, HttpExchange ex)
    {
        // Start of sending events through to the wrapped listener
        // Next decision point is the onResponseStatus
        super(ex.getEventListener(),true);
        _destination=destination;
        _exchange=ex;
    }
    
    
    /**
     * scrapes an authentication type from the authString
     * 
     * @param authString
     * @return the authentication type
     */
    protected String scrapeAuthenticationType( String authString )
    {
        String authType;

        if ( authString.indexOf( " " ) == -1 )
        {
            authType = authString.toString().trim();
        }
        else
        {
            String authResponse = authString.toString();
            authType = authResponse.substring( 0, authResponse.indexOf( " " ) ).trim();
        }
        return authType;
    }
    
    /**
     * scrapes a set of authentication details from the authString
     * 
     * @param authString
     * @return the authentication details
     */
    protected Map<String, String> scrapeAuthenticationDetails( String authString )
    {
        Map<String, String> authenticationDetails = new HashMap<String, String>();
        authString = authString.substring( authString.indexOf( " " ) + 1, authString.length() );
        StringTokenizer strtok = new StringTokenizer( authString, ",");
        
        while ( strtok.hasMoreTokens() )
        {
            String token = strtok.nextToken();
            String[] pair = token.split( "=" );
            
            // authentication details ought to come in two parts, if not then just skip
            if ( pair.length == 2 )
            {
                String itemName = pair[0].trim();
                String itemValue = pair[1].trim();
                
                itemValue = StringUtil.unquote( itemValue );
                
                authenticationDetails.put( itemName, itemValue );
            }    
            else
            {
                LOG.debug("SecurityListener: missed scraping authentication details - " + token );
            }
        }
        return authenticationDetails;
    }

  
    @Override
    public void onResponseStatus( Buffer version, int status, Buffer reason )
        throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("SecurityListener:Response Status: " + status );

        if ( status == HttpStatus.UNAUTHORIZED_401 && _attempts<_destination.getHttpClient().maxRetries()) 
        {
            // Let's absorb events until we have done some retries
            setDelegatingResponses(false);
            _needIntercept = true;
        }
        else 
        {
            setDelegatingResponses(true);
            setDelegatingRequests(true);
            _needIntercept = false;
        }
        super.onResponseStatus(version,status,reason);
    }


    @Override
    public void onResponseHeader( Buffer name, Buffer value )
        throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug( "SecurityListener:Header: " + name.toString() + " / " + value.toString() );
        
        
        if (!isDelegatingResponses())
        {
            int header = HttpHeaders.CACHE.getOrdinal(name);
            switch (header)
            {
                case HttpHeaders.WWW_AUTHENTICATE_ORDINAL:

                    // TODO don't hard code this bit.
                    String authString = value.toString();
                    String type = scrapeAuthenticationType( authString );                  

                    // TODO maybe avoid this map creation
                    Map<String,String> details = scrapeAuthenticationDetails( authString );
                    String pathSpec="/"; // TODO work out the real path spec
                    RealmResolver realmResolver = _destination.getHttpClient().getRealmResolver();
                    
                    if ( realmResolver == null )
                    {
                        break;
                    }
                    
                    Realm realm = realmResolver.getRealm( details.get("realm"), _destination, pathSpec ); // TODO work our realm correctly 
                    
                    if ( realm == null )
                    {
                        LOG.warn( "Unknown Security Realm: " + details.get("realm") );
                    }
                    else if ("digest".equalsIgnoreCase(type))
                    {
                        _destination.addAuthorization("/",new DigestAuthentication(realm,details));
                        
                    }
                    else if ("basic".equalsIgnoreCase(type))
                    {
                        _destination.addAuthorization(pathSpec,new BasicAuthentication(realm));
                    }
                    
                    break;
            }
        }
        super.onResponseHeader(name,value);
    }
    

    @Override
    public void onRequestComplete() throws IOException
    {
        _requestComplete = true;

        if (_needIntercept)
        {
            if (_requestComplete && _responseComplete)
            {
               if (LOG.isDebugEnabled())
                   LOG.debug("onRequestComplete, Both complete: Resending from onResponseComplete "+_exchange); 
                _responseComplete = false;
                _requestComplete = false;
                setDelegatingRequests(true);
                setDelegatingResponses(true);
                _destination.resend(_exchange);  
            } 
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("onRequestComplete, Response not yet complete onRequestComplete, calling super for "+_exchange);
                super.onRequestComplete(); 
            }
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("onRequestComplete, delegating to super with Request complete="+_requestComplete+", response complete="+_responseComplete+" "+_exchange);
            super.onRequestComplete();
        }
    }


    @Override
    public void onResponseComplete() throws IOException
    {   
        _responseComplete = true;
        if (_needIntercept)
        {  
            if (_requestComplete && _responseComplete)
            {              
                if (LOG.isDebugEnabled())
                    LOG.debug("onResponseComplete, Both complete: Resending from onResponseComplete"+_exchange);
                _responseComplete = false;
                _requestComplete = false;
                setDelegatingResponses(true);
                setDelegatingRequests(true);
                _destination.resend(_exchange); 

            }
            else
            {
               if (LOG.isDebugEnabled())
                   LOG.debug("onResponseComplete, Request not yet complete from onResponseComplete,  calling super "+_exchange);
                super.onResponseComplete(); 
            }
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("OnResponseComplete, delegating to super with Request complete="+_requestComplete+", response complete="+_responseComplete+" "+_exchange);
            super.onResponseComplete();  
        }
    }

    @Override
    public void onRetry()
    {
        _attempts++;
        setDelegatingRequests(true);
        setDelegatingResponses(true);
        _requestComplete=false;
        _responseComplete=false;
        _needIntercept=false;
        super.onRetry();
    }  
    
    
}
