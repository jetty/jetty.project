package org.eclipse.jetty.policy.loader;

/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */ 

//========================================================================
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at 
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses. 
// 
// This file adapted for use from Apache Harmony code by written and contributed 
// to that project by Alexey V. Varlamov under the ASL-2.0
//
// See CQ3380
//========================================================================

import java.io.IOException;
import java.io.Reader;
import java.io.StreamTokenizer;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import org.eclipse.jetty.policy.loader.DefaultPolicyLoader.GrantEntry;
import org.eclipse.jetty.policy.loader.DefaultPolicyLoader.KeystoreEntry;
import org.eclipse.jetty.policy.loader.DefaultPolicyLoader.PermissionEntry;
import org.eclipse.jetty.policy.loader.DefaultPolicyLoader.PrincipalEntry;


/**
 * This is a basic high-level tokenizer of policy files. It takes in a stream, analyzes data read from it and returns a
 * set of structured tokens. <br>
 * This implementation recognizes text files, consisting of clauses with the following syntax:
 * 
 * <pre>
 * 
 *     keystore &quot;some_keystore_url&quot;, &quot;keystore_type&quot;;
 * 
 * </pre>
 * 
 * <pre>
 * 
 *     grant [SignedBy &quot;signer_names&quot;] [, CodeBase &quot;URL&quot;]
 *      [, Principal [principal_class_name] &quot;principal_name&quot;]
 *      [, Principal [principal_class_name] &quot;principal_name&quot;] ... {
 *      permission permission_class_name [ &quot;target_name&quot; ] [, &quot;action&quot;] 
 *      [, SignedBy &quot;signer_names&quot;];
 *      permission ...
 *      };
 * 
 * </pre>
 * 
 * For semantical details of this format, see the {@link org.apache.harmony.security.DefaultPolicy default policy
 * description}. <br>
 * Keywords are case-insensitive in contrast to quoted string literals. Comma-separation rule is quite forgiving, most
 * commas may be just omitted. Whitespaces, line- and block comments are ignored. Symbol-level tokenization is delegated
 * to java.io.StreamTokenizer. <br>
 * <br>
 * This implementation is effectively thread-safe, as it has no field references to data being processed (that is,
 * passes all the data as method parameters).
 * 
 */
public class PolicyFileScanner
{

    /**
     * Specific exception class to signal policy file syntax error.
     */
    public static class InvalidFormatException
        extends Exception
    {

        /**
         * @serial
         */
        private static final long serialVersionUID = 5789786270390222184L;

        /**
         * Constructor with detailed message parameter.
         */
        public InvalidFormatException( String arg0 )
        {
            super( arg0 );
        }
    }

    /**
     * Configures passed tokenizer accordingly to supported syntax.
     */
    protected StreamTokenizer configure( StreamTokenizer st )
    {
        st.slashSlashComments( true );
        st.slashStarComments( true );
        st.wordChars( '_', '_' );
        st.wordChars( '$', '$' );
        return st;
    }

    /**
     * Performs the main parsing loop. Starts with creating and configuring a StreamTokenizer instance; then tries to
     * recognize <i>keystore </i> or <i>grant </i> keyword. When found, invokes read method corresponding to the clause
     * and collects result to the passed collection.
     * 
     * @param r policy stream reader
     * @param grantEntries a collection to accumulate parsed GrantEntries
     * @param keystoreEntries a collection to accumulate parsed KeystoreEntries
     * @throws IOException if stream reading failed
     * @throws InvalidFormatException if unexpected or unknown token encountered
     */
    public void scanStream( Reader r, Collection<GrantEntry> grantEntries, List<KeystoreEntry> keystoreEntries )
        throws IOException, InvalidFormatException
    {
        StreamTokenizer st = configure( new StreamTokenizer( r ) );
        // main parsing loop
        parsing: while ( true )
        {
            switch ( st.nextToken() )
            {
                case StreamTokenizer.TT_EOF: // we've done the job
                    break parsing;

                case StreamTokenizer.TT_WORD:
                    if ( Util.equalsIgnoreCase( "keystore", st.sval ) ) { //$NON-NLS-1$
                        keystoreEntries.add( readKeystoreEntry( st ) );
                    }
                    else if ( Util.equalsIgnoreCase( "grant", st.sval ) ) { //$NON-NLS-1$
                        grantEntries.add( readGrantEntry( st ) );
                    }
                    else
                    {
                        handleUnexpectedToken( st, "Expected entries are : \"grant\" or \"keystore\"" ); //$NON-NLS-1$

                    }
                    break;

                case ';': // just delimiter of entries
                    break;

                default:
                    handleUnexpectedToken( st );
                    break;
            }
        }
    }

