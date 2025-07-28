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
 * Any type of POJO to be bound that can be retrieved later and linked into
 * a webapp's java:comp/env namespace.
 */
public class Resource extends NamingEntry
{
    public Resource(Object scope, String jndiName, Object objToBind)
        throws NamingException
    {
        super(scope, jndiName, objToBind);
    }

    public Resource(String jndiName, Object objToBind)
        throws NamingException
    {
        super(null, jndiName, objToBind);
    }
}
