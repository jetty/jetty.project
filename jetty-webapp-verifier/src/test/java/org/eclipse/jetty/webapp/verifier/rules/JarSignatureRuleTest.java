// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.webapp.verifier.rules;

import org.eclipse.jetty.webapp.verifier.AbstractTestWebappVerifier;
import org.eclipse.jetty.webapp.verifier.MavenTestingUtils;
import org.eclipse.jetty.webapp.verifier.WebappVerifier;
import org.eclipse.jetty.webapp.verifier.rules.JarSignatureRule;

/**
 * Tests against {@link JarSignatureRule}
 */
public class JarSignatureRuleTest extends AbstractTestWebappVerifier
{
    public void testSimpleVerify() throws Exception
    {
        JarSignatureRule signed = new JarSignatureRule();
        
        // Create Webapp Specific Verifier from Verifier Suite
        WebappVerifier verifier = new WebappVerifier(MavenTestingUtils.toTargetFile("test-classes/webapps/signed-jar-test-webapp.war").toURI());
        verifier.addRule( signed );
        verifier.setWorkDir( getTestWorkDir() );

        // Run the verification.
        verifier.visitAll();
        
        assertTrue( verifier.getViolations().size() == 0 );
    }
}
