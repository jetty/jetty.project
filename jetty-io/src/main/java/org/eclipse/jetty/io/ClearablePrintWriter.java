// ========================================================================
// Copyright (c) 2012-2012 Mort Bay Consulting Pty. Ltd.
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

import java.io.PrintWriter;
import java.io.Writer;


/* ------------------------------------------------------------ */
/**
 * ClearablePrintWriter
 *
 * Small class to make clearError method callable publically. Used for recycling the print writer on the response object.
 */
public abstract class ClearablePrintWriter extends PrintWriter
{
    
    /* ------------------------------------------------------------ */
    public ClearablePrintWriter(Writer writer, boolean autoFlush)
    {
        super(writer);
    }
    
    /* ------------------------------------------------------------ */
    public ClearablePrintWriter(Writer writer)
    {
        super(writer);
    }
    
    
    /* ------------------------------------------------------------ */
    public void clearError ()
    {
        super.clearError();
    }
}
