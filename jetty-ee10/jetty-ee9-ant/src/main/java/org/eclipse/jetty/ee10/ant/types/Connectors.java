//
//  ========================================================================
//  Copyright (c) 1995-2012 Sabre Holdings and others.
//  ------------------------------------------------------------------------
//  This program and the accompanying materials are made available under the
//  terms of the Eclipse Public License v. 2.0 which is available at
//  https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
//  which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
//  SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
//  ========================================================================
//

package org.eclipse.jetty.ee9.ant.types;

import java.util.ArrayList;
import java.util.List;

/**
 * Specifies a jetty configuration <code>&lt;connectors/&gt;</code> element for Ant build file.
 */
public class Connectors
{
    private List<Connector> connectors = new ArrayList<Connector>();
    private List<Connector> defaultConnectors = new ArrayList<Connector>();

    /**
     * Default constructor.
     */
    public Connectors()
    {
        this(8080, 30000);
    }

    /**
     * Constructor.
     *
     * @param port The port that the default connector will listen on
     * @param maxIdleTime The maximum idle time for the default connector
     */
    public Connectors(int port, int maxIdleTime)
    {
        defaultConnectors.add(new Connector(port, maxIdleTime));
    }

    /**
     * Adds a connector to the list of connectors to deploy.
     *
     * @param connector A connector to add to the list
     */
    public void add(Connector connector)
    {
        connectors.add(connector);
    }

    /**
     * Returns the list of known connectors to deploy.
     *
     * @return The list of known connectors
     */
    public List<Connector> getConnectors()
    {
        return connectors;
    }

    /**
     * Gets the default list of connectors to deploy when no connectors
     * were explicitly added to the list.
     *
     * @return The list of default connectors
     */
    public List<Connector> getDefaultConnectors()
    {
        return defaultConnectors;
    }
}
