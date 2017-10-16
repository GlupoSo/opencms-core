/*
 * This library is part of OpenCms -
 * the Open Source Content Management System
 *
 * Copyright (c) Alkacon Software GmbH & Co. KG (http://www.alkacon.com)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * For further information about Alkacon Software GmbH & Co. KG, please see the
 * company website: http://www.alkacon.com
 *
 * For further information about OpenCms, please see the
 * project website: http://www.opencms.org
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package org.opencms.site;

import org.opencms.configuration.CmsConfigurationException;
import org.opencms.configuration.CmsSitesConfiguration;
import org.opencms.db.CmsPublishedResource;
import org.opencms.file.CmsObject;
import org.opencms.file.CmsProject;
import org.opencms.file.CmsPropertyDefinition;
import org.opencms.file.CmsResource;
import org.opencms.file.CmsResourceFilter;
import org.opencms.main.CmsContextInfo;
import org.opencms.main.CmsEvent;
import org.opencms.main.CmsException;
import org.opencms.main.CmsLog;
import org.opencms.main.CmsRuntimeException;
import org.opencms.main.I_CmsEventListener;
import org.opencms.main.OpenCms;
import org.opencms.security.CmsPermissionSet;
import org.opencms.security.CmsRole;
import org.opencms.util.CmsFileUtil;
import org.opencms.util.CmsStringUtil;
import org.opencms.util.CmsUUID;
import org.opencms.workplace.CmsWorkplace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.apache.commons.logging.Log;

import com.google.common.base.Optional;

/**
 * Manages all configured sites in OpenCms.<p>
 *
 * To obtain the configured site manager instance, use {@link OpenCms#getSiteManager()}.<p>
 *
 * @since 7.0.2
 */
public final class CmsSiteManagerImpl implements I_CmsEventListener {

    /** The root path prefix used to mark root paths not pointing into a site or /system/ or /shared/. */
    public static final String ROOT_PATH_PREFIX = "@";

    /** A placeholder for the title of the shared folder. */
    public static final String SHARED_FOLDER_TITLE = "%SHARED_FOLDER%";

    /** Path to config template. */
    public static final String WEB_SERVER_CONFIG_CONFIGTEMPLATE = "configtemplate";

    /**prefix for files. */
    public static final String WEB_SERVER_CONFIG_FILENAMEPREFIX = "filenameprefix";

    /**Path to write logs to. */
    public static final String WEB_SERVER_CONFIG_LOGGINGDIR = "loggingdir";

    /** Path to secure template. */
    public static final String WEB_SERVER_CONFIG_SECURETEMPLATE = "securetemplate";

    /** Path to target. */
    public static final String WEB_SERVER_CONFIG_TARGETPATH = "targetpath";

    /** Path of webserver script.*/
    public static final String WEB_SERVER_CONFIG_WEBSERVERSCRIPT = "webserverscript";

    /** The static log object for this class. */
    private static final Log LOG = CmsLog.getLog(CmsSiteManagerImpl.class);

    /** The path to the "/sites/" folder. */
    private static final String SITES_FOLDER = "/sites/";

    /** The length of the "/sites/" folder plus 1. */
    private static final int SITES_FOLDER_POS = SITES_FOLDER.length() + 1;

    /** A list of additional site roots, that is site roots that are not below the "/sites/" folder. */
    private List<String> m_additionalSiteRoots;

    /**
     * The list of aliases for the site that is configured at the moment,
     * needed for the sites added during configuration. */
    private List<CmsSiteMatcher> m_aliases;

    /**Map with webserver scripting parameter. */
    private Map<String, String> m_apacheConfig;

    /** The default site root. */
    private CmsSite m_defaultSite;

    /** The default URI. */
    private String m_defaultUri;

    /** Indicates if the configuration is finalized (frozen). */
    private boolean m_frozen;

    /** The shared folder name. */
    private String m_sharedFolder;

    /** Contains all configured site matchers in a list for direct access. */
    private List<CmsSiteMatcher> m_siteMatchers;

    /** Maps site matchers to sites. */
    private Map<CmsSiteMatcher, CmsSite> m_siteMatcherSites;

    /** Temporary store for site parameter values. */
    private SortedMap<String, String> m_siteParams;

    /** Maps site roots to sites. */
    private Map<String, CmsSite> m_siteRootSites;

    /**Map from CmsUUID to CmsSite.*/
    private Map<CmsUUID, CmsSite> m_siteUUIDs;

    /**Site which are only available for offline project. */
    private List<CmsSite> m_onlyOfflineSites;

    /**CmsObject.*/
    private CmsObject m_clone;

    /** The workplace site matchers. */
    private List<CmsSiteMatcher> m_workplaceMatchers;

    /** The workpace servers. */
    private List<String> m_workplaceServers;

    /**Is the publish listener already set? */
    private boolean m_isListenerSet;

    /**
     * Creates a new CmsSiteManager.<p>
     *
     */
    public CmsSiteManagerImpl() {

        m_siteMatcherSites = new HashMap<CmsSiteMatcher, CmsSite>();
        m_siteRootSites = new HashMap<String, CmsSite>();
        m_aliases = new ArrayList<CmsSiteMatcher>();
        m_siteParams = new TreeMap<String, String>();
        m_additionalSiteRoots = new ArrayList<String>();
        m_workplaceServers = new ArrayList<String>();
        m_workplaceMatchers = new ArrayList<CmsSiteMatcher>();

        if (CmsLog.INIT.isInfoEnabled()) {
            CmsLog.INIT.info(Messages.get().getBundle().key(Messages.INIT_START_SITE_CONFIG_0));
        }
    }

    /**
     * Adds an alias to the currently configured site.
     *
     * @param alias the URL of the alias server
     * @param offset the optional time offset for this alias
     */
    public void addAliasToConfigSite(String alias, String offset) {

        long timeOffset = 0;
        try {
            timeOffset = Long.parseLong(offset);
        } catch (Throwable e) {
            // ignore
        }
        CmsSiteMatcher siteMatcher = new CmsSiteMatcher(alias, timeOffset);
        m_aliases.add(siteMatcher);
    }

    /**
     * Adds a parameter to the currently configured site.<p>
     *
     * @param name the parameter name
     * @param value the parameter value
     */
    public void addParamToConfigSite(String name, String value) {

        m_siteParams.put(name, value);
    }

