// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.handler;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.AsyncContinuation;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.LazyList;

public class StatisticsHandler extends HandlerWrapper implements CompleteHandler
{
    transient long _statsStartedAt;
    
    transient int _requests;
    
    transient long _requestsDurationMin;         // min request duration
    transient long _requestsDurationMax;         // max request duration
    transient long _requestsDurationTotal;       // total request duration
    transient long _requestsActiveDurationMin;   // min request active duration
    transient long _requestsActiveDurationMax;   // max request active duration
    transient long _requestsActiveDurationTotal; // total request active duration
    
    transient int _requestsActive;
    transient int _requestsActiveMin;            // min number of connections handled simultaneously
    transient int _requestsActiveMax;
    transient int _requestsResumed;
    transient int _requestsTimedout;             // requests that timed out while suspended
    transient int _responses1xx; // Informal
    transient int _responses2xx; // Success
    transient int _responses3xx; // Redirection
    transient int _responses4xx; // Client Error
    transient int _responses5xx; // Server Error
    
    transient long _responsesBytesTotal;
       
    /* ------------------------------------------------------------ */
    public void statsReset()
    {
        synchronized(this)
        {
            if (isStarted())
                _statsStartedAt=System.currentTimeMillis();
            _requests=0;
            _responses1xx=0;
            _responses2xx=0;
            _responses3xx=0;
            _responses4xx=0;
            _responses5xx=0;
          
            _requestsActiveMin=_requestsActive;
            _requestsActiveMax=_requestsActive;

            _requestsDurationMin=0;
            _requestsDurationMax=0;
            _requestsDurationTotal=0;
            
            _requestsActiveDurationMin=0;
            _requestsActiveDurationMax=0;
            _requestsActiveDurationTotal=0;
        }
    }


    /* ------------------------------------------------------------ */
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        final Response base_response=baseRequest.getResponse();
        
