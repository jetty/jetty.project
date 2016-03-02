//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.plus.jndi;

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
        _link=link;
    }

    public Link (String jndiName, String link) throws NamingException
    {
        super(jndiName);
        save(link);
        _link=link;
    }

    public void bindToENC(String localName) throws NamingException
    {
        throw new UnsupportedOperationException("Method not supported for Link objects");
    }
    
    public String getLink()
    {
        return _link;
    }
}