    /**
     * Adds the root path prefix to the given path.<p>
     *
     * @param rootPath the resource root path
     *
     * @return the prefixed path
     */
    public String addRootPathPrefix(String rootPath) {

        return ROOT_PATH_PREFIX + rootPath;
    }

    /**
     * Adds a site.<p>
     *
     * @param cms the CMS object
     * @param site the site to add
     *
     * @throws CmsException if something goes wrong
     */
    public void addSite(CmsObject cms, CmsSite site) throws CmsException {

        // check permissions
        if (OpenCms.getRunLevel() > OpenCms.RUNLEVEL_1_CORE_OBJECT) {
            // simple unit tests will have runlevel 1 and no CmsObject
            OpenCms.getRoleManager().checkRole(cms, CmsRole.DATABASE_MANAGER);
        }

        // un-freeze
        m_frozen = false;

        // set aliases and parameters, they will be used in the addSite method
        // this is necessary because of a digester workaround
        m_siteParams = site.getParameters();
        m_aliases = site.getAliases();

        String secureUrl = null;
        if (site.hasSecureServer()) {
            secureUrl = site.getSecureUrl();
        }

        // add the site
        addSite(
            site.getUrl(),
            site.getSiteRoot(),
            site.getTitle(),
            Float.toString(site.getPosition()),
            site.getErrorPage(),
            Boolean.toString(site.isWebserver()),
            secureUrl,
            Boolean.toString(site.isExclusiveUrl()),
            Boolean.toString(site.isExclusiveError()),
            Boolean.toString(site.usesPermanentRedirects()));

        // re-initialize, will freeze the state when finished
        initialize(cms);
        OpenCms.writeConfiguration(CmsSitesConfiguration.class);
    }

    /**
     * Adds a new CmsSite to the list of configured sites,
     * this is only allowed during configuration.<p>
     *
     * If this method is called after the configuration is finished,
     * a <code>RuntimeException</code> is thrown.<p>
     *
     * @param server the Server
     * @param uri the VFS path
     * @param title the display title for this site
     * @param position the display order for this site
     * @param errorPage the URI to use as error page for this site
     * @param webserver indicates whether to write the web server configuration for this site or not
     * @param secureServer a secure server, can be <code>null</code>
     * @param exclusive if set to <code>true</code>, secure resources will only be available using the configured secure url
     * @param error if exclusive, and set to <code>true</code> will generate a 404 error,
     *                             if set to <code>false</code> will redirect to secure URL
     * @param usePermanentRedirects if set to "true", permanent redirects should be used when redirecting to the secure URL
     *
     * @throws CmsConfigurationException if the site contains a server name, that is already assigned
     */
    public void addSite(
        String server,
        String uri,
        String title,
        String position,
        String errorPage,
        String webserver,
        String secureServer,
        String exclusive,
        String error,
        String usePermanentRedirects)
    throws CmsConfigurationException {

        if (m_frozen) {
            throw new CmsRuntimeException(Messages.get().container(Messages.ERR_CONFIG_FROZEN_0));
        }

        if (getSiteRoots().contains(uri)) {
            throw new CmsRuntimeException(Messages.get().container(Messages.ERR_SITE_ALREADY_CONFIGURED_1, uri));
        }

        if (CmsStringUtil.isEmptyOrWhitespaceOnly(server)) {
            throw new CmsRuntimeException(Messages.get().container(Messages.ERR_EMPTY_SERVER_URL_0));
        }

        // create a new site object
        CmsSiteMatcher matcher = new CmsSiteMatcher(server);
        CmsSite site = new CmsSite(uri, matcher);
        // set the title
        site.setTitle(title);
        // set the position
        if (CmsStringUtil.isNotEmptyOrWhitespaceOnly(position)) {
            float pos = Float.MAX_VALUE;
            try {
                pos = Float.parseFloat(position);
            } catch (Throwable e) {
                // m_position will have Float.MAX_VALUE, so this site will appear last
            }
            site.setPosition(pos);
        }
        // set the error page
        site.setErrorPage(errorPage);
        site.setWebserver(Boolean.valueOf(webserver).booleanValue());

        // add the server(s)
        addServer(matcher, site);
        if (CmsStringUtil.isNotEmpty(secureServer)) {
            matcher = new CmsSiteMatcher(secureServer);
            site.setSecureServer(matcher);
            site.setExclusiveUrl(Boolean.valueOf(exclusive).booleanValue());
            site.setExclusiveError(Boolean.valueOf(error).booleanValue());
            site.setUsePermanentRedirects(Boolean.valueOf(usePermanentRedirects).booleanValue());
            addServer(matcher, site);
        }

        // note that Digester first calls the addAliasToConfigSite method.
        // therefore, the aliases are already set
        site.setAliases(m_aliases);
        Iterator<CmsSiteMatcher> i = m_aliases.iterator();
        while (i.hasNext()) {
            matcher = i.next();
            addServer(matcher, site);
        }
        m_aliases = new ArrayList<CmsSiteMatcher>();
        site.setParameters(m_siteParams);
        m_siteParams = new TreeMap<String, String>();
        m_siteRootSites = new HashMap<String, CmsSite>(m_siteRootSites);
        m_siteRootSites.put(site.getSiteRoot(), site);
        if (CmsLog.INIT.isInfoEnabled()) {
            CmsLog.INIT.info(Messages.get().getBundle().key(Messages.INIT_SITE_ROOT_ADDED_1, site.toString()));
        }
    }

    /**
     * Adds a workplace server, this is only allowed during configuration.<p>
     *
     * @param workplaceServer the workplace server
     */
    public void addWorkplaceServer(String workplaceServer) {

        if (m_frozen) {
            throw new CmsRuntimeException(Messages.get().container(Messages.ERR_CONFIG_FROZEN_0));
        }
        if (!m_workplaceServers.contains(workplaceServer)) {
            m_workplaceServers.add(workplaceServer);
        }
    }