        long timestamp0=baseRequest.getTimeStamp();
        long timestamp1=timestamp0;
        try
        {
            synchronized(this)
            {
                AsyncContinuation asyncContextState=baseRequest.getAsyncContinuation();

                if(asyncContextState==null)
                {
                    _requests++;
                }
                else
                {
                    if(asyncContextState.isInitial())
                        _requests++;
                    else
                    {
                        timestamp1=System.currentTimeMillis();
                        /*
                        if (asyncContextState.isTimeout())
                            _requestsTimedout++;
                        if(asyncContextState.isResumed())
                            _requestsResumed++;
                        */
                    }
                }

                _requestsActive++;
                if (_requestsActive>_requestsActiveMax)
                    _requestsActiveMax=_requestsActive;
            }
            
            super.handle(target, baseRequest, request, response);
        }
        finally
        {
            synchronized(this)
            {
                _requestsActive--;
                if (_requestsActive<0)
                    _requestsActive=0;
                if (_requestsActive < _requestsActiveMin)
                    _requestsActiveMin=_requestsActive;

                long duration = System.currentTimeMillis()-timestamp1;
                _requestsActiveDurationTotal+=duration;
                if (_requestsActiveDurationMin==0 || duration<_requestsActiveDurationMin)
                    _requestsActiveDurationMin=duration;
                if (duration>_requestsActiveDurationMax)
                    _requestsActiveDurationMax=duration;

                
                if(baseRequest.isAsyncStarted())
                {
                    Object list = baseRequest.getAttribute(COMPLETE_HANDLER_ATTR);
                    baseRequest.setAttribute(COMPLETE_HANDLER_ATTR, LazyList.add(list, this));
                }
                else
                {
                    duration = System.currentTimeMillis()-timestamp0;                    
                    addRequestsDurationTotal(duration);
                    
                    switch(base_response.getStatus()/100)
                    {
                        case 1: _responses1xx++;break;
                        case 2: _responses2xx++;break;
                        case 3: _responses3xx++;break;
                        case 4: _responses4xx++;break;
                        case 5: _responses5xx++;break;
                    }
                    
                    _responsesBytesTotal += base_response.getContentCount();
                }                                            
            }
        }
    }

    /* ------------------------------------------------------------ */
    protected void doStart() throws Exception
    {
        super.doStart();
        _statsStartedAt=System.currentTimeMillis();
    }

    /* ------------------------------------------------------------ */
    protected void doStop() throws Exception
    {
        super.doStop();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return Get the number of requests handled by this context
     * since last call of statsReset(), not counting resumed requests.
     * If setStatsOn(false) then this is undefined.
     */
    public int getRequests() {return _requests;}

    /* ------------------------------------------------------------ */
    /**
     * @return Number of requests currently active.
     * Undefined if setStatsOn(false).
     */
    public int getRequestsActive() {return _requestsActive;}

    /* ------------------------------------------------------------ */
    /**
     * @return Number of requests that have been resumed.
     * Undefined if setStatsOn(false).
     */
    public int getRequestsResumed() {return _requestsResumed;}

    /* ------------------------------------------------------------ */
    /**
     * @return Number of requests that timed out while suspended.
     * Undefined if setStatsOn(false).
     */
    public int getRequestsTimedout() {return _requestsTimedout;}

    /* ------------------------------------------------------------ */
    /**
     * @return Maximum number of active requests
     * since statsReset() called. Undefined if setStatsOn(false).
     */
    public int getRequestsActiveMax() {return _requestsActiveMax;}

    /* ------------------------------------------------------------ */
    /**
     * @return Get the number of responses with a 2xx status returned
     * by this context since last call of statsReset(). Undefined if
     * if setStatsOn(false).
     */
    public int getResponses1xx() {return _responses1xx;}

    /* ------------------------------------------------------------ */
    /**
     * @return Get the number of responses with a 100 status returned
     * by this context since last call of statsReset(). Undefined if
     * if setStatsOn(false).
     */
    public int getResponses2xx() {return _responses2xx;}

    /* ------------------------------------------------------------ */
    /**
     * @return Get the number of responses with a 3xx status returned
     * by this context since last call of statsReset(). Undefined if
     * if setStatsOn(false).
     */
    public int getResponses3xx() {return _responses3xx;}

    /* ------------------------------------------------------------ */
    /**
     * @return Get the number of responses with a 4xx status returned
     * by this context since last call of statsReset(). Undefined if
     * if setStatsOn(false).
     */
    public int getResponses4xx() {return _responses4xx;}

    /* ------------------------------------------------------------ */
    /**
     * @return Get the number of responses with a 5xx status returned
     * by this context since last call of statsReset(). Undefined if
     * if setStatsOn(false).
     */
    public int getResponses5xx() {return _responses5xx;}

    /* ------------------------------------------------------------ */
    /** 
     * @return Timestamp stats were started at.
     */
    public long getStatsOnMs()
    {
        return System.currentTimeMillis()-_statsStartedAt;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the requestsActiveMin.
     */
    public int getRequestsActiveMin()
    {
        return _requestsActiveMin;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the requestsDurationMin.
     */
    public long getRequestsDurationMin()
    {
        return _requestsDurationMin;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the requestsDurationTotal.
     */
    public long getRequestsDurationTotal()
    {
        return _requestsDurationTotal;
    }

    /* ------------------------------------------------------------ */
    /** 
     * @return Average duration of request handling in milliseconds 
     * since statsReset() called. Undefined if setStatsOn(false).
     */
    public long getRequestsDurationAve() {return _requests==0?0:(_requestsDurationTotal/_requests);}

    /* ------------------------------------------------------------ */
    /** 
     * @return Get maximum duration in milliseconds of request handling
     * since statsReset() called. Undefined if setStatsOn(false).
     */
    public long getRequestsDurationMax() {return _requestsDurationMax;}
    
    /* ------------------------------------------------------------ */
    /**
     * @return Returns the requestsActiveDurationMin.
     */
    public long getRequestsActiveDurationMin()
    {
        return _requestsActiveDurationMin;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the requestsActiveDurationTotal.
     */
    public long getRequestsActiveDurationTotal()
    {
        return _requestsActiveDurationTotal;
    }

    /* ------------------------------------------------------------ */
    /** 
     * @return Average duration of request handling in milliseconds 
     * since statsReset() called. Undefined if setStatsOn(false).
     */
    public long getRequestsActiveDurationAve() {return _requests==0?0:(_requestsActiveDurationTotal/_requests);}

    /* ------------------------------------------------------------ */
    /** 
     * @return Get maximum duration in milliseconds of request handling
     * since statsReset() called. Undefined if setStatsOn(false).
     */
    public long getRequestsActiveDurationMax() {return _requestsActiveDurationMax;}
    
    /* ------------------------------------------------------------ */
    /** 
     * @return Total bytes of content sent in responses
     */
    public long getResponsesBytesTotal() {return _responsesBytesTotal; }
    
    private void addRequestsDurationTotal(long duration) 
    {
        synchronized(this)
        {
            _requestsDurationTotal+=duration;
            if (_requestsDurationMin==0 || duration<_requestsDurationMin)
                _requestsDurationMin=duration;
            if (duration>_requestsDurationMax)
                _requestsDurationMax=duration;
        }
    }

    
    /* ------------------------------------------------------------ */
    /**
     * Handle completed requests.
     * 
     * @param request
     *                the request which has just completed
     */
    public void complete(Request request)
    {
        long duration = System.currentTimeMillis() - request.getTimeStamp();
        addRequestsDurationTotal(duration);
    }

}
