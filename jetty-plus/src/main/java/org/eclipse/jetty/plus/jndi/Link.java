// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.plus.jndi;

import javax.naming.NamingException;



public class Link extends NamingEntry
{

    public Link(Object scope, String jndiName, Object object) throws NamingException
    {
        //jndiName is the name according to the web.xml
        //objectToBind is the name in the environment
        super(scope, jndiName, object);
    }

    public Link (String jndiName, Object object) throws NamingException
    {
        super(null, jndiName, object);
    }


    public void bindToENC(String localName) throws NamingException
    {
        throw new UnsupportedOperationException("Method not supported for Link objects");
    }
    


}
