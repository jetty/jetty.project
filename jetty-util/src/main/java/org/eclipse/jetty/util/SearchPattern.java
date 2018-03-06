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

package org.eclipse.jetty.util;

public class SearchPattern
{
    static final int alphabetSize = 256;
    int[] table;
    
    private String pattern;

    
    /**
     * @param pattern  The pattern to search for.
     * @return A Pattern instance for the search pattern
     */
    static SearchPattern compile(String pattern)
    {
        SearchPattern sp = new SearchPattern();
        sp.pattern = pattern;
        
        
        /*
        Build up pre-processed table for this pattern.
        
            function preprocess(pattern):
                T ← new table of 256 integers
                for i from 0 to 256 exclusive
                    T[i] ← length(pattern)
                for i from 0 to length(pattern) - 1 exclusive
                    T[pattern[i]] ← length(pattern) - 1 - i
                return T
        */
        
        sp.table = new int[alphabetSize];
        for(int i = 0; i<sp.table.length; ++i){
            sp.table[i] = pattern.length();
        }
        
        /*
         for i from 0 to length(pattern) - 1 exclusive
             T[pattern[i]] ← length(pattern) - 1 - i
        */
        for(int i = 0; i<pattern.length(); ++i){
            int index = pattern;
            sp.table[index] = pattern.length()-1-i;
        }
        
        return sp;
    }
    
    
    /**
     * Search for a complete match of the pattern within the data
     * @param data The data in which to search for. The data may be arbitrary binary data, 
     * but the pattern will always be {@link StandardCharsets#US_ASCII} encoded.
     * @param offset The offset within the data to start the search
     * @param length The length of the data to search
     * @return The index within the data array at which the first instance of the pattern or -1 if not found
     */
    public int match(byte[] data, int offset, int length)
    {
        return -1;
    }
    
    /**
     * Search for a partial match of the pattern at the end of the data.
     * @param data The data in which to search for. The data may be arbitrary binary data, 
     * but the pattern will always be {@link StandardCharsets#US_ASCII} encoded.
     * @param offset The offset within the data to start the search
     * @param length The length of the data to search
     * @return the length of the partial pattern matched or -1 for no match.
     */
    public int endsPartiallyWith(byte data, int offset, int length)
    {
        return -1;
    }
    
    /**
     * 
     * Search for a partial match of the pattern at the start of the data.
     * @param data The data in which to search for. The data may be arbitrary binary data, 
     * but the pattern will always be {@link StandardCharsets#US_ASCII} encoded.
     * @param offset The offset within the data to start the search
     * @param length The length of the data to search
     * @param matched The length of the partial pattern already matched
     * @return the length of the partial pattern matched or -1 for no match.
     */
    public int startsPartialyWith(byte[] data, int offset, int length, int matched)
    {
        return -1;    
    }
}
