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

package org.eclipse.jetty.io;

import java.io.IOException;

/* ------------------------------------------------------------ */
/** Abstract Connection used by Jetty Connectors.
 * <p>
 * Jetty will call the handle method of a connection when there is work 
 * to be done on the connection.  For blocking connections, this is soon 
 * as the connection is open and handle will keep being called until the 
 * connection is closed.   For non-blocking connections, handle will only
 * be called if there are bytes to be read or the connection becomes writable
 * after being write blocked.
 * 
 * @see org.eclipse.jetty.io.nio.SelectorManager
 */
public interface Connection
{
    /* ------------------------------------------------------------ */
    /**
     * Handle the connection.
     * @return The Connection to use for the next handling of the connection. 
     * This allows protocol upgrades and support for CONNECT.
     * @throws IOException
     */
    Connection handle() throws IOException;
    
    /**
     * @return the timestamp at which the connection was created
     */
    long getTimeStamp();

    boolean isIdle();
    
    boolean isSuspended();
    
    /**
     * Called when the connection is closed
     */
    void closed();
    
    /**
     * Called when the connection idle timeout expires
     */
    void idleExpired();
}
