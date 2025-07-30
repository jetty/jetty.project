//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
 * This is an object that will be linked into a webapp's java:comp/env namespace as if it
 * was an &lt;env-entry&gt; from web.xml.
 *
 * The Servlet Specification mandates that only String, Integer etc can be bound as an
 * &lt;env-entry&gt;, however Jetty is more lenient and allows a POJO.
 */
public class EnvEntry extends NamingEntry
{
    private boolean overrideWebXml;

    public EnvEntry(Object scope, String jndiName, Object objToBind, boolean overrideWebXml)
        throws NamingException
    {
        super(scope, jndiName, objToBind);
        this.overrideWebXml = overrideWebXml;
    }

    public EnvEntry(String jndiName, Object objToBind, boolean overrideWebXml)
        throws NamingException
    {
        super(null, jndiName, objToBind);
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
