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

package org.eclipse.jetty.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

import org.eclipse.jetty.http.HttpSchemes;
import javax.servlet.HttpConstraintElement;
import javax.servlet.HttpMethodConstraintElement;
import javax.servlet.ServletSecurityElement;
import javax.servlet.annotation.ServletSecurity.EmptyRoleSemantic;
import javax.servlet.annotation.ServletSecurity.TransportGuarantee;

import org.eclipse.jetty.http.PathMap;
import org.eclipse.jetty.server.AbstractHttpConnection;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.StringMap;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.security.Constraint;

/* ------------------------------------------------------------ */
/**
 * Handler to enforce SecurityConstraints. This implementation is servlet spec
 * 3.0 compliant and precomputes the constraint combinations for runtime
 * efficiency.
 *
 */
public class ConstraintSecurityHandler extends SecurityHandler implements ConstraintAware
{
    private static final String OMISSION_SUFFIX = ".omission";
    
    private final List<ConstraintMapping> _constraintMappings= new CopyOnWriteArrayList<ConstraintMapping>();
    private final Set<String> _roles = new CopyOnWriteArraySet<String>();
    private final PathMap _constraintMap = new PathMap();
    private boolean _strict = true;
    
    
    /* ------------------------------------------------------------ */
    /**
     * @return
     */
    public static Constraint createConstraint()
    {
        return new Constraint();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param constraint
     * @return
     */
    public static Constraint createConstraint(Constraint constraint)
    {
        try
        {
            return (Constraint)constraint.clone();
        }
        catch (CloneNotSupportedException e)
        {
            throw new IllegalStateException (e);
        }
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Create a security constraint
     * 
     * @param name
     * @param authenticate
     * @param roles
     * @param dataConstraint
     * @return
     */
    public static Constraint createConstraint (String name, boolean authenticate, String[] roles, int dataConstraint)
    {
        Constraint constraint = createConstraint();
        if (name != null)
            constraint.setName(name);
        constraint.setAuthenticate(authenticate);
        constraint.setRoles(roles);
        constraint.setDataConstraint(dataConstraint);
        return constraint;
    }
    

    /* ------------------------------------------------------------ */
    /**
     * @param name
     * @param element
     * @return
     */
    public static Constraint createConstraint (String name, HttpConstraintElement element)
    {
        return createConstraint(name, element.getRolesAllowed(), element.getEmptyRoleSemantic(), element.getTransportGuarantee());     
    }


    /* ------------------------------------------------------------ */
    /**
     * @param name
     * @param rolesAllowed
     * @param permitOrDeny
     * @param transport
     * @return
     */
    public static Constraint createConstraint (String name, String[] rolesAllowed, EmptyRoleSemantic permitOrDeny, TransportGuarantee transport)
    {
        Constraint constraint = createConstraint();
        
        if (rolesAllowed == null || rolesAllowed.length==0)
        {           
            if (permitOrDeny.equals(EmptyRoleSemantic.DENY))
            {
                //Equivalent to <auth-constraint> with no roles
                constraint.setName(name+"-Deny");
                constraint.setAuthenticate(true);
            }
            else
            {
                //Equivalent to no <auth-constraint>
                constraint.setName(name+"-Permit");
                constraint.setAuthenticate(false);
            }
        }
        else
        {
            //Equivalent to <auth-constraint> with list of <security-role-name>s
            constraint.setAuthenticate(true);
            constraint.setRoles(rolesAllowed);
            constraint.setName(name+"-RolesAllowed");           
        } 

        //Equivalent to //<user-data-constraint><transport-guarantee>CONFIDENTIAL</transport-guarantee></user-data-constraint>
        constraint.setDataConstraint((transport.equals(TransportGuarantee.CONFIDENTIAL)?Constraint.DC_CONFIDENTIAL:Constraint.DC_NONE));
        return constraint; 
    }
    
    

    /* ------------------------------------------------------------ */
    /**
     * @param pathSpec
     * @param constraintMappings
     * @return
     */
    public static List<ConstraintMapping> getConstraintMappingsForPath(String pathSpec, List<ConstraintMapping> constraintMappings)
    {
        if (pathSpec == null || "".equals(pathSpec.trim()) || constraintMappings == null || constraintMappings.size() == 0)
            return Collections.emptyList();
        
        List<ConstraintMapping> mappings = new ArrayList<ConstraintMapping>();
        for (ConstraintMapping mapping:constraintMappings)
        {
            if (pathSpec.equals(mapping.getPathSpec()))
            {
               mappings.add(mapping);
            }
        }
        return mappings;
    }
    
    
    /* ------------------------------------------------------------ */
    /** Take out of the constraint mappings those that match the 
     * given path.
     * 
     * @param pathSpec
     * @param constraintMappings a new list minus the matching constraints
     * @return
     */
    public static List<ConstraintMapping> removeConstraintMappingsForPath(String pathSpec, List<ConstraintMapping> constraintMappings)
    {
        if (pathSpec == null || "".equals(pathSpec.trim()) || constraintMappings == null || constraintMappings.size() == 0)
            return Collections.emptyList();
        
        List<ConstraintMapping> mappings = new ArrayList<ConstraintMapping>();
        for (ConstraintMapping mapping:constraintMappings)
        {
            //Remove the matching mappings by only copying in non-matching mappings
            if (!pathSpec.equals(mapping.getPathSpec()))
            {
               mappings.add(mapping);
            }
        }
        return mappings;
    }
    
    
    
    /* ------------------------------------------------------------ */
    /** Generate Constraints and ContraintMappings for the given url pattern and ServletSecurityElement
     * 
     * @param name
     * @param pathSpec
     * @param securityElement
     * @return
     */
    public static List<ConstraintMapping> createConstraintsWithMappingsForPath (String name, String pathSpec, ServletSecurityElement securityElement)
    {
        List<ConstraintMapping> mappings = new ArrayList<ConstraintMapping>();

        //Create a constraint that will describe the default case (ie if not overridden by specific HttpMethodConstraints)
        Constraint constraint = ConstraintSecurityHandler.createConstraint(name, securityElement);

        //Create a mapping for the pathSpec for the default case
        ConstraintMapping defaultMapping = new ConstraintMapping();
        defaultMapping.setPathSpec(pathSpec);
        defaultMapping.setConstraint(constraint);  
        mappings.add(defaultMapping);


        //See Spec 13.4.1.2 p127
        List<String> methodOmissions = new ArrayList<String>();
        
        //make constraint mappings for this url for each of the HttpMethodConstraintElements
        Collection<HttpMethodConstraintElement> methodConstraints = securityElement.getHttpMethodConstraints();
        if (methodConstraints != null)
        {
            for (HttpMethodConstraintElement methodConstraint:methodConstraints)
            {
                //Make a Constraint that captures the <auth-constraint> and <user-data-constraint> elements supplied for the HttpMethodConstraintElement
                Constraint mconstraint = ConstraintSecurityHandler.createConstraint(name, methodConstraint);
                ConstraintMapping mapping = new ConstraintMapping();
                mapping.setConstraint(mconstraint);
                mapping.setPathSpec(pathSpec);
                if (methodConstraint.getMethodName() != null)
                {
                    mapping.setMethod(methodConstraint.getMethodName());
                    //See spec 13.4.1.2 p127 - add an omission for every method name to the default constraint
                    methodOmissions.add(methodConstraint.getMethodName());
                }
                mappings.add(mapping);
            }
        }
        //See spec 13.4.1.2 p127 - add an omission for every method name to the default constraint
        if (methodOmissions.size() > 0)
            defaultMapping.setMethodOmissions(methodOmissions.toArray(new String[methodOmissions.size()]));

        return mappings;
    }
    
    
    /* ------------------------------------------------------------ */
    /** Get the strict mode.
     * @return true if the security handler is running in strict mode.
     */
    public boolean isStrict()
    {
        return _strict;
    }

    /* ------------------------------------------------------------ */
    /** Set the strict mode of the security handler.
     * <p>
     * When in strict mode (the default), the full servlet specification
     * will be implemented.
     * If not in strict mode, some additional flexibility in configuration
     * is allowed:<ul>
     * <li>All users do not need to have a role defined in the deployment descriptor
     * <li>The * role in a constraint applies to ANY role rather than all roles defined in
     * the deployment descriptor.
     * </ul>
     *
     * @param strict the strict to set
     * @see #setRoles(Set)
     * @see #setConstraintMappings(List, Set)
     */
    public void setStrict(boolean strict)
    {
        _strict = strict;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the constraintMappings.
     */
    public List<ConstraintMapping> getConstraintMappings()
    {
        return _constraintMappings;
    }

    /* ------------------------------------------------------------ */
    public Set<String> getRoles()
    {
        return _roles;
    }

    /* ------------------------------------------------------------ */
    /**
     * Process the constraints following the combining rules in Servlet 3.0 EA
     * spec section 13.7.1 Note that much of the logic is in the RoleInfo class.
     *
     * @param constraintMappings
     *            The constraintMappings to set, from which the set of known roles
     *            is determined.
     */
    public void setConstraintMappings(List<ConstraintMapping> constraintMappings)
    {
        setConstraintMappings(constraintMappings,null);
    }

    /**
     * Process the constraints following the combining rules in Servlet 3.0 EA
     * spec section 13.7.1 Note that much of the logic is in the RoleInfo class.
     *
     * @param constraintMappings
     *            The constraintMappings to set as array, from which the set of known roles
     *            is determined.  Needed to retain API compatibility for 7.x
     */
    public void setConstraintMappings( ConstraintMapping[] constraintMappings )
    {
        setConstraintMappings( Arrays.asList(constraintMappings), null);
    }

    /* ------------------------------------------------------------ */
    /**
     * Process the constraints following the combining rules in Servlet 3.0 EA
     * spec section 13.7.1 Note that much of the logic is in the RoleInfo class.
     *
     * @param constraintMappings
     *            The constraintMappings to set.
     * @param roles The known roles (or null to determine them from the mappings)
     */
    public void setConstraintMappings(List<ConstraintMapping> constraintMappings, Set<String> roles)
    {
        _constraintMappings.clear();
        _constraintMappings.addAll(constraintMappings);

        if (roles==null)
        {
            roles = new HashSet<String>();
            for (ConstraintMapping cm : constraintMappings)
            {
                String[] cmr = cm.getConstraint().getRoles();
                if (cmr!=null)
                {
                    for (String r : cmr)
                        if (!"*".equals(r))
                            roles.add(r);
                }
            }
        }
        setRoles(roles);
        
        if (isStarted())
        {
            for (ConstraintMapping mapping : _constraintMappings)
            {
                processConstraintMapping(mapping);
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the known roles.
     * This may be overridden by a subsequent call to {@link #setConstraintMappings(ConstraintMapping[])} or
     * {@link #setConstraintMappings(List, Set)}.
     * @see #setStrict(boolean)
     * @param roles The known roles (or null to determine them from the mappings)
     */
    public void setRoles(Set<String> roles)
    {
        _roles.clear();
        _roles.addAll(roles);
    }



    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.security.ConstraintAware#addConstraintMapping(org.eclipse.jetty.security.ConstraintMapping)
     */
    public void addConstraintMapping(ConstraintMapping mapping)
    {
        _constraintMappings.add(mapping);
        if (mapping.getConstraint()!=null && mapping.getConstraint().getRoles()!=null)
            for (String role :  mapping.getConstraint().getRoles())
                addRole(role);

        if (isStarted())
        {
            processConstraintMapping(mapping);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.security.ConstraintAware#addRole(java.lang.String)
     */
    public void addRole(String role)
    {
        boolean modified = _roles.add(role);
        if (isStarted() && modified && _strict)
        {
            // Add the new role to currently defined any role role infos
            for (Map<String,RoleInfo> map : (Collection<Map<String,RoleInfo>>)_constraintMap.values())
            {
                for (RoleInfo info : map.values())
                {
                    if (info.isAnyRole())
                        info.addRole(role);
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.security.SecurityHandler#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        _constraintMap.clear();
        if (_constraintMappings!=null)
        {
            for (ConstraintMapping mapping : _constraintMappings)
            {
                processConstraintMapping(mapping);
            }
        }
        super.doStart();
    }
    
    
    /* ------------------------------------------------------------ */
    @Override
    protected void doStop() throws Exception
    {
        _constraintMap.clear();
        _constraintMappings.clear();
        _roles.clear();
        super.doStop();
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * Create and combine the constraint with the existing processed
     * constraints.
     * 
     * @param mapping
     */
    protected void processConstraintMapping(ConstraintMapping mapping)
    {
        Map<String, RoleInfo> mappings = (Map<String, RoleInfo>)_constraintMap.get(mapping.getPathSpec());
        if (mappings == null)
        {
            mappings = new StringMap();
            _constraintMap.put(mapping.getPathSpec(),mappings);
        }
        RoleInfo allMethodsRoleInfo = mappings.get(null);
        if (allMethodsRoleInfo != null && allMethodsRoleInfo.isForbidden())
            return;
       
        if (mapping.getMethodOmissions() != null && mapping.getMethodOmissions().length > 0)
        {
           
            processConstraintMappingWithMethodOmissions(mapping, mappings);
            return;
        }

        String httpMethod = mapping.getMethod();       
        RoleInfo roleInfo = mappings.get(httpMethod);
        if (roleInfo == null)
        {
            roleInfo = new RoleInfo();
            mappings.put(httpMethod,roleInfo);
            if (allMethodsRoleInfo != null)
            {
                roleInfo.combine(allMethodsRoleInfo);
            }
        }
        if (roleInfo.isForbidden())
            return;

        //add in info from the constraint
        configureRoleInfo(roleInfo, mapping);
        
        if (roleInfo.isForbidden())
        {
            if (httpMethod == null)
            {
                mappings.clear();
                mappings.put(null,roleInfo);
            }
        }
        else
        {
            //combine with any entry that covers all methods
            if (httpMethod == null)
            {
                for (Map.Entry<String, RoleInfo> entry : mappings.entrySet())
                {
                    if (entry.getKey() != null)
                    {
                        RoleInfo specific = entry.getValue();
                        specific.combine(roleInfo);
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    /** Constraints that name method omissions are dealt with differently.
     * We create an entry in the mappings with key "method.omission". This entry
     * is only ever combined with other omissions for the same method to produce a
     * consolidated RoleInfo. Then, when we wish to find the relevant constraints for
     *  a given Request (in prepareConstraintInfo()), we consult 3 types of entries in 
     * the mappings: an entry that names the method of the Request specifically, an
     * entry that names constraints that apply to all methods, entries of the form
     * method.omission, where the method of the Request is not named in the omission.
     * @param mapping
     * @param mappings
     */
    protected void processConstraintMappingWithMethodOmissions (ConstraintMapping mapping, Map<String, RoleInfo> mappings)
    {
        String[] omissions = mapping.getMethodOmissions();

        for (String omission:omissions)
        {
            //for each method omission, see if there is already a RoleInfo for it in mappings
            RoleInfo ri = mappings.get(omission+OMISSION_SUFFIX);
            if (ri == null)
            {
                //if not, make one
                ri = new RoleInfo();
                mappings.put(omission+OMISSION_SUFFIX, ri);
            }

            //initialize RoleInfo or combine from ConstraintMapping
            configureRoleInfo(ri, mapping);
        }
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * Initialize or update the RoleInfo from the constraint
     * @param ri
     * @param mapping
     */
    protected void configureRoleInfo (RoleInfo ri, ConstraintMapping mapping)
    {
        Constraint constraint = mapping.getConstraint();
        boolean forbidden = constraint.isForbidden();
        ri.setForbidden(forbidden);
        
        //set up the data constraint (NOTE: must be done after setForbidden, as it nulls out the data constraint
        //which we need in order to do combining of omissions in prepareConstraintInfo
        UserDataConstraint userDataConstraint = UserDataConstraint.get(mapping.getConstraint().getDataConstraint());
        ri.setUserDataConstraint(userDataConstraint);
        

        //if forbidden, no point setting up roles
        if (!ri.isForbidden())
        {
            //add in the roles
            boolean checked = mapping.getConstraint().getAuthenticate();
            ri.setChecked(checked);
            if (ri.isChecked())
            {
                if (mapping.getConstraint().isAnyRole())
                {
                    if (_strict)
                    {
                        // * means "all defined roles"
                        for (String role : _roles)
                            ri.addRole(role);
                    }
                    else
                        // * means any role
                        ri.setAnyRole(true);
                }
                else
                {
                    String[] newRoles = mapping.getConstraint().getRoles();
                    for (String role : newRoles)
                    {
                        if (_strict &&!_roles.contains(role))
                            throw new IllegalArgumentException("Attempt to use undeclared role: " + role + ", known roles: " + _roles);
                        ri.addRole(role);
                    }
                }
            }
        }
    }
   
    
    /* ------------------------------------------------------------ */
    /** 
     * Find constraints that apply to the given path.
     * In order to do this, we consult 3 different types of information stored in the mappings for each path - each mapping
     * represents a merged set of user data constraints, roles etc -:
     * <ol>
     * <li>A mapping of an exact method name </li>
     * <li>A mapping will null key that matches every method name</li>
     * <li>Mappings with keys of the form "method.omission" that indicates it will match every method name EXCEPT that given</li>
     * </ol>
     * 
     * @see org.eclipse.jetty.security.SecurityHandler#prepareConstraintInfo(java.lang.String, org.eclipse.jetty.server.Request)
     */
    protected Object prepareConstraintInfo(String pathInContext, Request request)
    {
        Map<String, RoleInfo> mappings = (Map<String, RoleInfo>)_constraintMap.match(pathInContext);

        if (mappings != null)
        {
            String httpMethod = request.getMethod();
            RoleInfo roleInfo = mappings.get(httpMethod);
            if (roleInfo == null)
            {
                //No specific http-method names matched
                List<RoleInfo> applicableConstraints = new ArrayList<RoleInfo>();

                //Get info for constraint that matches all methods if it exists
                RoleInfo all = mappings.get(null);
                if (all != null)
                    applicableConstraints.add(all);
          
                
                //Get info for constraints that name method omissions where target method name is not omitted
                //(ie matches because target method is not omitted, hence considered covered by the constraint)
                for (Entry<String, RoleInfo> entry: mappings.entrySet())
                {
                    if (entry.getKey() != null && entry.getKey().contains(OMISSION_SUFFIX) && !(httpMethod+OMISSION_SUFFIX).equals(entry.getKey()))
                        applicableConstraints.add(entry.getValue());
                }
                
                if (applicableConstraints.size() == 1)
                    roleInfo = applicableConstraints.get(0);
                else
                {
                    roleInfo = new RoleInfo();
                    roleInfo.setUserDataConstraint(UserDataConstraint.None);
                    
                    for (RoleInfo r:applicableConstraints)
                        roleInfo.combine(r);
                }

            }
            return roleInfo;
        }
        return null;
    }
    
    
    /* ------------------------------------------------------------ */
    /** 
     * @see org.eclipse.jetty.security.SecurityHandler#checkUserDataPermissions(java.lang.String, org.eclipse.jetty.server.Request, org.eclipse.jetty.server.Response, java.lang.Object)
     */
    protected boolean checkUserDataPermissions(String pathInContext, Request request, Response response, Object constraintInfo) throws IOException
    {
        if (constraintInfo == null)
            return true;

        RoleInfo roleInfo = (RoleInfo)constraintInfo;
        if (roleInfo.isForbidden())
            return false;


        UserDataConstraint dataConstraint = roleInfo.getUserDataConstraint();
        if (dataConstraint == null || dataConstraint == UserDataConstraint.None)
        {
            return true;
        }
        AbstractHttpConnection connection = AbstractHttpConnection.getCurrentConnection();
        Connector connector = connection.getConnector();

        if (dataConstraint == UserDataConstraint.Integral)
        {
            if (connector.isIntegral(request))
                return true;
            if (connector.getIntegralPort() > 0)
            {
                String scheme=connector.getIntegralScheme();
                int port=connector.getIntegralPort();
                String url = (HttpSchemes.HTTPS.equalsIgnoreCase(scheme) && port==443)
                    ? "https://"+request.getServerName()+request.getRequestURI()
                    : scheme + "://" + request.getServerName() + ":" + port + request.getRequestURI();
                if (request.getQueryString() != null)
                    url += "?" + request.getQueryString();
                response.setContentLength(0);
                response.sendRedirect(url);
            }
            else
                response.sendError(Response.SC_FORBIDDEN,"!Integral");

            request.setHandled(true);
            return false;
        }
        else if (dataConstraint == UserDataConstraint.Confidential)
        {
            if (connector.isConfidential(request))
                return true;

            if (connector.getConfidentialPort() > 0)
            {
                String scheme=connector.getConfidentialScheme();
                int port=connector.getConfidentialPort();
                String url = (HttpSchemes.HTTPS.equalsIgnoreCase(scheme) && port==443)
                    ? "https://"+request.getServerName()+request.getRequestURI()
                    : scheme + "://" + request.getServerName() + ":" + port + request.getRequestURI();                    
                if (request.getQueryString() != null)
                    url += "?" + request.getQueryString();
                response.setContentLength(0);
                response.sendRedirect(url);
            }
            else
                response.sendError(Response.SC_FORBIDDEN,"!Confidential");

            request.setHandled(true);
            return false;
        }
        else
        {
            throw new IllegalArgumentException("Invalid dataConstraint value: " + dataConstraint);
        }

    }
    
    /* ------------------------------------------------------------ */
    /** 
     * @see org.eclipse.jetty.security.SecurityHandler#isAuthMandatory(org.eclipse.jetty.server.Request, org.eclipse.jetty.server.Response, java.lang.Object)
     */
    protected boolean isAuthMandatory(Request baseRequest, Response base_response, Object constraintInfo)
    {
        if (constraintInfo == null)
        {
            return false;
        }
        return ((RoleInfo)constraintInfo).isChecked();
    }
    
    
    /* ------------------------------------------------------------ */
    /** 
     * @see org.eclipse.jetty.security.SecurityHandler#checkWebResourcePermissions(java.lang.String, org.eclipse.jetty.server.Request, org.eclipse.jetty.server.Response, java.lang.Object, org.eclipse.jetty.server.UserIdentity)
     */
    @Override
    protected boolean checkWebResourcePermissions(String pathInContext, Request request, Response response, Object constraintInfo, UserIdentity userIdentity)
            throws IOException
    {
        if (constraintInfo == null)
        {
            return true;
        }
        RoleInfo roleInfo = (RoleInfo)constraintInfo;

        if (!roleInfo.isChecked())
        {
            return true;
        }

        if (roleInfo.isAnyRole() && request.getAuthType()!=null)
            return true;

        for (String role : roleInfo.getRoles())
        {
            if (userIdentity.isUserInRole(role, null))
                return true;
        }
        return false;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void dump(Appendable out,String indent) throws IOException
    {
        dumpThis(out);
        dump(out,indent,
                Collections.singleton(getLoginService()),
                Collections.singleton(getIdentityService()),
                Collections.singleton(getAuthenticator()),
                Collections.singleton(_roles),
                _constraintMap.entrySet(),
                getBeans(),
                TypeUtil.asList(getHandlers()));
    }

}
