//========================================================================
//Copyright 2012 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at 
//http://www.apache.org/licenses/LICENSE-2.0
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
//========================================================================

package org.eclipse.jetty.util.preventers;

/**
 * LoginConfigurationLeakPreventer
 *
 * The javax.security.auth.login.Configuration class keeps a static reference to the 
 * thread context classloader. We prevent a webapp context classloader being used for
 * that by invoking the classloading here.
 * 
 * Inspired by Tomcat JreMemoryLeakPrevention
 */
public class LoginConfigurationLeakPreventer extends AbstractLeakPreventer
{
    /* ------------------------------------------------------------ */
    /** 
     * @see org.eclipse.jetty.util.preventers.AbstractLeakPreventer#prevent(java.lang.ClassLoader)
     */
    @Override
    public void prevent(ClassLoader loader)
    {
        try
        {
            Class.forName("javax.security.auth.login.Configuration", true, loader);
        }
        catch (ClassNotFoundException e)
        {
            LOG.warn(e);
        }
    }

}
