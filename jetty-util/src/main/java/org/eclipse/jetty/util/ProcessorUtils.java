//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
