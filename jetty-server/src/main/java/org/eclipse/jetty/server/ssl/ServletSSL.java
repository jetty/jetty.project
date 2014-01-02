//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.ssl;

/* --------------------------------------------------------------------- */
/**
 * Jetty Servlet SSL support utilities.
 * <p>
 * A collection of utilities required to support the SSL requirements of the Servlet 2.2 and 2.3
 * specs.
 * 
 * <p>
 * Used by the SSL listener classes.
 * 
 * 
 */
public class ServletSSL
{
    /* ------------------------------------------------------------ */
    /**
     * Given the name of a TLS/SSL cipher suite, return an int representing it effective stream
     * cipher key strength. i.e. How much entropy material is in the key material being fed into the
     * encryption routines.
     * 
     * <p>
     * This is based on the information on effective key lengths in RFC 2246 - The TLS Protocol
     * Version 1.0, Appendix C. CipherSuite definitions:
     * 
     * <pre>
     *                         Effective 
     *     Cipher       Type    Key Bits 
     * 		       	       
     *     NULL       * Stream     0     
     *     IDEA_CBC     Block    128     
     *     RC2_CBC_40 * Block     40     
     *     RC4_40     * Stream    40     
     *     RC4_128      Stream   128     
     *     DES40_CBC  * Block     40     
     *     DES_CBC      Block     56     
     *     3DES_EDE_CBC Block    168     
     * </pre>
     * 
     * @param cipherSuite String name of the TLS cipher suite.
     * @return int indicating the effective key entropy bit-length.
     */
    public static int deduceKeyLength(String cipherSuite)
    {
        // Roughly ordered from most common to least common.
        if (cipherSuite == null)
            return 0;
        else if (cipherSuite.indexOf("WITH_AES_256_") >= 0)
            return 256;
        else if (cipherSuite.indexOf("WITH_RC4_128_") >= 0)
            return 128;
        else if (cipherSuite.indexOf("WITH_AES_128_") >= 0)
            return 128;
        else if (cipherSuite.indexOf("WITH_RC4_40_") >= 0)
            return 40;
        else if (cipherSuite.indexOf("WITH_3DES_EDE_CBC_") >= 0)
            return 168;
        else if (cipherSuite.indexOf("WITH_IDEA_CBC_") >= 0)
            return 128;
        else if (cipherSuite.indexOf("WITH_RC2_CBC_40_") >= 0)
            return 40;
        else if (cipherSuite.indexOf("WITH_DES40_CBC_") >= 0)
            return 40;
        else if (cipherSuite.indexOf("WITH_DES_CBC_") >= 0)
            return 56;
        else
            return 0;
    }
}
