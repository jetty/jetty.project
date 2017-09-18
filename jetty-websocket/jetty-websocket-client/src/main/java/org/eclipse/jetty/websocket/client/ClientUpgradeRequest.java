//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.websocket.common.UpgradeRequestAdapter;

/**
 * Allowing a generate from a UpgradeRequest
 */
public final class ClientUpgradeRequest extends UpgradeRequestAdapter
{
    public ClientUpgradeRequest()
    {
        super();
    }

    @Override
    public void setRequestURI(URI uri)
    {
        super.setRequestURI(uri);

        // parse parameter map
        Map<String, List<String>> pmap = new HashMap<>();

        String query = uri.getQuery();

        if (StringUtil.isNotBlank(query))
        {
            MultiMap<String> params = new MultiMap<>();
            UrlEncoded.decodeTo(uri.getQuery(),params,StandardCharsets.UTF_8);

            for (String key : params.keySet())
            {
                List<String> values = params.getValues(key);
                if (values == null)
                {
                    pmap.put(key,new ArrayList<>());
                }
                else
                {
                    // break link to original
                    List<String> copy = new ArrayList<>();
                    copy.addAll(values);
                    pmap.put(key,copy);
                }
            }

            super.setParameterMap(pmap);
        }
    }
}
