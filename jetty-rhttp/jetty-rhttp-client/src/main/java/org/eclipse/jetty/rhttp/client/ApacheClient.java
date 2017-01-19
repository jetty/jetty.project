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

import java.io.IOException;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NoHttpResponseException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.util.EntityUtils;

/**
 * Implementation of {@link RHTTPClient} that uses Apache's HttpClient.
 *
 * @version $Revision$ $Date$
 */
public class ApacheClient extends AbstractClient
{
    private final HttpClient httpClient;
    private final String gatewayPath;

    public ApacheClient(HttpClient httpClient, String gatewayPath, String targetId)
    {
        super(targetId);
        this.httpClient = httpClient;
        this.gatewayPath = gatewayPath;
    }

    public String getHost()
    {
        return ((HttpHost)httpClient.getParams().getParameter("http.default-host")).getHostName();
    }

    public int getPort()
    {
        return ((HttpHost)httpClient.getParams().getParameter("http.default-host")).getPort();
    }
    
    public String getPath()
    {
        return gatewayPath;
    }

    protected void syncHandshake() throws IOException
    {
        HttpPost handshake = new HttpPost(gatewayPath + "/" + urlEncode(getTargetId()) + "/handshake");
        HttpResponse response = httpClient.execute(handshake);
        int statusCode = response.getStatusLine().getStatusCode();
        HttpEntity entity = response.getEntity();
        if (entity != null)
            entity.consumeContent();
        if (statusCode != HttpStatus.SC_OK)
            throw new IOException("Handshake failed");
        getLogger().debug("Client {} handshake returned from gateway", getTargetId(), null);
    }

    protected void asyncConnect()
    {
        new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    HttpPost connect = new HttpPost(gatewayPath + "/" + urlEncode(getTargetId()) + "/connect");
                    getLogger().debug("Client {} connect sent to gateway", getTargetId(), null);
                    HttpResponse response = httpClient.execute(connect);
                    int statusCode = response.getStatusLine().getStatusCode();
                    HttpEntity entity = response.getEntity();
                    byte[] responseContent = EntityUtils.toByteArray(entity);
                    if (statusCode == HttpStatus.SC_OK)
                        connectComplete(responseContent);
                    else if (statusCode == HttpStatus.SC_UNAUTHORIZED)
                        notifyConnectRequired();
                    else
                        notifyConnectException();
                }
                catch (NoHttpResponseException x)
                {
                    notifyConnectClosed();
                }
                catch (IOException x)
                {
                    getLogger().debug("", x);
                    notifyConnectException();
                }
            }
        }.start();
    }

    protected void syncDisconnect() throws IOException
    {
        HttpPost disconnect = new HttpPost(gatewayPath + "/" + urlEncode(getTargetId()) + "/disconnect");
        HttpResponse response = httpClient.execute(disconnect);
        int statusCode = response.getStatusLine().getStatusCode();
        HttpEntity entity = response.getEntity();
        if (entity != null)
            entity.consumeContent();
        if (statusCode != HttpStatus.SC_OK)
            throw new IOException("Disconnect failed");
        getLogger().debug("Client {} disconnect returned from gateway", getTargetId(), null);
    }

    protected void asyncDeliver(final RHTTPResponse response)
    {
        new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    HttpPost deliver = new HttpPost(gatewayPath + "/" + urlEncode(getTargetId()) + "/deliver");
                    deliver.setEntity(new ByteArrayEntity(response.getFrameBytes()));
                    getLogger().debug("Client {} deliver sent to gateway, response {}", getTargetId(), response);
                    HttpResponse httpResponse = httpClient.execute(deliver);
                    int statusCode = httpResponse.getStatusLine().getStatusCode();
                    HttpEntity entity = httpResponse.getEntity();
                    if (entity != null)
                        entity.consumeContent();
                    if (statusCode == HttpStatus.SC_UNAUTHORIZED)
                        notifyConnectRequired();
                    else if (statusCode != HttpStatus.SC_OK)
                        notifyDeliverException(response);
                }
                catch (IOException x)
                {
                    getLogger().debug("", x);
                    notifyDeliverException(response);
                }
            }
        }.start();
    }
}
