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

package org.eclipse.jetty.start.config;

import java.io.IOException;
import java.nio.file.Path;

/**
 * ${jetty.base} specific ConfigSource
 */
public class JettyBaseConfigSource extends DirConfigSource
{
    // Standard weight for ${jetty.base}, so that it comes after command line, and before everything else 
    private final static int WEIGHT = 0;
    
    public JettyBaseConfigSource(Path dir) throws IOException
    {
        super("${jetty.base}",dir,WEIGHT,true);
    }
}
