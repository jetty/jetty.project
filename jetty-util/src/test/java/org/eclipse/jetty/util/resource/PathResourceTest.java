//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.resource;

import java.io.File;
import java.io.IOException;
import java.net.URI;

import org.junit.Ignore;

@Ignore // Ignore as PathResource not utilized in 9.2 and fixed in 9.3
public class PathResourceTest extends AbstractFSResourceTest
{
    @Override
    public Resource newResource(URI uri) throws IOException
    {
        return new PathResource(uri);
    }

    @Override
    public Resource newResource(File file) throws IOException
    {
        return new PathResource(file);
    }
}