    /**
     * @see org.opencms.main.I_CmsEventListener#cmsEvent(org.opencms.main.CmsEvent)
     */
    public void cmsEvent(CmsEvent event) {

        try {
            CmsProject project = getOfflineProject();
            m_clone.getRequestContext().setCurrentProject(project);
            List<CmsPublishedResource> res = null;

            List<CmsPublishedResource> foundSites = new ArrayList<CmsPublishedResource>();

            res = m_clone.readPublishedResources(
                new CmsUUID((String)event.getData().get(I_CmsEventListener.KEY_PUBLISHID)));

            if (res != null) {
                for (CmsPublishedResource r : res) {
                    if (!foundSites.contains(r)) {
                        if (m_siteUUIDs.containsKey(r.getStructureId())) {
                            foundSites.add(r);
                        }
                    }
                }
            }

            Map<CmsSite, CmsSite> updateMap = new HashMap<CmsSite, CmsSite>();

            for (CmsPublishedResource r : foundSites) {
                if (m_clone.existsResource(r.getStructureId())) {
                    //Resource was not deleted
                    CmsResource siteRoot = m_clone.readResource(r.getStructureId());
                    if (!m_siteRootSites.containsKey(CmsFileUtil.removeTrailingSeparator(siteRoot.getRootPath()))
                        | m_onlyOfflineSites.contains(m_siteUUIDs.get(r.getStructureId()))) {
                        //Site was moved or site root was renamed.. or site was published the first time
                        CmsSite oldSite = m_siteUUIDs.get(siteRoot.getStructureId());
                        CmsSite newSite = (CmsSite)oldSite.clone();
                        newSite.setSiteRoot(siteRoot.getRootPath());
                        updateMap.put(oldSite, newSite);
                    }
                }
            }

            for (CmsSite site : updateMap.keySet()) {
                updateSite(m_clone, site, updateMap.get(site));
            }
        } catch (CmsException e) {
            LOG.error("Unable to handle publish event", e);
        }

    }

    /**
     * Returns all wrong configured sites.<p>
     *
     * @param cms CmsObject
     * @param workplaceMode workplace mode
     * @return List of CmsSite
     */
    public List<CmsSite> getAvailableCorruptedSites(CmsObject cms, boolean workplaceMode) {

        List<CmsSite> res = new ArrayList<CmsSite>();
        List<CmsSite> visSites = getAvailableSites(cms, workplaceMode);
        Map<CmsSiteMatcher, CmsSite> allsites = getSites();
        for (CmsSiteMatcher matcher : allsites.keySet()) {
            if (!visSites.contains(allsites.get(matcher))) {
                res.add(allsites.get(matcher));
            }
        }
        return res;
    }

    /**
     * Returns a list of all sites available (visible) for the current user.<p>
     *
     * @param cms the current OpenCms user context
     * @param workplaceMode if true, the root and current site is included for the admin user
     *                      and the view permission is required to see the site root
     *
     * @return a list of all sites available for the current user
     */
    public List<CmsSite> getAvailableSites(CmsObject cms, boolean workplaceMode) {

        return getAvailableSites(cms, workplaceMode, cms.getRequestContext().getOuFqn());
    }

    /**
     * Returns a list of all {@link CmsSite} instances that are compatible to the given organizational unit.<p>
     *
     * @param cms the current OpenCms user context
     * @param workplaceMode if true, the root and current site is included for the admin user
     *                      and the view permission is required to see the site root
     * @param showShared if the shared folder should be shown
     * @param ouFqn the organizational unit
     *
     * @return a list of all site available for the current user
     */
    public List<CmsSite> getAvailableSites(CmsObject cms, boolean workplaceMode, boolean showShared, String ouFqn) {

        List<String> siteroots = new ArrayList<String>(m_siteMatcherSites.size() + 1);
        Map<String, CmsSiteMatcher> siteServers = new HashMap<String, CmsSiteMatcher>(m_siteMatcherSites.size() + 1);
        List<CmsSite> result = new ArrayList<CmsSite>(m_siteMatcherSites.size() + 1);

        Iterator<CmsSiteMatcher> i;
        // add site list
        i = m_siteMatcherSites.keySet().iterator();
        while (i.hasNext()) {
            CmsSite site = m_siteMatcherSites.get(i.next());
            String folder = CmsFileUtil.addTrailingSeparator(site.getSiteRoot());
            if (!siteroots.contains(folder)) {
                siteroots.add(folder);
                siteServers.put(folder, site.getSiteMatcher());
            }
        }
        // add default site
        if (workplaceMode && (m_defaultSite != null)) {
            String folder = CmsFileUtil.addTrailingSeparator(m_defaultSite.getSiteRoot());
            if (!siteroots.contains(folder)) {
                siteroots.add(folder);
            }
        }

        String storedSiteRoot = cms.getRequestContext().getSiteRoot();
        try {
            // for all operations here we need no context
            cms.getRequestContext().setSiteRoot("/");
            if (workplaceMode && OpenCms.getRoleManager().hasRole(cms, CmsRole.VFS_MANAGER)) {
                if (!siteroots.contains("/")) {
                    // add the root site if the user is in the workplace and has the required role
                    siteroots.add("/");
                }
                if (!siteroots.contains(CmsFileUtil.addTrailingSeparator(storedSiteRoot))) {
                    siteroots.add(CmsFileUtil.addTrailingSeparator(storedSiteRoot));
                }
            }
            // add the shared site
            String shared = OpenCms.getSiteManager().getSharedFolder();
            if (showShared && (shared != null) && !siteroots.contains(shared)) {
                siteroots.add(shared);
            }

            List<CmsResource> resources;
            try {
                resources = OpenCms.getOrgUnitManager().getResourcesForOrganizationalUnit(cms, ouFqn);
            } catch (CmsException e) {
                return Collections.emptyList();
            }

            Collections.sort(siteroots); // sort by resource name
            Iterator<String> roots = siteroots.iterator();
            while (roots.hasNext()) {
                String folder = roots.next();
                boolean compatible = false;
                Iterator<CmsResource> itResources = resources.iterator();
                while (itResources.hasNext()) {
                    CmsResource resource = itResources.next();
                    if (resource.getRootPath().startsWith(folder) || folder.startsWith(resource.getRootPath())) {
                        compatible = true;
                        break;
                    }
                }
                // select only sites compatibles to the given organizational unit
                if (compatible) {
                    try {
                        CmsResource res = cms.readResource(folder);
                        if (!workplaceMode
                            || cms.hasPermissions(
                                res,
                                CmsPermissionSet.ACCESS_VIEW,
                                false,
                                CmsResourceFilter.ONLY_VISIBLE)) {

                            // get the title and the position from the system configuration first
                            CmsSite configuredSite = m_siteRootSites.get(CmsFileUtil.removeTrailingSeparator(folder));

                            // get the title
                            String title = null;
                            if ((configuredSite != null)
                                && CmsStringUtil.isNotEmptyOrWhitespaceOnly(configuredSite.getTitle())) {
                                title = configuredSite.getTitle();
                            }
                            if (title == null) {
                                title = getSiteTitle(cms, res);
                            }

                            // get the position
                            String position = null;
                            if ((configuredSite != null) && (configuredSite.getPosition() != Float.MAX_VALUE)) {
                                position = Float.toString(configuredSite.getPosition());
                            }
                            if (position == null) {
                                // not found, use the 'NavPos' property
                                position = cms.readPropertyObject(
                                    res,
                                    CmsPropertyDefinition.PROPERTY_NAVPOS,
                                    false).getValue();
                            }
                            if (configuredSite != null) {
                                float pos = Float.MAX_VALUE;
                                try {
                                    pos = Float.parseFloat(position);
                                } catch (Throwable e) {
                                    // m_position will have Float.MAX_VALUE, so this site will appear last
                                }
                                CmsSite clone = (CmsSite)configuredSite.clone();
                                clone.setPosition(pos);
                                clone.setTitle(title);
                                result.add(clone);
                            } else {
                                // add the site to the result
                                result.add(
                                    new CmsSite(
                                        folder,
                                        res.getStructureId(),
                                        title,
                                        siteServers.get(folder),
                                        position));
                            }
                        }
                    } catch (CmsException e) {
                        // user probably has no read access to the folder, ignore and continue iterating
                    }
                }
            }

            // sort and ensure that the shared folder is the last element in the list
            Collections.sort(result, new Comparator<CmsSite>() {

                public int compare(CmsSite o1, CmsSite o2) {

                    if (isSharedFolder(o1.getSiteRoot())) {
                        return +1;
                    }
                    if (isSharedFolder(o2.getSiteRoot())) {
                        return -1;
                    }
                    return o1.compareTo(o2);
                }
            });
        } catch (Throwable t) {
            LOG.error(Messages.get().getBundle().key(Messages.LOG_READ_SITE_PROP_FAILED_0), t);
        } finally {
            // restore the user's current context
            cms.getRequestContext().setSiteRoot(storedSiteRoot);
        }
        return result;
    }

