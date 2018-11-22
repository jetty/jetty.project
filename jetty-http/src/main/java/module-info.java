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

import org.eclipse.jetty.http.Http1FieldPreEncoder;
import org.eclipse.jetty.http.HttpFieldPreEncoder;

module org.eclipse.jetty.http
{
    exports org.eclipse.jetty.http;
    exports org.eclipse.jetty.http.pathmap;

    requires org.eclipse.jetty.util;
    requires org.eclipse.jetty.io;
    requires static javax.servlet.api;

    uses HttpFieldPreEncoder;

    provides HttpFieldPreEncoder with Http1FieldPreEncoder;
}
