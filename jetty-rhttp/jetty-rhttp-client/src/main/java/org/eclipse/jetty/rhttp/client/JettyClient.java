//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.rhttp.client;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;

import org.eclipse.jetty.client.Address;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.EofException;

/**
 * Implementation of {@link RHTTPClient} that uses Jetty's HttpClient.
 *
 * @version $Revision$ $Date$
 */
public class JettyClient extends AbstractClient
{
    private final HttpClient httpClient;
    private final Address gatewayAddress;
    private final String gatewayPath;

    public JettyClient(HttpClient httpClient, Address gatewayAddress, String gatewayPath, String targetId)
    {
        super(targetId);
        this.httpClient = httpClient;
        this.gatewayAddress = gatewayAddress;
        this.gatewayPath = gatewayPath;
    }
    
    public JettyClient(HttpClient httpClient, String gatewayURI, String targetId)
    {
        super(targetId);
        
        HttpURI uri = new HttpURI(gatewayURI);
        
        this.httpClient = httpClient;
        this.gatewayAddress = new Address(uri.getHost(),uri.getPort());
        this.gatewayPath = uri.getPath();
    }
    
    public String getHost()
    {
        return gatewayAddress.getHost();
    }

    public int getPort()
    {
        return gatewayAddress.getPort();
    }

    public String getPath()
    {
        return gatewayPath;
    }
    
    @Override
    protected void doStart() throws Exception
    {
        httpClient.start();
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        httpClient.stop();
    }

    protected void syncHandshake() throws IOException
    {
        HandshakeExchange exchange = new HandshakeExchange();
        exchange.setMethod(HttpMethods.POST);
        exchange.setAddress(gatewayAddress);
        exchange.setURI(gatewayPath + "/" + urlEncode(getTargetId()) + "/handshake");
        httpClient.send(exchange);
        getLogger().debug("Client {} handshake sent to gateway", getTargetId(), null);

        try
        {
            int exchangeStatus = exchange.waitForDone();
            if (exchangeStatus != HttpExchange.STATUS_COMPLETED)
                throw new IOException("Handshake failed");
            if (exchange.getResponseStatus() != 200)
                throw new IOException("Handshake failed");
            getLogger().debug("Client {} handshake returned from gateway", getTargetId(), null);
        }
        catch (InterruptedException x)
        {
            Thread.currentThread().interrupt();
            throw newIOException(x);
        }
    }

    private IOException newIOException(Throwable x)
    {
        return (IOException)new IOException().initCause(x);
    }

    protected void asyncConnect()
    {
        try
        {
            ConnectExchange exchange = new ConnectExchange();
            exchange.setMethod(HttpMethods.POST);
            exchange.setAddress(gatewayAddress);
            exchange.setURI(gatewayPath + "/" + urlEncode(getTargetId()) + "/connect");
            httpClient.send(exchange);
            getLogger().debug("Client {} connect sent to gateway", getTargetId(), null);
        }
        catch (IOException x)
        {
            getLogger().debug("Could not send exchange", x);
            throw new RuntimeException(x);
        }
    }

    protected void syncDisconnect() throws IOException
    {
        DisconnectExchange exchange = new DisconnectExchange();
        exchange.setMethod(HttpMethods.POST);
        exchange.setAddress(gatewayAddress);
        exchange.setURI(gatewayPath + "/" + urlEncode(getTargetId()) + "/disconnect");
        httpClient.send(exchange);
        getLogger().debug("Client {} disconnect sent to gateway", getTargetId(), null);
        try
        {
            int status = exchange.waitForDone();
            if (status != HttpExchange.STATUS_COMPLETED)
                throw new IOException("Disconnect failed");
            if (exchange.getResponseStatus() != 200)
                throw new IOException("Disconnect failed");
            getLogger().debug("Client {} disconnect returned from gateway", getTargetId(), null);
        }
        catch (InterruptedException x)
        {
            Thread.currentThread().interrupt();
            throw newIOException(x);
        }
    }

    protected void asyncDeliver(RHTTPResponse response)
    {
        try
        {
            DeliverExchange exchange = new DeliverExchange(response);
            exchange.setMethod(HttpMethods.POST);
            exchange.setAddress(gatewayAddress);
            exchange.setURI(gatewayPath + "/" + urlEncode(getTargetId()) + "/deliver");
            exchange.setRequestContent(new ByteArrayBuffer(response.getFrameBytes()));
            httpClient.send(exchange);
            getLogger().debug("Client {} deliver sent to gateway, response {}", getTargetId(), response);
        }
        catch (IOException x)
        {
            getLogger().debug("Could not send exchange", x);
            throw new RuntimeException(x);
        }
    }

    protected class HandshakeExchange extends ContentExchange
    {
        protected HandshakeExchange()
        {
            super(true);
        }
        
        @Override
        protected void onConnectionFailed(Throwable x)
        {
            getLogger().warn(x.toString());
            getLogger().debug(x);
        }
    }

    protected class ConnectExchange extends ContentExchange
    {
        private final ByteArrayOutputStream content = new ByteArrayOutputStream();

        protected ConnectExchange()
        {
            super(true);
        }

        @Override
        protected void onResponseContent(Buffer buffer) throws IOException
        {
            buffer.writeTo(content);
        }

        @Override
        protected void onResponseComplete()
        {
            int responseStatus = getResponseStatus();
            if (responseStatus == 200)
            {
                try
                {
                    connectComplete(content.toByteArray());
                }
                catch (IOException x)
                {
                    onException(x);
                }
            }
            else if (responseStatus == 401)
            {
                notifyConnectRequired();
            }
            else
            {
                notifyConnectException();
            }
        }

        @Override
        protected void onException(Throwable x)
        {
            getLogger().debug(x);
            if (x instanceof EofException || x instanceof EOFException)
            {
                notifyConnectClosed();
            }
            else
            {
                notifyConnectException();
            }
        }
        
        @Override
        protected void onConnectionFailed(Throwable x)
        {
            getLogger().debug(x);
        }
    }

    protected class DisconnectExchange extends ContentExchange
    {
        protected DisconnectExchange()
        {
            super(true);
        }
    }

    protected class DeliverExchange extends ContentExchange
    {
        private final RHTTPResponse response;

        protected DeliverExchange(RHTTPResponse response)
        {
            super(true);
            this.response = response;
        }

        @Override
        protected void onResponseComplete() throws IOException
        {
            int responseStatus = getResponseStatus();
            if (responseStatus == 401)
            {
                notifyConnectRequired();
            }
            else if (responseStatus != 200)
            {
                notifyDeliverException(response);
            }
        }

        @Override
        protected void onException(Throwable x)
        {
            getLogger().debug(x);
            notifyDeliverException(response);
        }

        @Override
        protected void onConnectionFailed(Throwable x)
        {
            getLogger().debug(x);
        }
    }
}
