// ========================================================================
// Copyright (c) 2010 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.io.nio;

import java.io.IOException;

import org.eclipse.jetty.io.AsyncEndPoint;

public interface AsyncConnection 
{
    void onInputShutdown() throws IOException;
    
    AsyncEndPoint getAsyncEndPoint();
    
    /* ------------------------------------------------------------ */
    /**
     * Handle the connection.
     * @return The Connection to use for the next handling of the connection. 
     * This allows protocol upgrades and support for CONNECT.
     * @throws IOException
     */
    AsyncConnection handle() throws IOException;
    
    boolean isReadInterested();

    /**
     * Called when the connection is closed
     */
    void onClose();
    
    /**
     * Called when the connection idle timeout expires
     * @param idleForMs TODO
     */
    void onIdleExpired(long idleForMs);
    

    /**
     * @return the timestamp at which the connection was created
     */
    long getTimeStamp();

    boolean isIdle();
}
