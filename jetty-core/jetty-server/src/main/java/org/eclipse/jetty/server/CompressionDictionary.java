//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Base64.Encoder;

/**
 * This class implements the Compression Dictionary as described in RFC 9842.
 * 
 * @author arsenal
 */
public class CompressionDictionary
{
    public static enum Type
    {
        RAW("raw"),
        DCZ("dcz"),
        DCB("dcb");

        private String name;

        Type(String name)
        {
            this.name = name;
        }
        
        public String getType()
        {
            return this.name;
        }
    }

    private String match;
    private StringBuilder matchDestList;
    private String id;
    private Type type;
    private ByteBuffer dictionary;
    private String dictDigestBase64;
    private byte[] dictDigest;
    
    /**
     * Creates instance of Compression Directory
     * 
     * @param match {@code String} value that provides the URL Pattern to use for request matching
     * @param dictionary {@code ByteBuffer} content of compression directory
     * @throws URISyntaxException
     * @throws NoSuchAlgorithmException
     */
    public CompressionDictionary(String match, ByteBuffer dictionary, Type type) throws URISyntaxException, NoSuchAlgorithmException
    {
        // URL is used for percent-encoded version of math
        URI path = new URI(null, null, match, null);
        this.match = path.toASCIIString();
        this.matchDestList = new StringBuilder();
        this.dictDigest = calcDictDigest(dictionary);
        this.dictDigestBase64 = calcDictDigestBase64(this.dictDigest);
        this.dictionary = dictionary;
        this.type = type;
    }
    
    public synchronized byte[] getDictDigest()
    {
        return this.dictDigest;
    }

    public synchronized String getDictDigestBase64()
    {
        return this.dictDigestBase64;
    }
    
    /**
     * Get a compression dictionary data
     * 
     * @return compression dictionary {@code ByteBuffer}
     */
    public synchronized ByteBuffer getDictionary()
    {
        return this.dictionary;
    }
    
    /**
     * Add a match-dest value of a compression dictionary
     * 
     * @param matchDest {@code String} The "match-dest" value of the "Use-As-Dictionary" response header
     */
    public synchronized void addMatchDest(String matchDest)
    {
        if (matchDestList.length() == 0)
        {
            matchDestList.append('"' + matchDest + '"');
        }
        else
        {
            matchDestList.append(" \"" + matchDest + '"');
        }
    }

    public synchronized boolean setId(String id)
    {
        if (id.length() > 1024)
            throw new IllegalArgumentException();
        this.id = id;

        return true;
    }

    public synchronized void setType(Type type)
    {
        this.type = type;
    }
    
    /**
     * Get a match value that provides the URL Pattern to use for request matching
     * 
     * @return match {@code String} value
     */
    public synchronized String getMatch()
    {
        return this.match;
    }

    public synchronized String getMatchDest()
    {
        return this.matchDestList.toString();
    }

    public synchronized String getId()
    {
        return this.id;
    }

    public synchronized String getType()
    {
        return this.type.getType();
    }
    
    /**
     * Calculate SHA-256 digest of a compression dictionary in Base64 format
     * 
     * @param dict A dictionary to calculate digest for
     * @return {@code String} of dictionary digest 
     * @throws NoSuchAlgorithmException
     */
    private String calcDictDigestBase64(byte[] sha256Digest)
    {
        Encoder base64Enc = Base64.getEncoder();
        return base64Enc.encodeToString(sha256Digest);
    }
    
    /**
     * Calculate SHA-256 digest of a compression dictionary
     * 
     * @param dict A dictionary to calculate digest for
     * @return {@code byte[]} of dictionary digest 
     * @throws NoSuchAlgorithmException
     */
    private byte[] calcDictDigest(ByteBuffer dict) throws NoSuchAlgorithmException
    {
        return MessageDigest.getInstance("SHA-256").digest(dict.array());
    }
}
