//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
 * <p>ProcessorUtils provides access to runtime info about processors, that may be
 * overridden by system properties or environment variables.</p>
 * <p>This can be useful in virtualized environments where the runtime may miss
 * report the available resources.</p>
 */
public class ProcessorUtils
{
    public static final String AVAILABLE_PROCESSORS = "JETTY_AVAILABLE_PROCESSORS";
    private static int __availableProcessors = init();

    static int init()
    {
        String processors = System.getProperty(AVAILABLE_PROCESSORS, System.getenv(AVAILABLE_PROCESSORS));
        if (processors != null)
        {
            try
            {
                return Integer.parseInt(processors);
            }
            catch (NumberFormatException ignored)
            {
            }
        }
        return Runtime.getRuntime().availableProcessors();
    }

    /**
     * Returns the number of available processors, from System Property "JETTY_AVAILABLE_PROCESSORS",
     * or if not set then from environment variable "JETTY_AVAILABLE_PROCESSORS" or if not set then
     * from {@link Runtime#availableProcessors()}.
     *
     * @return the number of processors
     */
    public static int availableProcessors()
    {
        return __availableProcessors;
    }

    public static void setAvailableProcessors(int processors)
    {
        if (processors < 1)
            throw new IllegalArgumentException("Invalid number of processors: " + processors);
        __availableProcessors = processors;
    }
}
