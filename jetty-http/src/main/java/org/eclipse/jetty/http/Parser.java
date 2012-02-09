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

package org.eclipse.jetty.http;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Abstract interface for a connection Parser for use by Jetty.
 */
public interface Parser
{
    void reset();

    boolean isComplete();

    
    /* ------------------------------------------------------------ */
    /**
     * @param buffer
     * @return True if parsed to the next unit
     * @throws IOException
     */
    boolean parseNext(ByteBuffer buffer) throws IOException;

    boolean onEOF()throws IOException;
    
    boolean isIdle();
    
    boolean isPersistent();
    
    void setPersistent(boolean persistent);

}