    /**
     * Tries to read <i>keystore </i> clause fields. The expected syntax is
     * 
     * <pre>
     * 
     *     &quot;some_keystore_url&quot;[, &quot;keystore_type&quot;];
     * 
     * </pre>
     * 
     * @return successfully parsed KeystoreEntry
     * @throws IOException if stream reading failed
     * @throws InvalidFormatException if unexpected or unknown token encountered
     */
    protected KeystoreEntry readKeystoreEntry( StreamTokenizer st )
        throws IOException, InvalidFormatException
    {
        KeystoreEntry ke = new KeystoreEntry();
        if ( st.nextToken() == '"' )
        {
            ke.url = st.sval;
            if ( ( st.nextToken() == '"' ) || ( ( st.ttype == ',' ) && ( st.nextToken() == '"' ) ) )
            {
                ke.type = st.sval;
            }
            else
            { // handle token in the main loop
                st.pushBack();
            }
        }
        else
        {
            handleUnexpectedToken( st, "Expected syntax is : keystore \"url\"[, \"type\"]" ); //$NON-NLS-1$

        }
        return ke;
    }

    /**
     * Tries to read <i>grant </i> clause. <br>
     * First, it reads <i>codebase </i>, <i>signedby </i>, <i>principal </i> entries till the '{' (opening curly brace)
     * symbol. Then it calls readPermissionEntries() method to read the permissions of this clause. <br>
     * Principal entries (if any) are read by invoking readPrincipalEntry() method, obtained PrincipalEntries are
     * accumulated. <br>
     * The expected syntax is
     * 
     * <pre>
     * 
     *     [ [codebase &quot;url&quot;] | [signedby &quot;name1,...,nameN&quot;] | 
     *          principal ...] ]* { ... }
     * 
     * </pre>
     * 
     * @return successfully parsed GrantEntry
     * @throws IOException if stream reading failed
     * @throws InvalidFormatException if unexpected or unknown token encountered
     */
    protected GrantEntry readGrantEntry( StreamTokenizer st )
        throws IOException, InvalidFormatException
    {
        GrantEntry ge = new GrantEntry();
        parsing: while ( true )
        {
            switch ( st.nextToken() )
            {

                case StreamTokenizer.TT_WORD:
                    if ( Util.equalsIgnoreCase( "signedby", st.sval ) ) { //$NON-NLS-1$
                        if ( st.nextToken() == '"' )
                        {
                            ge.signers = st.sval;
                        }
                        else
                        {
                            handleUnexpectedToken( st, "Expected syntax is : signedby \"name1,...,nameN\"" ); //$NON-NLS-1$
                        }
                    }
                    else if ( Util.equalsIgnoreCase( "codebase", st.sval ) ) { //$NON-NLS-1$
                        if ( st.nextToken() == '"' )
                        {
                            ge.codebase = st.sval;
                        }
                        else
                        {
                            handleUnexpectedToken( st, "Expected syntax is : codebase \"url\"" ); //$NON-NLS-1$
                        }
                    }
                    else if ( Util.equalsIgnoreCase( "principal", st.sval ) ) { //$NON-NLS-1$
                        ge.addPrincipal( readPrincipalEntry( st ) );
                    }
                    else
                    {
                        handleUnexpectedToken( st );
                    }
                    break;

                case ',': // just delimiter of entries
                    break;

                case '{':
                    ge.permissions = readPermissionEntries( st );
                    break parsing;

                default: // handle token in the main loop
                    st.pushBack();
                    break parsing;
            }
        }

        return ge;
    }

    /**
     * Tries to read <i>Principal </i> entry fields. The expected syntax is
     * 
     * <pre>
     * 
     *     [ principal_class_name ] &quot;principal_name&quot;
     * 
     * </pre>
     * 
     * Both class and name may be wildcards, wildcard names should not surrounded by quotes.
     * 
     * @return successfully parsed PrincipalEntry
     * @throws IOException if stream reading failed
     * @throws InvalidFormatException if unexpected or unknown token encountered
     */
    protected PrincipalEntry readPrincipalEntry( StreamTokenizer st )
        throws IOException, InvalidFormatException
    {
        PrincipalEntry pe = new PrincipalEntry();
        if ( st.nextToken() == StreamTokenizer.TT_WORD )
        {
            pe.klass = st.sval;
            st.nextToken();
        }
        else if ( st.ttype == '*' )
        {
            pe.klass = PrincipalEntry.WILDCARD;
            st.nextToken();
        }
        if ( st.ttype == '"' )
        {
            pe.name = st.sval;
        }
        else if ( st.ttype == '*' )
        {
            pe.name = PrincipalEntry.WILDCARD;
        }
        else
        {
            handleUnexpectedToken( st, "Expected syntax is : principal [class_name] \"principal_name\"" ); //$NON-NLS-1$
        }
        return pe;
    }