    /**
     * Returns a list of all {@link CmsSite} instances that are compatible to the given organizational unit.<p>
     *
     * @param cms the current OpenCms user context
     * @param workplaceMode if true, the root and current site is included for the admin user
     *                      and the view permission is required to see the site root
     * @param ouFqn the organizational unit
     *
     * @return a list of all site available for the current user
     */
    public List<CmsSite> getAvailableSites(CmsObject cms, boolean workplaceMode, String ouFqn) {

        return getAvailableSites(cms, workplaceMode, workplaceMode, ouFqn);
    }

    /**
     * Returns the current site for the provided OpenCms user context object.<p>
     *
     * In the unlikely case that no site matches with the provided OpenCms user context,
     * the default site is returned.<p>
     *
     * @param cms the OpenCms user context object to check for the site
     *
     * @return the current site for the provided OpenCms user context object
     */
    public CmsSite getCurrentSite(CmsObject cms) {

        CmsSite site = getSiteForSiteRoot(cms.getRequestContext().getSiteRoot());
        return (site == null) ? m_defaultSite : site;
    }

    /**
     * Returns the default site.<p>
     *
     * @return the default site
     */
    public CmsSite getDefaultSite() {

        return m_defaultSite;
    }

    /**
     * Returns the defaultUri.<p>
     *
     * @return the defaultUri
     */
    public String getDefaultUri() {

        return m_defaultUri;
    }

    /**
     * Returns the shared folder path.<p>
     *
     * @return the shared folder path
     */
    public String getSharedFolder() {

        return m_sharedFolder;
    }

    /**
     * Returns the site for the given resource path, using the fall back site root
     * in case the resource path is no root path.<p>
     *
     * In case neither the given resource path, nor the given fall back site root
     * matches any configured site, the default site is returned.<p>
     *
     * Usually the fall back site root should be taken from {@link org.opencms.file.CmsRequestContext#getSiteRoot()},
     * in which case a site for the site root should always exist.<p>
     *
     * This is the same as first calling {@link #getSiteForRootPath(String)} with the
     * <code>resourcePath</code> parameter, and if this fails calling
     * {@link #getSiteForSiteRoot(String)} with the <code>fallbackSiteRoot</code> parameter,
     * and if this fails calling {@link #getDefaultSite()}.<p>
     *
     * @param rootPath the resource root path to get the site for
     * @param fallbackSiteRoot site root to use in case the resource path is no root path
     *
     * @return the site for the given resource path, using the fall back site root
     *      in case the resource path is no root path
     *
     * @see #getSiteForRootPath(String)
     */
    public CmsSite getSite(String rootPath, String fallbackSiteRoot) {

        CmsSite result = getSiteForRootPath(rootPath);
        if (result == null) {
            result = getSiteForSiteRoot(fallbackSiteRoot);
            if (result == null) {
                result = getDefaultSite();
            }
        }
        return result;
    }

    /**
     * Gets the site which is mapped to the default uri, or the 'absent' value of no such site exists.<p>
     *
     * @return the optional site mapped to the default uri
     */
    public Optional<CmsSite> getSiteForDefaultUri() {

        String defaultUri = getDefaultUri();
        CmsSite candidate = m_siteRootSites.get(CmsFileUtil.removeTrailingSeparator(defaultUri));
        return Optional.fromNullable(candidate);
    }

    /**
     * Returns the site for the given resources root path,
     * or <code>null</code> if the resources root path does not match any site.<p>
     *
     * @param rootPath the root path of a resource
     *
     * @return the site for the given resources root path,
     *      or <code>null</code> if the resources root path does not match any site
     *
     * @see #getSiteForSiteRoot(String)
     * @see #getSiteRoot(String)
     */
    public CmsSite getSiteForRootPath(String rootPath) {

        if ((rootPath.length() > 0) && !rootPath.endsWith("/")) {
            rootPath = rootPath + "/";
        }
        // most sites will be below the "/sites/" folder,
        CmsSite result = lookupSitesFolder(rootPath);
        if (result != null) {
            return result;
        }
        // look through all folders that are not below "/sites/"
        String siteRoot = lookupAdditionalSite(rootPath);
        return (siteRoot != null) ? getSiteForSiteRoot(siteRoot) : null;
    }

