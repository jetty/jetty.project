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

package org.eclipse.jetty.start.config;

import java.util.Collections;
import java.util.Set;

import org.eclipse.jetty.start.Props;
import org.eclipse.jetty.start.RawArgs;
import org.eclipse.jetty.start.StartIni;

/**
 * A Configuration Source
 */
public interface ConfigSource
{
    /**
     * The identifier for this source.
     * <p>
     * Used in end-user display of the source.
     *
     * @return the configuration source identifier.
     */
    public String getId();

    /**
     * The weight of this source, used for proper ordering of the config source search order.
     * <p>
     * Recommended Weights:
     * <pre>
     *           -1 = the command line
     *            0 = the ${jetty.base} source
     *       [1..n] = include-jetty-dir entries from command line
     *     [n+1..n] = include-jetty-dir entries from start.ini (or start.d/*.ini)
     *      9999999 = the ${jetty.home} source
     * </pre>
     *
     * @return the weight of the config source. (lower value is more important)
     */
    public int getWeight();

    /**
     * The list of Arguments for this ConfigSource
     *
     * @return the list of Arguments for this ConfigSource
     */
    public RawArgs getArgs();

    /**
     * The properties for this ConfigSource
     *
     * @return the properties for this ConfigSource
     */
    public Props getProps();

    /**
     * Return the value of the specified property.
     *
     * @param key the key to lookup
     * @return the value of the property, or null if not found
     */
    public String getProperty(String key);

    public default Set<StartIni> getStartInis()
    {
        return Collections.emptySet();
    }
}
