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

package org.eclipse.jetty.http;

import static java.lang.Integer.MIN_VALUE;

/* ------------------------------------------------------------ */

/**
 * Implements a quoted comma separated list of quality values
 * in accordance with RFC7231.
 * Result is sorted primarily by given client quality order and
 * in case of tie by the given server configured preference order.
 * Values are returned sorted with the OWS and quality parameters
 * removed.
 *
 * @see "https://tools.ietf.org/html/rfc7231#section-5.3.4"
 */
public class QuotedEncodingQualityCSV extends QuotedQualityCSV
{
    /* ------------------------------------------------------------ */
    public QuotedEncodingQualityCSV(CompressedContentFormat[] preferredFormatOrder)
    {
        super((s) -> {
            for (int i=0;i<preferredFormatOrder.length;++i)
                if (preferredFormatOrder[i]._encoding.equals(s))
                    return preferredFormatOrder.length-i;

            if ("*".equals(s))
                return preferredFormatOrder.length;

            return MIN_VALUE;
        });
    }
}