    /**
     * Returns the site with has the provided site root,
     * or <code>null</code> if no configured site has that site root.<p>
     *
     * The site root must have the form:
     * <code>/sites/default</code>.<br>
     * That means there must be a leading, but no trailing slash.<p>
     *
     * @param siteRoot the site root to look up the site for
     *
     * @return the site with has the provided site root,
     *      or <code>null</code> if no configured site has that site root
     *
     * @see #getSiteForRootPath(String)
     */
    public CmsSite getSiteForSiteRoot(String siteRoot) {

        return m_siteRootSites.get(siteRoot);
    }

    /**
     * Returns the site root part for the given resources root path,
     * or <code>null</code> if the given resources root path does not match any site root.<p>
     *
     * The site root returned will have the form:
     * <code>/sites/default</code>.<br>
     * That means there will a leading, but no trailing slash.<p>
     *
     * @param rootPath the root path of a resource
     *
     * @return the site root part of the resources root path,
     *      or <code>null</code> if the path does not match any site root
     *
     * @see #getSiteForRootPath(String)
     */
    public String getSiteRoot(String rootPath) {

        if (rootPath.startsWith(ROOT_PATH_PREFIX)) {
            rootPath = rootPath.substring(ROOT_PATH_PREFIX.length());
        }

        // add a trailing slash, because the path may be the path of a site root itself
        if (!rootPath.endsWith("/")) {
            rootPath = rootPath + "/";
        }
        // most sites will be below the "/sites/" folder,
        CmsSite site = lookupSitesFolder(rootPath);
        if (site != null) {
            return site.getSiteRoot();
        }
        // look through all folders that are not below "/sites/"
        return lookupAdditionalSite(rootPath);
    }

    /**
     * Returns an unmodifiable set of all configured site roots (Strings).<p>
     *
     * @return an unmodifiable set of all configured site roots (Strings)
     */
    public Set<String> getSiteRoots() {

        return m_siteRootSites.keySet();
    }

    /**
     * Returns the map of configured sites, using
     * {@link CmsSiteMatcher} objects as keys and {@link CmsSite} objects as values.<p>
     *
     * @return the map of configured sites, using {@link CmsSiteMatcher}
     *      objects as keys and {@link CmsSite} objects as values
     */
    public Map<CmsSiteMatcher, CmsSite> getSites() {

        return m_siteMatcherSites;
    }

    /**
     * Returns the site title.<p>
     *
     * @param cms the cms context
     * @param resource the site root resource
     *
     * @return the title
     *
     * @throws CmsException in case reading the title property fails
     */
    public String getSiteTitle(CmsObject cms, CmsResource resource) throws CmsException {

        String title = cms.readPropertyObject(resource, CmsPropertyDefinition.PROPERTY_TITLE, false).getValue();
        if (title == null) {
            title = resource.getRootPath();
        }
        if (resource.getRootPath().equals(getSharedFolder())) {
            title = SHARED_FOLDER_TITLE;
        }
        return title;
    }

    /**
     * Get web server scripting configurations.<p>
     *
     * @return Map with configuration data
     */
    public Map<String, String> getWebServerConfig() {

        return m_apacheConfig;
    }

    /**
     * Returns the workplace server.<p>
     *
     * @return the workplace server
     */
    public String getWorkplaceServer() {

        return m_workplaceServers.isEmpty() ? null : m_workplaceServers.get(0);
    }

    /**
     * Returns the configured worklace servers.<p>
     *
     * @return the workplace servers
     */
    public List<String> getWorkplaceServers() {

        return Collections.unmodifiableList(m_workplaceServers);
    }

    /**
     * Returns the site matcher that matches the workplace site.<p>
     *
     * @return the site matcher that matches the workplace site
     */
    public CmsSiteMatcher getWorkplaceSiteMatcher() {

        return m_workplaceMatchers.isEmpty() ? null : m_workplaceMatchers.get(0);
    }

    /**
     * Returns whether the given path starts with the root path prefix.<p>
     *
     * @param rootPath the path to check
     *
     * @return <code>true</code> if the given path starts with the root path prefix
     */
    public boolean hasRootPathPrefix(String rootPath) {

        return rootPath.startsWith(ROOT_PATH_PREFIX);
    }

    /**
     * Initializes the site manager with the OpenCms system configuration.<p>
     *
     * @param cms an OpenCms context object that must have been initialized with "Admin" permissions
     */
    public void initialize(CmsObject cms) {

        if (CmsLog.INIT.isInfoEnabled()) {
            CmsLog.INIT.info(
                Messages.get().getBundle().key(
                    Messages.INIT_NUM_SITE_ROOTS_CONFIGURED_1,
                    new Integer((m_siteMatcherSites.size() + ((m_defaultUri != null) ? 1 : 0)))));
        }

        try {

            m_clone = OpenCms.initCmsObject(cms);
            m_clone.getRequestContext().setSiteRoot("");
            m_clone.getRequestContext().setCurrentProject(m_clone.readProject(CmsProject.ONLINE_PROJECT_NAME));

            CmsObject cms_offline = OpenCms.initCmsObject(m_clone);
            CmsProject tempProject = cms_offline.createProject(
                "tempProjectSites",
                "",
                "/Users",
                "/Users",
                CmsProject.PROJECT_TYPE_TEMPORARY);
            cms_offline.getRequestContext().setCurrentProject(tempProject);

            m_siteUUIDs = new HashMap<CmsUUID, CmsSite>();
            // check the presence of sites in VFS

            m_onlyOfflineSites = new ArrayList<CmsSite>();

            for (CmsSite site : m_siteMatcherSites.values()) {
                checkUUIDOfSiteRoot(site, m_clone, cms_offline);
                try {
                    CmsResource siteRes = m_clone.readResource(site.getSiteRoot());
                    site.setSiteRootUUID(siteRes.getStructureId());

                    m_siteUUIDs.put(siteRes.getStructureId(), site);
                    // during server startup the digester can not access properties, so set the title afterwards
                    if (CmsStringUtil.isEmptyOrWhitespaceOnly(site.getTitle())) {
                        String title = m_clone.readPropertyObject(
                            siteRes,
                            CmsPropertyDefinition.PROPERTY_TITLE,
                            false).getValue();
                        if (CmsStringUtil.isNotEmptyOrWhitespaceOnly(title)) {
                            site.setTitle(title);
                        }
                    }
                } catch (Throwable t) {
                    if (CmsLog.INIT.isWarnEnabled()) {
                        CmsLog.INIT.warn(Messages.get().getBundle().key(Messages.INIT_NO_ROOT_FOLDER_1, site));
                    }
                }
            }
            cms_offline.deleteProject(tempProject.getUuid());

            // check the presence of the default site in VFS
            if (CmsStringUtil.isEmptyOrWhitespaceOnly(m_defaultUri)) {
                m_defaultSite = null;
            } else {
                m_defaultSite = new CmsSite(m_defaultUri, CmsSiteMatcher.DEFAULT_MATCHER);
                try {
                    m_clone.readResource(m_defaultSite.getSiteRoot());
                } catch (Throwable t) {
                    if (CmsLog.INIT.isWarnEnabled()) {
                        CmsLog.INIT.warn(
                            Messages.get().getBundle().key(Messages.INIT_NO_ROOT_FOLDER_DEFAULT_SITE_1, m_defaultSite));
                    }
                }
            }
            if (m_defaultSite == null) {
                m_defaultSite = new CmsSite("/", CmsSiteMatcher.DEFAULT_MATCHER);
            }
            if (CmsLog.INIT.isInfoEnabled()) {
                if (m_defaultSite != null) {
                    CmsLog.INIT.info(Messages.get().getBundle().key(Messages.INIT_DEFAULT_SITE_ROOT_1, m_defaultSite));
                } else {
                    CmsLog.INIT.info(Messages.get().getBundle().key(Messages.INIT_DEFAULT_SITE_ROOT_0));
                }
            }
            initWorkplaceMatchers();

            // set site lists to unmodifiable
            setSiteMatcherSites(m_siteMatcherSites);

            // store additional site roots to optimize lookups later
            for (String root : m_siteRootSites.keySet()) {
                if (!root.startsWith(SITES_FOLDER) || (root.split("/").length >= 4)) {
                    m_additionalSiteRoots.add(root);
                }
            }

            // initialization is done, set the frozen flag to true
            m_frozen = true;
        } catch (CmsException e) {
            LOG.warn(e);
        }
        if (!m_isListenerSet) {
            OpenCms.addCmsEventListener(this, new int[] {I_CmsEventListener.EVENT_PUBLISH_PROJECT});
            m_isListenerSet = true;
        }
    }

