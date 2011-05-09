//========================================================================
//Copyright (c) Webtide LLC
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//
//The Apache License v2.0 is available at
//http://www.apache.org/licenses/LICENSE-2.0.txt
//
//You may elect to redistribute this code under either of these licenses.
//========================================================================

package org.eclipse.jetty.servlet;

public interface Mapping
{
    /**
     * Sets the entity name.
     *
     * @param name the new entity name
     */
    public void setEntityName(String name);

    /**
     * Gets the entity name.
     *
     * @return the entity name
     */
    public String getEntityName();
    
    /**
     * Sets the path specs.
     *
     * @param pathSpecs the new path specs
     */
    public void setPathSpecs(String[] pathSpecs);

    /**
     * Sets the path spec.
     *
     * @param pathSpec the new path spec
     */
    public void setPathSpec(String pathSpec);

    /**
     * Gets the path specs.
     *
     * @return the path specs
     */
    public String[] getPathSpecs();

    /**
     * Gets the context basis.
     *
     * @return the context basis
     */
    public String getContextBasis();

    /**
     * Sets the context basis.
     *
     * @param basis the new context basis
     */
    public void setContextBasis(String basis);
}
