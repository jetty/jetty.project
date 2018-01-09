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

/**
 * <p>Implementations of this class listen for requests arriving from the gateway server
 * and notified by {@link RHTTPClient}.</p>
 *
 * @version $Revision$ $Date$
 */
public interface RHTTPListener
{
    /**
     * Callback method called by {@link RHTTPClient} to inform that the gateway server
     * sent a request to the gateway client.
     * @param request the request sent by the gateway server.
     * @throws Exception allowed to be thrown by implementations
     */
    public void onRequest(RHTTPRequest request) throws Exception;
}
