//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.spring;

import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;


/* ------------------------------------------------------------ */
/** Run Jetty from Spring configuration.
 * @see <a href="http://svn.codehaus.org/jetty/jetty/trunk/jetty-spring/src/main/config/etc/jetty-spring.xml">jetty-spring.xml</a>
 */
public class Main
{
    public static void main(String[] args) throws Exception
    {
        Resource config = Resource.newResource(args.length == 1?args[0]:"src/main/config/etc/jetty-spring.xml");
        XmlConfiguration.main(new String[]{config.getFile().getAbsolutePath()});
        
    }
}