    /**
     * Checks if web server scripting is enabled.<p>
     *
     * @return true if web server scripting is set to available
     */
    public boolean isConfigurableWebServer() {

        return m_apacheConfig != null;
    }

    /**
     * Returns <code>true</code> if the given site matcher matches any configured site,
     * which includes the workplace site.<p>
     *
     * @param matcher the site matcher to match the site with
     *
     * @return <code>true</code> if the matcher matches a site
     */
    public boolean isMatching(CmsSiteMatcher matcher) {

        boolean result = m_siteMatcherSites.get(matcher) != null;
        if (!result) {
            // try to match the workplace site
            result = isWorkplaceRequest(matcher);
        }
        return result;
    }

    /**
     * Returns <code>true</code> if the given site matcher matches the current site.<p>
     *
     * @param cms the current OpenCms user context
     * @param matcher the site matcher to match the site with
     *
     * @return <code>true</code> if the matcher matches the current site
     */
    public boolean isMatchingCurrentSite(CmsObject cms, CmsSiteMatcher matcher) {

        return m_siteMatcherSites.get(matcher) == getCurrentSite(cms);
    }

    /**
     * Indicates if given site is only available for offline repository.<p>
     *
     * @param site to be looked up
     * @return true if only offline exists, false otherwise
     */
    public boolean isOnlyOfflineSite(CmsSite site) {

        return m_onlyOfflineSites.contains(site);
    }

    /**
     * Checks if the given path is that of a shared folder.<p>
     *
     * @param name a path prefix
     *
     * @return true if the given prefix represents a shared folder
     */
    public boolean isSharedFolder(String name) {

        return (m_sharedFolder != null) && m_sharedFolder.equals(CmsStringUtil.joinPaths("/", name, "/"));
    }

    /**
     * Checks whether a given root path is a site root.<p>
     *
     * @param rootPath a root path
     *
     * @return true if the given path is the path of a site root
     */
    public boolean isSiteRoot(String rootPath) {

        String siteRoot = getSiteRoot(rootPath);
        rootPath = CmsStringUtil.joinPaths(rootPath, "/");
        return rootPath.equals(siteRoot);

    }

    /**
     * Returns <code>true</code> if the given site matcher matches the configured OpenCms workplace.<p>
     *
     * @param matcher the site matcher to match the site with
     *
     * @return <code>true</code> if the given site matcher matches the configured OpenCms workplace
     */
    public boolean isWorkplaceRequest(CmsSiteMatcher matcher) {

        return m_workplaceMatchers.contains(matcher);
    }

    /**
     * Returns <code>true</code> if the given request is against the configured OpenCms workplace.<p>
     *
     * @param req the request to match
     *
     * @return <code>true</code> if the given request is against the configured OpenCms workplace
     */
    public boolean isWorkplaceRequest(HttpServletRequest req) {

        if (req == null) {
            // this may be true inside a static export test case scenario
            return false;
        }
        return isWorkplaceRequest(getRequestMatcher(req));
    }

    /**
     * Matches the given request against all configures sites and returns
     * the matching site, or the default site if no sites matches.<p>
     *
     * @param req the request to match
     *
     * @return the matching site, or the default site if no sites matches
     */
    public CmsSite matchRequest(HttpServletRequest req) {

        CmsSiteMatcher matcher = getRequestMatcher(req);
        if (matcher.getTimeOffset() != 0) {
            HttpSession session = req.getSession();
            if (session != null) {
                session.setAttribute(
                    CmsContextInfo.ATTRIBUTE_REQUEST_TIME,
                    new Long(System.currentTimeMillis() + matcher.getTimeOffset()));
            }
        }
        CmsSite site = matchSite(matcher);

        if (LOG.isDebugEnabled()) {
            String requestServer = req.getScheme() + "://" + req.getServerName() + ":" + req.getServerPort();
            LOG.debug(
                Messages.get().getBundle().key(
                    Messages.LOG_MATCHING_REQUEST_TO_SITE_2,
                    requestServer,
                    site.toString()));
        }
        return site;
    }

    /**
     * Return the configured site that matches the given site matcher,
     * or the default site if no sites matches.<p>
     *
     * @param matcher the site matcher to match the site with
     * @return the matching site, or the default site if no sites matches
     */
    public CmsSite matchSite(CmsSiteMatcher matcher) {

        CmsSite site = m_siteMatcherSites.get(matcher);
        if (site == null) {
            // return the default site (might be null as well)
            site = m_defaultSite;
        }
        return site;
    }

