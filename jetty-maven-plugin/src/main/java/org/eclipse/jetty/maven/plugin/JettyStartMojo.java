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

package org.eclipse.jetty.maven.plugin;


/**
 *  <p>
 *  This goal is similar to the jetty:run goal, EXCEPT that it is designed to be bound to an execution inside your pom, rather
 *  than being run from the command line. 
 *  </p>
 *  <p>
 *  When using it, be careful to ensure that you bind it to a phase in which all necessary generated files and classes for the webapp
 *  will have been created. If you run it from the command line, then also ensure that all necessary generated files and classes for
 *  the webapp already exist.
 *  </p>
 * 
 * @goal start
 * @requiresDependencyResolution test
 * @execute phase="validate"
 * @description Runs jetty directly from a maven project from a binding to an execution in your pom
 */
public class JettyStartMojo extends JettyRunMojo
{
}
