//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.File;
import java.io.IOException;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/**
 * This goal runs the jetty quickstart feature on an unassembled webapp in order to generate
 * a comprehensive web.xml that combines all information from annotations, webdefault.xml and all web-fragment.xml
 * files. By default, the web.xml is generated to the console output only. Use the <b>effectiveWebXml</b> parameter
 * to provide a file name into which to save the output.
 *
 * See <a href="https://www.eclipse.org/jetty/documentation/">https://www.eclipse.org/jetty/documentation</a> for more information on this and other jetty plugins.
 *
 * Runs jetty on the unassembled webapp to generate the effective web.xml
 */
@Mojo(name = "effective-web-xml", requiresDependencyResolution = ResolutionScope.TEST)
@Execute(phase = LifecyclePhase.TEST_COMPILE)
public class JettyEffectiveWebXml extends JettyRunMojo
{
    /**
     * The target directory
     */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    protected File target;

    /**
     * The name of the file to generate into
     */
    @Parameter
    protected File effectiveWebXml;

    protected boolean deleteOnExit = true;

    /**
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        super.execute();
    }

    @Override
    public void startJetty() throws MojoExecutionException
    {
        //Only do enough setup to be able to produce a quickstart-web.xml file 

        QueuedThreadPool tpool = null;

        try
        {
            printSystemProperties();

            //apply any config from a jetty.xml file first to our "fake" server instance
            //TODO probably not necessary
            applyJettyXml();

            ServerSupport.configureHandlers(server, null);
            ServerSupport.configureDefaultConfigurationClasses(server);

            //ensure config of the webapp based on settings in plugin
            configureWebApplication();

            //set the webapp up to do very little other than generate the quickstart-web.xml
            webApp.setCopyWebDir(false);
            webApp.setCopyWebInf(false);
            webApp.setGenerateQuickStart(true);

            //if the user didn't nominate a file to generate into, pick the name and
            //make sure that it is deleted on exit
            if (webApp.getQuickStartWebDescriptor() == null)
            {
                if (effectiveWebXml == null)
                {
                    deleteOnExit = true;
                    effectiveWebXml = new File(target, "effective-web.xml");
                    effectiveWebXml.deleteOnExit();
                }

                Resource descriptor = Resource.newResource(effectiveWebXml);

                if (!effectiveWebXml.getParentFile().exists())
                    effectiveWebXml.getParentFile().mkdirs();
                if (!effectiveWebXml.exists())
                    effectiveWebXml.createNewFile();

                webApp.setQuickStartWebDescriptor(descriptor);
            }

            ServerSupport.addWebApplication(server, webApp);

            //if our server has a thread pool associated we can do any annotation scanning multithreaded,
            //otherwise scanning will be single threaded
            tpool = server.getBean(QueuedThreadPool.class);
            if (tpool != null)
                tpool.start();
            else
                webApp.setAttribute(AnnotationConfiguration.MULTI_THREADED, Boolean.FALSE.toString());

            webApp.start(); //just enough to generate the quickstart
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Effective web.xml generation failed", e);
        }
        finally
        {
            try
            {
                webApp.stop();
            }
            catch (Exception ignored)
            {
            }

            try
            {
                if (tpool != null)
                    tpool.stop();
            }
            catch (Exception ignored)
            {
            }
        }

        if (deleteOnExit)
        {
            try
            {
                //just show the result in the log
                getLog().info(IO.toString(webApp.getQuickStartWebDescriptor().getInputStream()));
            }
            catch (IOException e)
            {
                throw new MojoExecutionException("Unable to output effective web.xml", e);
            }
        }
    }
}