    /**
     * Removes the root path prefix.<p>
     *
     * @param rootPath the prefixed path
     *
     * @return the path without the prefix
     */
    public String removeRootPathPrefix(String rootPath) {

        return rootPath.substring(ROOT_PATH_PREFIX.length());
    }

    /**
     * Removes a site from the list of configured sites.<p>
     *
     * @param cms the cms object
     * @param site the site to remove
     *
     * @throws CmsException if something goes wrong
     */
    public void removeSite(CmsObject cms, CmsSite site) throws CmsException {

        // check permissions
        if (OpenCms.getRunLevel() > OpenCms.RUNLEVEL_1_CORE_OBJECT) {
            // simple unit tests will have runlevel 1 and no CmsObject
            OpenCms.getRoleManager().checkRole(cms, CmsRole.DATABASE_MANAGER);
        }

        // un-freeze
        m_frozen = false;

        // create a new map containing all existing sites without the one to remove
        Map<CmsSiteMatcher, CmsSite> siteMatcherSites = new HashMap<CmsSiteMatcher, CmsSite>();
        for (Map.Entry<CmsSiteMatcher, CmsSite> entry : m_siteMatcherSites.entrySet()) {
            // iterate over the existing sites
            boolean isSite = site.getUrl().equals(entry.getKey().getUrl());
            boolean isSecure = site.hasSecureServer() ? site.getSecureUrl().equals(entry.getKey().getUrl()) : false;
            boolean isAlias = site.getAliases().contains(entry.getKey());
            if (!(isSite || isSecure || isAlias)) {
                // entry not the site itself nor an alias of the site nor the secure URL of the site, so add it
                siteMatcherSites.put(entry.getKey(), entry.getValue());
            }
        }
        setSiteMatcherSites(siteMatcherSites);

        // remove the site from the map holding the site roots as keys and the sites as values
        Map<String, CmsSite> siteRootSites = new HashMap<String, CmsSite>(m_siteRootSites);
        siteRootSites.remove(site.getSiteRoot());
        m_siteRootSites = Collections.unmodifiableMap(siteRootSites);

        // re-initialize, will freeze the state when finished
        initialize(cms);
        OpenCms.writeConfiguration(CmsSitesConfiguration.class);
    }

    /**
     * Returns whether the given resource root path requires a root path prefix.<p>
     * This is the case if the path does not point into a configured site or below /system/ or into the shared folder.<p>
     *
     * @param rootPath the root path
     *
     * @return <code>true</code> in case the root path prefix is required
     */
    public boolean requiresRootPathPrefix(String rootPath) {

        return (getSiteRoot(rootPath) == null)
            && !(startsWithShared(rootPath) || rootPath.startsWith(CmsWorkplace.VFS_PATH_SYSTEM));
    }

    /**
     * Sets the default URI, this is only allowed during configuration.<p>
     *
     * If this method is called after the configuration is finished,
     * a <code>RuntimeException</code> is thrown.<p>
     *
     * @param defaultUri the defaultUri to set
     */
    public void setDefaultUri(String defaultUri) {

        if (m_frozen) {
            throw new CmsRuntimeException(Messages.get().container(Messages.ERR_CONFIG_FROZEN_0));
        }
        m_defaultUri = defaultUri;
    }

    /**
     * Sets the shared folder path.<p>
     *
     * @param sharedFolder the shared folder path
     */
    public void setSharedFolder(String sharedFolder) {

        if (m_frozen) {
            throw new CmsRuntimeException(Messages.get().container(Messages.ERR_CONFIG_FROZEN_0));
        }
        m_sharedFolder = CmsStringUtil.joinPaths("/", sharedFolder, "/");
    }

    /**
     * Set webserver script configuration.<p>
     *
     *
     * @param webserverscript path
     * @param targetpath path
     * @param configtemplate path
     * @param securetemplate path
     * @param filenameprefix to add to files
     * @param loggingdir path
     */
    public void setWebServerScripting(
        String webserverscript,
        String targetpath,
        String configtemplate,
        String securetemplate,
        String filenameprefix,
        String loggingdir) {

        m_apacheConfig = new HashMap<String, String>();
        m_apacheConfig.put(WEB_SERVER_CONFIG_WEBSERVERSCRIPT, webserverscript);
        m_apacheConfig.put(WEB_SERVER_CONFIG_TARGETPATH, targetpath);
        m_apacheConfig.put(WEB_SERVER_CONFIG_CONFIGTEMPLATE, configtemplate);
        m_apacheConfig.put(WEB_SERVER_CONFIG_SECURETEMPLATE, securetemplate);
        m_apacheConfig.put(WEB_SERVER_CONFIG_FILENAMEPREFIX, filenameprefix);
        m_apacheConfig.put(WEB_SERVER_CONFIG_LOGGINGDIR, loggingdir);
    }

    /**
     * Returns true if the path starts with the shared folder path.<p>
     *
     * @param path the path to check
     *
     * @return true if the path starts with the shared folder path
     */
    public boolean startsWithShared(String path) {

        return (m_sharedFolder != null) && CmsFileUtil.addTrailingSeparator(path).startsWith(m_sharedFolder);
    }

    /**
     * Updates the general settings.<p>
     *
     * @param cms the cms to use
     * @param defaulrUri the default URI
     * @param workplaceServers the workplace server URLs
     * @param sharedFolder the shared folder URI
     *
     * @throws CmsException if something goes wrong
     */
    public void updateGeneralSettings(
        CmsObject cms,
        String defaulrUri,
        List<String> workplaceServers,
        String sharedFolder)
    throws CmsException {

        CmsObject clone = OpenCms.initCmsObject(cms);
        clone.getRequestContext().setSiteRoot("");

        // set the shared folder
        if ((sharedFolder == null)
            || sharedFolder.equals("")
            || sharedFolder.equals("/")
            || !sharedFolder.startsWith("/")
            || !sharedFolder.endsWith("/")
            || sharedFolder.startsWith("/sites/")) {
            throw new CmsException(
                Messages.get().container(Messages.ERR_INVALID_PATH_FOR_SHARED_FOLDER_1, sharedFolder));
        }

        m_frozen = false;
        setDefaultUri(clone.readResource(defaulrUri).getRootPath());
        setSharedFolder(clone.readResource(sharedFolder).getRootPath());
        m_workplaceServers = new ArrayList<String>(workplaceServers);
        initWorkplaceMatchers();
        m_frozen = true;
    }

