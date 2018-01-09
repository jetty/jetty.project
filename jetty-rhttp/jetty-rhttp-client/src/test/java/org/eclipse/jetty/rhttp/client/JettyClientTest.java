//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.client.Address;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.rhttp.client.JettyClient;
import org.eclipse.jetty.rhttp.client.RHTTPClient;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;


/**
 * @version $Revision$ $Date$
 */
public class JettyClientTest extends ClientTest
{
    {
        ((StdErrLog)Log.getLog()).setHideStacks(!Log.getLog().isDebugEnabled());
    }
    
    private HttpClient httpClient;

    protected RHTTPClient createClient(int port, String targetId) throws Exception
    {
        ((StdErrLog)Log.getLog()).setSource(true);
        httpClient = new HttpClient();
        httpClient.start();
        return new JettyClient(httpClient, new Address("localhost", port), "", targetId);
    }

    protected void destroyClient(RHTTPClient client) throws Exception
    {
        httpClient.stop();
    }
}
