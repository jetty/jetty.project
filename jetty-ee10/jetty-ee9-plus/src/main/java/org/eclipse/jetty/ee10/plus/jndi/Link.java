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

package org.eclipse.jetty.ee9.plus.jndi;

import javax.naming.NamingException;

public class Link extends NamingEntry
{
    private final String _link;

    public Link(Object scope, String jndiName, String link) throws NamingException
    {
        //jndiName is the name according to the web.xml
        //objectToBind is the name in the environment
        super(scope, jndiName);
        save(link);
        _link = link;
    }

    public Link(String jndiName, String link) throws NamingException
    {
        super(jndiName);
        save(link);
        _link = link;
    }

    @Override
    public void bindToENC(String localName) throws NamingException
    {
        throw new UnsupportedOperationException("Method not supported for Link objects");
    }

    public String getLink()
    {
        return _link;
    }

    @Override
    protected String toStringMetaData()
    {
        return _link;
    }
}
