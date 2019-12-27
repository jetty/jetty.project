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

package org.eclipse.jetty.websocket.javax.tests;

import java.util.Map;

public class UpgradeUtils
{
    public static String generateUpgradeRequest(CharSequence requestPath, Map<String, String> headers)
    {
        StringBuilder upgradeRequest = new StringBuilder();
        upgradeRequest.append("GET ");
        upgradeRequest.append(requestPath == null ? "/" : requestPath);
        upgradeRequest.append(" HTTP/1.1\r\n");
        headers.entrySet().forEach(e ->
            upgradeRequest.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n"));
        upgradeRequest.append("\r\n");
        return upgradeRequest.toString();
    }
}
