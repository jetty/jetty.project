// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

public interface AsyncEndPoint extends EndPoint
{
    /* ------------------------------------------------------------ */
    /**
     * Dispatch the endpoint to a thread to attend to it.
     * 
     */
    public void dispatch();
    
    /**
     * @return true if this endpoint can accept a dispatch. False if the 
     * endpoint cannot accept a dispatched (eg is suspended or already dispatched)
     */
    public boolean isReadyForDispatch();
    
}
