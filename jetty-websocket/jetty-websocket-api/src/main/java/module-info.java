//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.websocket.api.extensions.Extension;

module org.eclipse.jetty.websocket.jetty.api
{
    exports org.eclipse.jetty.websocket.api;
    exports org.eclipse.jetty.websocket.api.annotations;
    exports org.eclipse.jetty.websocket.api.extensions;
    exports org.eclipse.jetty.websocket.api.util;

    uses Extension;
}
