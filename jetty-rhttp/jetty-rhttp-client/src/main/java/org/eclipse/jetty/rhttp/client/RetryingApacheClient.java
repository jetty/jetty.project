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

import org.apache.http.client.HttpClient;

/**
 * @version $Revision$ $Date$
 */
public class RetryingApacheClient extends ApacheClient
{
    public RetryingApacheClient(HttpClient httpClient, String gatewayURI, String targetId)
    {
        super(httpClient, gatewayURI, targetId);
        addClientListener(new RetryClientListener());
    }

    @Override
    protected void syncHandshake() throws IOException
    {
        while (true)
        {
            try
            {
                super.syncHandshake();
                break;
            }
            catch (IOException x)
            {
                getLogger().debug("Handshake failed, backing off and retrying");
                try
                {
                    Thread.sleep(1000);
                }
                catch (InterruptedException xx)
                {
                    throw (IOException)new IOException().initCause(xx);
                }
            }
        }
    }

    private class RetryClientListener implements ClientListener
    {
        public void connectRequired()
        {
            getLogger().debug("Connect requested by server");
            try
            {
                connect();
            }
            catch (IOException x)
            {
                // The connect() method is retried, so if it fails, it's a hard failure
                getLogger().debug("Connect failed after server required connect, giving up");
            }
        }

        public void connectClosed()
        {
            connectException();
        }

        public void connectException()
        {
            getLogger().debug("Connect failed, backing off and retrying");
            try
            {
                Thread.sleep(1000);
                asyncConnect();
            }
            catch (InterruptedException x)
            {
                // Ignore and stop retrying
                Thread.currentThread().interrupt();
            }
        }

        public void deliverException(RHTTPResponse response)
        {
            getLogger().debug("Deliver failed, backing off and retrying");
            try
            {
                Thread.sleep(1000);
                asyncDeliver(response);
            }
            catch (InterruptedException x)
            {
                // Ignore and stop retrying
                Thread.currentThread().interrupt();
            }
        }
    }
}
