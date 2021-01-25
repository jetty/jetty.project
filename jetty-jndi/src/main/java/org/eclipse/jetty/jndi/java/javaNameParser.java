//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.jndi.java;

import java.util.Properties;
import javax.naming.CompoundName;
import javax.naming.Name;
import javax.naming.NameParser;
import javax.naming.NamingException;

// This is the required name for JNDI
// @checkstyle-disable-check : TypeNameCheck

/**
 * javaNameParser
 */
public class javaNameParser implements NameParser
{

    static Properties syntax = new Properties();

    static
    {
        syntax.put("jndi.syntax.direction", "left_to_right");
        syntax.put("jndi.syntax.separator", "/");
        syntax.put("jndi.syntax.ignorecase", "false");
    }

    /**
     * Parse a name into its components.
     *
     * @param name The non-null string name to parse.
     * @return A non-null parsed form of the name using the naming convention
     * of this parser.
     * @throws NamingException If a naming exception was encountered.
     */
    @Override
    public Name parse(String name) throws NamingException
    {
        return new CompoundName(name, syntax);
    }
}
