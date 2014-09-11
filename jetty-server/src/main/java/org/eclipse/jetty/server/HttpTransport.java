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

package org.eclipse.jetty.server;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.util.Callback;

public interface HttpTransport
{    
    void send(HttpGenerator.ResponseInfo info, ByteBuffer content, boolean lastContent, Callback callback);
    
    void push (MetaData.Request request);
    
    void completed();
    
    /**
     * Aborts this transport.
     * <p />
     * This method should terminate the transport in a way that
     * can indicate an abnormal response to the client, for example
     * by abruptly close the connection.
     * <p />
     * This method is called when an error response needs to be sent,
     * but the response is already committed, or when a write failure
     * is detected.
     *
     * @param failure the failure that caused the abort.
     */
    void abort(Throwable failure);
}
