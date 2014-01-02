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

package org.eclipse.jetty.websocket.mux.server;

import java.io.IOException;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.mux.MuxChannel;
import org.eclipse.jetty.websocket.mux.MuxException;
import org.eclipse.jetty.websocket.mux.Muxer;
import org.eclipse.jetty.websocket.mux.add.MuxAddServer;

/**
 * Handler for incoming MuxAddChannel requests.
 */
public class MuxAddHandler implements MuxAddServer
{
    /** Represents physical connector */
    private Connector connector;

    /** Used for local address */
    private EndPoint endPoint;

    /** The original request handshake */
    private UpgradeRequest baseHandshakeRequest;

    /** The original request handshake */
    private UpgradeResponse baseHandshakeResponse;

    private int maximumHeaderSize = 32 * 1024;

    @Override
    public UpgradeRequest getPhysicalHandshakeRequest()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public UpgradeResponse getPhysicalHandshakeResponse()
    {
        // TODO Auto-generated method stub
        return null;
    }

    /**
     * An incoming MuxAddChannel request.
     * 
     * @param muxer the muxer handling this
     * @param channel the
     *            channel this request should be bound to
     * @param request
     *            the incoming request headers (complete and merged if delta encoded)
     */
    @Override
    public void handshake(Muxer muxer, MuxChannel channel, UpgradeRequest request) throws MuxException, IOException
    {
        // Need to call into HttpChannel to get the websocket properly setup.
        HttpTransportOverMux transport = new HttpTransportOverMux(muxer,channel);
        EmptyHttpInput input = new EmptyHttpInput();
        HttpConfiguration configuration = new HttpConfiguration();

        HttpChannelOverMux httpChannel = new HttpChannelOverMux(//
                connector,configuration,endPoint,transport,input);

        HttpMethod method = HttpMethod.fromString(request.getMethod());
        HttpVersion version = HttpVersion.fromString(request.getHttpVersion());
        httpChannel.startRequest(method,request.getMethod(),BufferUtil.toBuffer(request.getRequestURI().toASCIIString()),version);

        for (String headerName : request.getHeaders().keySet())
        {
            HttpHeader header = HttpHeader.CACHE.getBest(headerName.getBytes(),0,headerName.length());
            for (String value : request.getHeaders().get(headerName))
            {
                httpChannel.parsedHeader(new HttpField(header,value));
            }
        }

        httpChannel.headerComplete();
        httpChannel.messageComplete();
        httpChannel.run(); // calls into server for appropriate resource

        // TODO: what's in request handshake is not enough to process the request.
        // like a partial http request. (consider this a AddChannelRequest failure)
        throw new MuxException("Not a valid request");
    }
}
