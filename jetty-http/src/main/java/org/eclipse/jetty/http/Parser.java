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

/**
 * Abstract interface for a connection Parser for use by Jetty.
 */
public interface Parser
{
    void returnBuffers();
    void reset();

    boolean isComplete();

    /**
     * @return An indication of progress, typically the number of bytes filled plus the events parsed: -1 means EOF read, 0 no progress, >0 progress
     * @throws IOException
     */
    int parseAvailable() throws IOException;

    boolean isMoreInBuffer() throws IOException;

    boolean isIdle();

}