    /**
     * Updates or creates a site.<p>
     *
     * @param cms the CMS object
     * @param oldSite the site to remove if not <code>null</code>
     * @param newSite the site to add if not <code>null</code>
     *
     * @throws CmsException if something goes wrong
     */
    public void updateSite(CmsObject cms, CmsSite oldSite, CmsSite newSite) throws CmsException {

        if (oldSite != null) {
            // remove the old site
            removeSite(cms, oldSite);
        }

        if (newSite != null) {
            // add the new site
            addSite(cms, newSite);
        }
    }

    /**
     * Returns true if this request goes to a secure site.<p>
     *
     * @param req the request to check
     *
     * @return true if the request goes to a secure site
     */
    public boolean usesSecureSite(HttpServletRequest req) {

        CmsSite site = matchRequest(req);
        if (site == null) {
            return false;
        }
        CmsSiteMatcher secureMatcher = site.getSecureServerMatcher();
        boolean result = false;
        if (secureMatcher != null) {
            result = secureMatcher.equals(getRequestMatcher(req));
        }
        return result;
    }

    /**
     * Adds a new Site matcher object to the map of server names.
     *
     * @param matcher the SiteMatcher of the server
     * @param site the site to add
     *
     * @throws CmsConfigurationException if the site contains a server name, that is already assigned
     */
    private void addServer(CmsSiteMatcher matcher, CmsSite site) throws CmsConfigurationException {

        if (m_siteMatcherSites.containsKey(matcher)) {
            throw new CmsConfigurationException(
                Messages.get().container(Messages.ERR_DUPLICATE_SERVER_NAME_1, matcher.getUrl()));
        }
        Map<CmsSiteMatcher, CmsSite> siteMatcherSites = new HashMap<CmsSiteMatcher, CmsSite>(m_siteMatcherSites);
        siteMatcherSites.put(matcher, site);
        setSiteMatcherSites(siteMatcherSites);
    }

    /**
     * Fetches UUID for given site root from online and offline repository.<p>
     *
     * @param site to read and set UUID for
     * @param clone online CmsObject
     * @param cms_offline offline CmsObject
     */
    private void checkUUIDOfSiteRoot(CmsSite site, CmsObject clone, CmsObject cms_offline) {

        CmsUUID id = null;
        try {
            id = clone.readResource(site.getSiteRoot()).getStructureId();
        } catch (CmsException e) {
            //Ok, site root not available for online repository.
        }

        if (id == null) {
            try {
                id = cms_offline.readResource(site.getSiteRoot()).getStructureId();
                m_onlyOfflineSites.add(site);
            } catch (CmsException e) {
                //Siteroot not valid for on- and offline repository.
            }
        }
        if (id != null) {
            site.setSiteRootUUID(id);
            m_siteUUIDs.put(id, site);
        }
    }

    /**
     * Gets an offline project to read offline resources from.<p>
     *
     * @return CmsProject
     */
    private CmsProject getOfflineProject() {

        try {
            for (CmsProject p : OpenCms.getOrgUnitManager().getAllAccessibleProjects(m_clone, "/", true)) {
                if (!p.isOnlineProject()) {
                    return p;
                }
            }
        } catch (CmsException e) {
            LOG.error("Unable to find an offline project", e);
        }
        return null;
    }

    /**
     * Returns the site matcher for the given request.<p>
     *
     * @param req the request to get the site matcher for
     *
     * @return the site matcher for the given request
     */
    private CmsSiteMatcher getRequestMatcher(HttpServletRequest req) {

        CmsSiteMatcher matcher = new CmsSiteMatcher(req.getScheme(), req.getServerName(), req.getServerPort());
        // this is required to get the right configured time offset
        int index = m_siteMatchers.indexOf(matcher);
        if (index < 0) {
            return matcher;
        }
        return m_siteMatchers.get(index);
    }

    /**
     * Initializes the workplace matchers.<p>
     */
    private void initWorkplaceMatchers() {

        List<CmsSiteMatcher> matchers = new ArrayList<CmsSiteMatcher>();
        if (!m_workplaceServers.isEmpty()) {
            for (String server : m_workplaceServers) {
                CmsSiteMatcher matcher = new CmsSiteMatcher(server);
                matchers.add(matcher);
                if (CmsLog.INIT.isInfoEnabled()) {
                    CmsLog.INIT.info(Messages.get().getBundle().key(Messages.INIT_WORKPLACE_SITE_1, matcher));
                }
            }
        } else if (CmsLog.INIT.isInfoEnabled()) {
            CmsLog.INIT.info(Messages.get().getBundle().key(Messages.INIT_WORKPLACE_SITE_0));
        }
        m_workplaceMatchers = matchers;
    }

    /**
     * Returns <code>true</code> if the given root path matches any of the stored additional sites.<p>
     *
     * @param rootPath the root path to check
     *
     * @return <code>true</code> if the given root path matches any of the stored additional sites
     */
    private String lookupAdditionalSite(String rootPath) {

        for (int i = 0, size = m_additionalSiteRoots.size(); i < size; i++) {
            String siteRoot = m_additionalSiteRoots.get(i);
            if (rootPath.startsWith(siteRoot + "/")) {
                return siteRoot;
            }
        }
        return null;
    }

    /**
     * Returns the configured site if the given root path matches site in the "/sites/" folder,
     * or <code>null</code> otherwise.<p>
     *
     * @param rootPath the root path to check
     *
     * @return the configured site if the given root path matches site in the "/sites/" folder,
     *      or <code>null</code> otherwise
     */
    private CmsSite lookupSitesFolder(String rootPath) {

        int pos = rootPath.indexOf('/', SITES_FOLDER_POS);
        if (pos > 0) {
            // this assumes that the root path may likely start with something like "/sites/default/"
            // just cut the first 2 directories from the root path and do a direct lookup in the internal map
            return m_siteRootSites.get(rootPath.substring(0, pos));
        }
        return null;
    }

    /**
     * Sets the class member variables {@link #m_siteMatcherSites} and  {@link #m_siteMatchers}
     * from the provided map of configured site matchers.<p>
     *
     * @param siteMatcherSites the site matches to set
     */
    private void setSiteMatcherSites(Map<CmsSiteMatcher, CmsSite> siteMatcherSites) {

        m_siteMatcherSites = Collections.unmodifiableMap(siteMatcherSites);
        m_siteMatchers = Collections.unmodifiableList(new ArrayList<CmsSiteMatcher>(m_siteMatcherSites.keySet()));
    }
}