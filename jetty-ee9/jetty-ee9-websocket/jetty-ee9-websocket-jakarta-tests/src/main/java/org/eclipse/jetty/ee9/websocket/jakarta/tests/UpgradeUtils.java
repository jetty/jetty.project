//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.jakarta.tests;

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
