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

import java.security.Security;

/**
 * SecurityProviderLeakPreventer
 *
 * Some security providers, such as sun.security.pkcs11.SunPKCS11 start a deamon thread,
 * which will use the thread context classloader. Load them here to ensure the classloader
 * is not a webapp classloader.
 *
 * Inspired by Tomcat JreMemoryLeakPrevention
 */
public class SecurityProviderLeakPreventer extends AbstractLeakPreventer
{
    /* ------------------------------------------------------------ */
    /** 
     * @see org.eclipse.jetty.util.preventers.AbstractLeakPreventer#prevent(java.lang.ClassLoader)
     */
    @Override
    public void prevent(ClassLoader loader)
    {
        Security.getProviders();
    }

}
