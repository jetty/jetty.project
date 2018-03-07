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

package org.eclipse.jetty.util;

/**
 * ProcessorUtils return the default value for processor number from {@link Runtime}
 * but in a virtual environment you can override it using env var <code>JETTY_AVAILABLE_PROCESSORS</code>
 */
public class ProcessorUtils
{
    private static int AVAILABLE_PROCESSORS = Runtime.getRuntime().availableProcessors();

    static
    {
        String avlProcEnv = System.getenv( "JETTY_AVAILABLE_PROCESSORS" );
        if (avlProcEnv != null)
        {
            try
            {
                AVAILABLE_PROCESSORS = Integer.parseInt( avlProcEnv );
            }
            catch ( NumberFormatException e )
            {
                // ignore
            }
        }
    }

    /**
     *
     * @return the number of processors
     */
    public static int availableProcessors()
    {
        return AVAILABLE_PROCESSORS;
    }
}
