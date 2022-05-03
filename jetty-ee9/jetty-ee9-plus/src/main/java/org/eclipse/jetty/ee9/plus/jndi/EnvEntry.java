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

package org.eclipse.jetty.plus.jndi;

import javax.naming.NamingException;

/**
 * EnvEntry
 */
public class EnvEntry extends NamingEntry
{
    private boolean overrideWebXml;

    public EnvEntry(Object scope, String jndiName, Object objToBind, boolean overrideWebXml)
        throws NamingException
    {
        super(scope, jndiName);
        save(objToBind);
        this.overrideWebXml = overrideWebXml;
    }

    public EnvEntry(String jndiName, Object objToBind, boolean overrideWebXml)
        throws NamingException
    {
        super(jndiName);
        save(objToBind);
        this.overrideWebXml = overrideWebXml;
    }

    public EnvEntry(String jndiName, Object objToBind)
        throws NamingException
    {
        this(jndiName, objToBind, false);
    }

    public boolean isOverrideWebXml()
    {
        return this.overrideWebXml;
    }

    @Override
    protected String toStringMetaData()
    {
        return "OverrideWebXml=" + overrideWebXml;
    }
}
