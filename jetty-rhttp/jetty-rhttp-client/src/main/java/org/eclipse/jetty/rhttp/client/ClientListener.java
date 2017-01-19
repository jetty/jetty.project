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

/**
 * A listener for network-related events happening on the gateway client.
 *
 * @version $Revision$ $Date$
 */
public interface ClientListener
{
    /**
     * Called when the client detects that the server requested a new connect.
     */
    public void connectRequired();

    /**
     * Called when the client detects that the connection has been closed by the server.
     */
    public void connectClosed();

    /**
     * Called when the client detects a generic exception while trying to connect to the server.
     */
    public void connectException();

    /**
     * Called when the client detects a generic exception while tryint to deliver to the server.
     * @param response the Response object that should have been sent to the server
     */
    public void deliverException(RHTTPResponse response);

    public static class Adapter implements ClientListener
    {
        public void connectRequired()
        {
        }

        public void connectClosed()
        {
        }

        public void connectException()
        {
        }

        public void deliverException(RHTTPResponse response)
        {
        }
    }
}
