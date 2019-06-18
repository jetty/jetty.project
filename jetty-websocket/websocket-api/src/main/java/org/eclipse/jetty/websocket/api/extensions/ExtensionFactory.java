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

package org.eclipse.jetty.websocket.api.extensions;

import java.util.Map;
import java.util.Set;

public interface ExtensionFactory extends Iterable<Class<? extends Extension>>
{
    Map<String, Class<? extends Extension>> getAvailableExtensions();

    Class<? extends Extension> getExtension(String name);

    Set<String> getExtensionNames();

    boolean isAvailable(String name);

    Extension newInstance(ExtensionConfig config);

    void register(String name, Class<? extends Extension> extension);

    void unregister(String name);
}