    /**
     * Tries to read a list of <i>permission </i> entries. The expected syntax is
     * 
     * <pre>
     * 
     *     permission permission_class_name
     *          [ &quot;target_name&quot; ] [, &quot;action_list&quot;]
     *          [, signedby &quot;name1,name2,...&quot;];
     * 
     * </pre>
     * 
     * List is terminated by '}' (closing curly brace) symbol.
     * 
     * @return collection of successfully parsed PermissionEntries
     * @throws IOException if stream reading failed
     * @throws InvalidFormatException if unexpected or unknown token encountered
     */
    protected Collection<PermissionEntry> readPermissionEntries( StreamTokenizer st )
        throws IOException, InvalidFormatException
    {
        Collection<PermissionEntry> permissions = new HashSet<PermissionEntry>();
        parsing: while ( true )
        {
            switch ( st.nextToken() )
            {

                case StreamTokenizer.TT_WORD:
                    if ( Util.equalsIgnoreCase( "permission", st.sval ) ) { //$NON-NLS-1$
                        PermissionEntry pe = new PermissionEntry();
                        if ( st.nextToken() == StreamTokenizer.TT_WORD )
                        {
                            pe.klass = st.sval;
                            if ( st.nextToken() == '"' )
                            {
                                pe.name = st.sval;
                                st.nextToken();
                            }
                            if ( st.ttype == ',' )
                            {
                                st.nextToken();
                            }
                            if ( st.ttype == '"' )
                            {
                                pe.actions = st.sval;
                                if ( st.nextToken() == ',' )
                                {
                                    st.nextToken();
                                }
                            }
                            if ( st.ttype == StreamTokenizer.TT_WORD && Util.equalsIgnoreCase( "signedby", st.sval ) ) { //$NON-NLS-1$
                                if ( st.nextToken() == '"' )
                                {
                                    pe.signers = st.sval;
                                }
                                else
                                {
                                    handleUnexpectedToken( st );
                                }
                            }
                            else
                            { // handle token in the next iteration
                                st.pushBack();
                            }
                            permissions.add( pe );
                            continue parsing;
                        }
                    }
                    handleUnexpectedToken(
                                           st,
                                           "Expected syntax is : permission permission_class_name [\"target_name\"] [, \"action_list\"] [, signedby \"name1,...,nameN\"]" ); //$NON-NLS-1$
                    break;

                case ';': // just delimiter of entries
                    break;

                case '}': // end of list
                    break parsing;

                default: // invalid token
                    handleUnexpectedToken( st );
                    break;
            }
        }

        return permissions;
    }

    /**
     * Formats a detailed description of tokenizer status: current token, current line number, etc.
     */
    protected String composeStatus( StreamTokenizer st )
    {
        return st.toString();
    }

    /**
     * Throws InvalidFormatException with detailed diagnostics.
     * 
     * @param st a tokenizer holding the erroneous token
     * @param message a user-friendly comment, probably explaining expected syntax. Should not be <code>null</code>- use
     *            the overloaded single-parameter method instead.
     */
    protected final void handleUnexpectedToken( StreamTokenizer st, String message )
        throws InvalidFormatException
    {
        throw new InvalidFormatException( "Unexpected token encountered: " + composeStatus( st ) + ". " + message );
    }

    /**
     * Throws InvalidFormatException with error status: which token is unexpected on which line.
     * 
     * @param st a tokenizer holding the erroneous token
     */
    protected final void handleUnexpectedToken( StreamTokenizer st )
        throws InvalidFormatException
    {
        throw new InvalidFormatException( "Unexpected token encountered: " + composeStatus( st ) );
    }


    private static class Util
    {
        public static String toUpperCase( String s )
        {
            int len = s.length();
            StringBuilder buffer = new StringBuilder( len );
            for ( int i = 0; i < len; i++ )
            {
                char c = s.charAt( i );
                if ( 'a' <= c && c <= 'z' )
                {
                    buffer.append( (char) ( c - ( 'a' - 'A' ) ) );
                }
                else
                {
                    buffer.append( c );
                }
            }
            return buffer.toString();
        }

        public static boolean equalsIgnoreCase( String s1, String s2 )
        {
            s1 = toUpperCase( s1 );
            s2 = toUpperCase( s2 );
            return s1.equals( s2 );
        }
    }

}
