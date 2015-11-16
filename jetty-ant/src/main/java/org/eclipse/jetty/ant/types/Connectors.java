//
//  ========================================================================
//  Copyright (c) 1995-2012 Sabre Holdings.
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


package org.eclipse.jetty.ant.types;

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
    public Connectors() {
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
