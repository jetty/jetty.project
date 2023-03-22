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

package org.eclipse.jetty.ee9.plus.jndi;

import javax.naming.NamingException;

/**
 * Resource
 */
public class Resource extends NamingEntry
{
    public Resource(Object scope, String jndiName, Object objToBind)
        throws NamingException
    {
        super(scope, jndiName);
        save(objToBind);
    }

    public Resource(String jndiName, Object objToBind)
        throws NamingException
    {
        super(jndiName);
        save(objToBind);
    }
}
