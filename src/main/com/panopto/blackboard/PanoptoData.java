/* Copyright Panopto 2009 - 2016
 *
 * This file is part of the Panopto plugin for Blackboard.
 *
 * The Panopto plugin for Blackboard is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Panopto plugin for Blackboard is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Panopto plugin for Blackboard.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.panopto.blackboard;

import java.lang.reflect.Array;
import java.net.URL;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.axis2.transport.http.HTTPConstants;

import blackboard.base.BbList.Iterator;
import blackboard.base.FormattedText;
import blackboard.data.ValidationException;
import blackboard.data.content.Content;
import blackboard.data.course.Course;
import blackboard.data.course.CourseMembership;
import blackboard.data.course.CourseMembership.Role;
import blackboard.data.navigation.CourseToc;
import blackboard.data.registry.CourseRegistryEntry;
import blackboard.data.registry.Registry;
import blackboard.data.registry.RegistryEntry;
import blackboard.data.user.User;
import blackboard.persist.BbPersistenceManager;
import blackboard.persist.Id;
import blackboard.persist.KeyNotFoundException;
import blackboard.persist.PersistenceException;
import blackboard.persist.content.ContentDbPersister;
import blackboard.persist.course.CourseDbLoader;
import blackboard.persist.course.CourseMembershipDbLoader;
import blackboard.persist.navigation.CourseTocDbLoader;
import blackboard.persist.navigation.CourseTocDbPersister;
import blackboard.persist.registry.CourseRegistryEntryDbLoader;
import blackboard.persist.registry.CourseRegistryEntryDbPersister;
import blackboard.persist.user.UserDbLoader;
import blackboard.platform.context.Context;
import blackboard.platform.persistence.PersistenceServiceFactory;
import blackboard.platform.plugin.PlugIn;
import blackboard.platform.plugin.PlugInManager;
import blackboard.platform.plugin.PlugInManagerFactory;

import com.panopto.blackboard.Utils;
import com.panopto.services.SessionManagementStub;
import com.panopto.services.SessionManagementStub.DeleteFolders;
import com.panopto.services.SessionManagementStub.Folder;
import com.panopto.services.SessionManagementStub.FolderAvailabilitySettings;
import com.panopto.services.SessionManagementStub.FolderSortField;
import com.panopto.services.SessionManagementStub.GetCreatorFoldersList;
import com.panopto.services.SessionManagementStub.GetCreatorFoldersListResponse;
import com.panopto.services.SessionManagementStub.GetFoldersAvailabilitySettings;
import com.panopto.services.SessionManagementStub.GetFoldersById;
import com.panopto.services.SessionManagementStub.GetFoldersByIdResponse;
import com.panopto.services.SessionManagementStub.GetFoldersList;
import com.panopto.services.SessionManagementStub.GetFoldersListResponse;
import com.panopto.services.SessionManagementStub.GetRecorderDownloadUrls;
import com.panopto.services.SessionManagementStub.GetRecorderDownloadUrlsResponse;
import com.panopto.services.SessionManagementStub.GetSessionsAvailabilitySettings;
import com.panopto.services.SessionManagementStub.GetSessionsById;
import com.panopto.services.SessionManagementStub.GetSessionsList;
import com.panopto.services.SessionManagementStub.GetSessionsListResponse;
import com.panopto.services.SessionManagementStub.ListFoldersRequest;
import com.panopto.services.SessionManagementStub.ListFoldersResponse;
import com.panopto.services.SessionManagementStub.ListSessionsRequest;
import com.panopto.services.SessionManagementStub.ListSessionsResponse;
import com.panopto.services.SessionManagementStub.ProvisionExternalCourse;
import com.panopto.services.SessionManagementStub.RecorderDownloadUrlResponse;
import com.panopto.services.SessionManagementStub.Session;
import com.panopto.services.SessionManagementStub.SessionAvailabilitySettings;
import com.panopto.services.SessionManagementStub.SessionSortField;
import com.panopto.services.SessionManagementStub.SessionStartSettingType;
import com.panopto.services.SessionManagementStub.SessionState;
import com.panopto.services.SessionManagementStub.SetCopiedExternalCourseAccess;
import com.panopto.services.SessionManagementStub.SetExternalCourseAccess;
import com.panopto.services.SessionManagementStub.UnprovisionExternalCourse;
import com.panopto.services.SessionManagementStub.UpdateFolderExternalIdWithProvider;
import com.panopto.services.SessionManagementStub.UpdateSessionsAvailabilityStartSettings;
import com.panopto.services.UserManagementStub.SyncExternalUser;
import com.panopto.services.UserManagementStub;
import com.panopto.services.AccessManagementStub;
import com.panopto.services.AuthStub;
import com.panopto.services.AuthStub.ReportIntegrationInfo;

// Wrap interaction with DB and Panopto SOAP services for a particular Blackboard course
public class PanoptoData {
    // Keys for Blackboard course registry settings
    private static final String hostnameRegistryKey = "CourseCast_Hostname";
    private static final String sessionGroupIDRegistryKey = "CourseCast_SessionGroupID";
    private static final String sessionGroupDisplayNameRegistryKey = "CourseCast_SessionGroupDisplayName";
    private static final String copySessionGroupIDsRegistryKey = "CourseCast_CopySessionGroupIDs";
    private static final String copySessionGroupDisplayNamesRegistryKey = "CourseCast_CopySessionGroupDisplayNames";
    
    // There is no error code associated with this error so we need to parse the message for an expected substring.
    private static final String personalFolderErrorIdentifier = "provision personal folder";
    
    // version strings to report back to Panopto, only need to grab if not defined (see below in InitPanoptoData).
    private static String plugInVersion;
    private static String platformVersion;

    // Argument used to return empty arrays as they are immutable.
    private static final String[] emptyStringArray = new String[0];

    // Constants used for paging.
    private int maxPages = 100;
    private final int perPage = 100;

    // Blackboard course we are associating with
    private Course bbCourse;
    // Blackboard username of currently logged in user
    private String bbUserName;

    private List<String> instructorRoleIds = new ArrayList<String>();
    private List<String> taRoleIds = new ArrayList<String>();

    private boolean isInstructor = false;
    private boolean canAddLinks = false;

    // Panopto server to talk to
    private String serverName;

    // Version number of the current Panopto server
    private PanoptoVersion serverVersion;
    
    // User key to use when talking to Panopto SOAP services
    // (Instance-decorated username of currently-logged-in Blackboard user)
    private String apiUserKey;
    // Hash of username, server, and shared secret for securing web services
    private String apiUserAuthCode;

    // ID and display name of currently associated Panopto course
    private String[] sessionGroupPublicIDs;
    private String[] sessionGroupDisplayNames;

    // ID and display name of associated Panopto courses through a copy
    private String[] copySessionGroupPublicIDs;
    private String[] copySessionGroupDisplayNames;
    
    // If provisioning fails we output the error here so the parent Java window can grab it and process the error if needed.
    private String previousProvisioningError = "";
    
    // Returns the last error that had occured during provisioning, if the last provisioning call failed.
    public String getPreviousProvisioningError() {
        return previousProvisioningError;
    }
    
    // SOAP port for talking to Panopto
    private SessionManagementStub sessionManagement;

    // Construct the PanoptoData object using the current Blackboard context
    // (e.g. from <bbData:context ...> tag)
    // Pulls in stored property values from BB course registry if available.
    // Ensure that serverName and
    // sessionGroupPublicIDs are set before calling any instance methods that
    // rely on these properties (most).
    public PanoptoData(Context ctx) {
        InitPanoptoData(ctx.getCourse(), ctx.getUser().getUserName());
    }

    public PanoptoData(String bbCourseId, String bbUserName) {
        BbPersistenceManager bbPm = PersistenceServiceFactory.getInstance().getDbPersistenceManager();
        Course bbCourse = null;
        try {

            CourseDbLoader courseLoader = (CourseDbLoader) bbPm.getLoader(CourseDbLoader.TYPE);
            bbCourse = courseLoader.loadByCourseId(bbCourseId);
            InitPanoptoData(bbCourse, bbUserName);
        } catch (Exception e) {
            Utils.log(e, String.format("Error getting course info (course ID: %s).", bbCourseId));
        }
    }

    public PanoptoData(Course bbCourse, String bbUserName) {
        InitPanoptoData(bbCourse, bbUserName);
    }

    private void InitPanoptoData(Course bbCourse, String bbUserName) {
        this.bbCourse = bbCourse;
        this.bbUserName = bbUserName;
        this.isInstructor = PanoptoData.isUserInstructor(this.bbCourse.getId(), this.bbUserName, false);
        this.canAddLinks = PanoptoData.canUserAddLinks(this.bbCourse.getId(), this.bbUserName);
        
        int maxListedFolders = Integer.parseInt(Utils.pluginSettings.getMaxListedFolders());
        this.maxPages = (maxListedFolders / this.perPage);
        
        // If the user put in a maxListedFolders value that isn't divisible by our pages then add an extra page.
        if ((maxListedFolders % this.perPage) != 0) {
            ++this.maxPages;
        }
        
        Utils.pluginSettings = new Settings();

        List<String> serverList = Utils.pluginSettings.getServerList();
        // If there is only one server available, use it
        if (serverList.size() == 1) {
            updateServerName(serverList.get(0));
        } else {
            updateServerName(getCourseRegistryEntry(hostnameRegistryKey));
        }

        String roleMappingsString = Utils.pluginSettings.getRoleMappingString();
        String[] roleMappingsSplit = roleMappingsString.split(";");
        for (String mappingString : roleMappingsSplit) {
            String[] mappingArray = mappingString.split(":");
            if (mappingArray.length == 2) {
                String RoleId = mappingArray[0];
                if (mappingArray[1].trim().equalsIgnoreCase("instructor")) {
                    instructorRoleIds.add(RoleId);
                } else if (mappingArray[1].trim().equalsIgnoreCase("ta")) {
                    taRoleIds.add(RoleId);
                }
            }
        }

        sessionGroupPublicIDs = getCourseRegistryEntries(sessionGroupIDRegistryKey);
        sessionGroupDisplayNames = getCourseRegistryEntries(sessionGroupDisplayNameRegistryKey);

        // Check that the list of Ids and names are valid. They must both be the
        // same length
        if ((sessionGroupPublicIDs == null && sessionGroupDisplayNames != null)
                || (sessionGroupPublicIDs != null && sessionGroupDisplayNames == null)
                || (sessionGroupPublicIDs != null && sessionGroupPublicIDs.length != sessionGroupDisplayNames.length)) {
            Utils.log(String.format(
                    "Invalid course registry settings. Reseting both to null. sessionGroupPublicIDs = %s"
                            + "sessionGroupDisplayNames = %s",
                    Utils.encodeArrayOfStrings(sessionGroupPublicIDs),
                    Utils.encodeArrayOfStrings(sessionGroupDisplayNames)));

            this.sessionGroupPublicIDs = null;
            this.sessionGroupDisplayNames = null;
            setCourseRegistryEntry(hostnameRegistryKey, null);
            setCourseRegistryEntries(sessionGroupIDRegistryKey, null);
            setCourseRegistryEntries(sessionGroupDisplayNameRegistryKey, null);
        }

        ValidateCourseCopyValues();
        
    }

    public Course getBBCourse() {
        return bbCourse;
    }

    public String getProvisionUrl(String serverName, String returnUrl, String courseUrl) {
        if (serverName == null) {
            return "";
        }
        
        String provisionUrl =  "Course_Provision.jsp?provisionServerName=" + serverName + "&bbCourses=" + getBBCourse().getCourseId()
                + "&returnUrl=" + returnUrl;
        
        if (courseUrl != null) {
            provisionUrl += "&courseUrl=" + courseUrl;
        }
        
        return provisionUrl;
    }

    public String getServerName() {
        return serverName;
    }

    /**
     * Get the display names of all folders provisioned to this course.
     * 
     * @return String Array of friendly names of folders provisioned to this
     *         course
     */
    public String[] getFolderDisplayNames() {
        if (sessionGroupDisplayNames == null) {
            return emptyStringArray;
        }
        return sessionGroupDisplayNames;
    }

    /**
     * Get the display names of all folders that this course has copied
     * permissions to.
     * 
     * @return String Array of friendly names of folders copied to this course
     */
    public String[] getCopiedFolderDisplayNames() {
        if (copySessionGroupDisplayNames == null) {
            return emptyStringArray;
        }
        return copySessionGroupDisplayNames;
    }

    // gets the number of folders associated with this course
    public int getNumberOfFolders() {
        if (sessionGroupPublicIDs == null) {
            return 0;
        }

        return sessionGroupPublicIDs.length;
    }

    /**
     * Gets the number of copied folders associated with this course
     * 
     * @return Integer representing number of folders copied to this course
     */
    public int getNumberOfCopiedFolders() {
        if (copySessionGroupPublicIDs == null) {
            return 0;
        }

        return copySessionGroupPublicIDs.length;
    }

    // Update properties that depend on serverName
    private void updateServerName(String serverName) {
        this.serverName = serverName;

        if ((serverName != null) && !serverName.equals("")) {
            apiUserKey = Utils.decorateBlackboardUserName(bbUserName);
            apiUserAuthCode = Utils.generateAuthCode(serverName, apiUserKey + "@" + serverName);
            sessionManagement = getPanoptoSessionManagementSOAPService(serverName);
            serverVersion = getServerVersion();

        } else {
            apiUserKey = null;
            apiUserAuthCode = null;
            sessionManagement = null;
            serverVersion = null;
        }
    }

    public boolean isServerSet() {
        return (serverName != null);
    }

    // i.e. a Panopto course been selected for this Blackboard course
    public boolean isMapped() {
        return (sessionGroupPublicIDs != null && sessionGroupPublicIDs.length > 0);
    }

    // Returns true if this course has inherited any permissions due to course
    // copy
    public boolean isCopyMapped() {
        return (copySessionGroupPublicIDs != null && sessionGroupPublicIDs.length > 0);
    }

    // Get the Panopto user being used for SOAP calls
    public String getApiUserKey() {
        return apiUserKey;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // The following "get..." functions are just wrappers for the relevant SOAP calls,
    // using stored course mapping and credentials.
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Gets all the sessions in a folder from the Panopto server. Returns null
    // on error.
    public Session[] getSessions(String folderId) {
        Session[] returnValue;
        try {
            // Get all the sessions
            int page = 0;
            int responseCount = 0;
            int totalSessionsExpected = -1;
            ListSessionsResponse listResponse;
            List<Session> allSessions = new ArrayList<Session>();
            com.panopto.services.SessionManagementStub.AuthenticationInfo auth = new com.panopto.services.SessionManagementStub.AuthenticationInfo();
            auth.setAuthCode(apiUserAuthCode);
            auth.setPassword(null);
            auth.setUserKey(apiUserKey);
            
            do {
                ListSessionsRequest request = new ListSessionsRequest();
                
                SessionManagementStub.Guid guidFolderId = new SessionManagementStub.Guid();
                guidFolderId.setGuid(folderId);
                request.setFolderId(guidFolderId);
                
                SessionManagementStub.Pagination pagination = new SessionManagementStub.Pagination();
                pagination.setMaxNumberResults(this.perPage);
                pagination.setPageNumber(page);
                
                request.setPagination(pagination);
                
                request.setSortBy(SessionSortField.Date);
                request.setSortIncreasing(true); // sortIncreasing = true
                
                SessionManagementStub.ArrayOfSessionState sessionStates = new SessionManagementStub.ArrayOfSessionState();
                sessionStates.addSessionState(SessionState.Broadcasting);
                sessionStates.addSessionState(SessionState.Complete);
                sessionStates.addSessionState(SessionState.Recording);
                request.setStates(sessionStates);
                
                GetSessionsList getSessionsListParams = new GetSessionsList();
                getSessionsListParams.setAuth(auth);
                getSessionsListParams.setRequest(request);
                getSessionsListParams.setSearchQuery(null);
                GetSessionsListResponse resp = sessionManagement.getSessionsList(getSessionsListParams);
                
                listResponse = resp.getGetSessionsListResult();
                
                
                if (totalSessionsExpected == -1) {
                    // First time through, grab the expected total count.
                    totalSessionsExpected = listResponse.getTotalNumberResults();
                }
                
                Session[] folderSessions = listResponse.getResults().getSession();
                if (folderSessions != null && folderSessions.length > 0) {
                    allSessions.addAll(Arrays.asList(folderSessions));
                    responseCount += folderSessions.length;
                }
                
                page++;
            } while ((responseCount < totalSessionsExpected) && (page < this.maxPages));

            returnValue = new Session[allSessions.size()];
            returnValue = allSessions.toArray(returnValue);
        } catch (Exception e) {
            Utils.log(e, String.format("Error getting sessions (folder ID: %s, api user: %s).", folderId, apiUserKey));
            returnValue = null;
        }
        return returnValue;
    }

    // Gets all the folders associated with this course. Any folder we can't get
    // will return as null.
    public Folder[] getFolders() {
        Folder[] retVal = null;
        boolean userSynced = false;
        if (sessionGroupPublicIDs != null && sessionGroupPublicIDs.length > 0) {
            SessionManagementStub.AuthenticationInfo auth = new SessionManagementStub.AuthenticationInfo();
            auth.setAuthCode(apiUserAuthCode);
            auth.setPassword(null);
            auth.setUserKey(apiUserKey);
            
            try {
                syncUser(serverName, bbUserName);
                userSynced = true;
            } 
            catch (Exception e) {
                Utils.log(e, "syncUser call failed, will not attempt to get Panopto content without a succesful sync.");
            }
            
            if (userSynced) {
                GetFoldersById getFoldersByIdParams = new GetFoldersById();
                getFoldersByIdParams.setAuth(auth);
                
                SessionManagementStub.ArrayOfguid guidFolderIds = new SessionManagementStub.ArrayOfguid();
                
                for (int i = 0; i < sessionGroupPublicIDs.length; ++i) {
                    SessionManagementStub.Guid currFolderGuid = new SessionManagementStub.Guid();
                    currFolderGuid.setGuid(sessionGroupPublicIDs[i]);
                    guidFolderIds.addGuid(currFolderGuid);
                }
                
                getFoldersByIdParams.setFolderIds(guidFolderIds);
                
                try {
                    GetFoldersByIdResponse resp = sessionManagement.getFoldersById(getFoldersByIdParams);
                    retVal = resp.getGetFoldersByIdResult().getFolder();
                } 
                catch (Exception getFoldersException) {
                    // Failed. Could be because one of the folders has    
                    // been deleted. Get them one at a time.
                    if (getFoldersException.getMessage().contains("was not found")) {
                        retVal = new Folder[sessionGroupPublicIDs.length];  
                        for (int i = 0; i < sessionGroupPublicIDs.length; ++i) {    
                            try {   
                                getFoldersByIdParams = new GetFoldersById(); 
                                getFoldersByIdParams.setAuth(auth); 
    
                                guidFolderIds = new SessionManagementStub.ArrayOfguid();
                                SessionManagementStub.Guid currFolderGuid = new SessionManagementStub.Guid();   
                                currFolderGuid.setGuid(sessionGroupPublicIDs[i]);   
                                guidFolderIds.addGuid(currFolderGuid);  
    
                                getFoldersByIdParams.setFolderIds(guidFolderIds);   
    
                                GetFoldersByIdResponse resp = sessionManagement.getFoldersById(getFoldersByIdParams);   
                                retVal[i] = resp.getGetFoldersByIdResult().getFolder()[0];  
                            } 
                            catch (Exception getSingleFolderError) {    
                                Utils.log(getSingleFolderError,   
                                        String.format(  
                                                "Error getting folder(courseId: %s, courseTitle %s, folder ID: %s, api" 
                                                        + "user: %s).", 
                                                bbCourse.getId().toExternalString(), bbCourse.getTitle(),   
                                                sessionGroupPublicIDs[i], apiUserKey)); 
                                retVal[i] = null;   
                                if (!getSingleFolderError.getMessage().contains("was not found")) {
                                    return retVal;
                                }
                            }   
                        }
                    }
                }
            }
        }

        return retVal;
    }

    /**
     * Get the all of the public IDs for the folders provisioned to the course
     * 
     * @return String Array of folder public IDs provisioned to course (Guids)
     */
    private String[] getFolderIDs() {
        if (sessionGroupPublicIDs == null) {
            return emptyStringArray;
        }
        return sessionGroupPublicIDs;
    }

    /**
     * Get the all of the public IDs for the folders this course has access to
     * through a copy
     * 
     * @return String Array of folder public IDs course has copied permission to
     *         (Guids)
     */
    private String[] getCopiedFolderIDs() {
        if (copySessionGroupPublicIDs == null) {
            return emptyStringArray;
        }
        return copySessionGroupPublicIDs;
    }

    // Gets the urls to download the recorders
    public RecorderDownloadUrlResponse getRecorderDownloadUrls() {
        try {
            GetRecorderDownloadUrls getRecorderDownloadUrlsParams = new GetRecorderDownloadUrls();
            GetRecorderDownloadUrlsResponse resp = sessionManagement.getRecorderDownloadUrls(getRecorderDownloadUrlsParams);
            
            return resp.getGetRecorderDownloadUrlsResult();
        } catch (RemoteException e) {
            Utils.log(e, "Error getRecorderDownloadUrls");
            return null;
        }
    }

    // Used to sort folders alphabetically by name
    class FolderComparator implements Comparator<Folder> {
        public int compare(Folder f1, Folder f2) {
            return f1.getName().toLowerCase().compareTo(f2.getName().toLowerCase());
        }
    }

    // Gets all the Panopto folders the user has creator access to
    private Folder[] getFoldersWithCreatorAccess() {
        try {
            // First sync the user so his memberships will be up to date
            syncUser(serverName, bbUserName);

            // Next get the user's access details
            SessionManagementStub.AuthenticationInfo auth = new SessionManagementStub.AuthenticationInfo();
            auth.setAuthCode(apiUserAuthCode);
            auth.setPassword(null);
            auth.setUserKey(apiUserKey);
            
            HashSet<String> foldersWithCreatorAccess = new HashSet<String>();

            // Get all the folders
            int page = 0;
            int responseCount = 0;
            int totalFoldersExpected = -1;
            ListFoldersResponse listResponse;
            List<Folder> allFolders = new ArrayList<Folder>();
            do {
                SessionManagementStub.Pagination pagination = new SessionManagementStub.Pagination();
                pagination.setMaxNumberResults(this.perPage);
                pagination.setPageNumber(page);
                ListFoldersRequest foldersRequest = new ListFoldersRequest();
                foldersRequest.setPagination(pagination);
                foldersRequest.setParentFolderId(null);
                foldersRequest.setPublicOnly(false);
                foldersRequest.setSortIncreasing(true);
                foldersRequest.setSortBy(FolderSortField.Name);
                
                GetCreatorFoldersList getCreatorFoldersListParams = new GetCreatorFoldersList();
                getCreatorFoldersListParams.setAuth(auth);
                getCreatorFoldersListParams.setRequest(foldersRequest);
                getCreatorFoldersListParams.setSearchQuery(null);
                
                GetCreatorFoldersListResponse resp = getPanoptoSessionManagementSOAPService(serverName).getCreatorFoldersList(getCreatorFoldersListParams);

                listResponse = resp.getGetCreatorFoldersListResult();
                
                allFolders.addAll(Arrays.asList(listResponse.getResults().getFolder()));

                if (totalFoldersExpected == -1) {
                    // First time through, grab the expected total count.
                    totalFoldersExpected = listResponse.getTotalNumberResults();
                }
                Folder[] returnedFolders = listResponse.getResults().getFolder();

                // Log which folders we got back. foldersWithCreatorAccess,
                // folderIdList, and returnedFolders are all
                // just in place for logging.
                foldersWithCreatorAccess = new HashSet<String>();
                for (Folder folder : returnedFolders) {
                    foldersWithCreatorAccess.add(folder.getId().getGuid());
                }
                String[] folderIdList = foldersWithCreatorAccess.toArray(new String[0]);
                Utils.logVerbose(String.format(
                        "getFoldersWithCreatorAccess. User: %s, page: %d, returned from getCreatorFoldersList: %s",
                        bbUserName, page, Utils.encodeArrayOfStrings(folderIdList)));

                responseCount += returnedFolders.length;
                page++;
            } while ((responseCount < totalFoldersExpected) && (page < this.maxPages));

            Utils.logVerbose(
                    String.format("Expected %d folders, returned %d folders", totalFoldersExpected, allFolders.size()));

            return allFolders.toArray(new Folder[allFolders.size()]);
        } catch (RemoteException e) {
            Utils.log(e, String.format("Error getting folders with creator access (server: %s, apiUserKey: %s).",
                    serverName, apiUserKey));
            return null;
        }
    }

    // Gets all the public folders from the server
    private Folder[] getPublicFolders() {
        try {
            SessionManagementStub.AuthenticationInfo auth = new SessionManagementStub.AuthenticationInfo();
            auth.setAuthCode(apiUserAuthCode);
            auth.setPassword(null);
            auth.setUserKey(apiUserKey);
            
            HashSet<String> publicFolders = new HashSet<String>();
            // Get all the folders

            int page = 0;
            int responseCount = 0;
            int totalFoldersExpected = -1;
            ListFoldersResponse listResponse;
            List<Folder> allFolders = new ArrayList<Folder>();


            do {
                ListFoldersRequest request = new ListFoldersRequest();
                request.setPublicOnly(true);
                SessionManagementStub.Pagination pagination = new SessionManagementStub.Pagination();
                pagination.setMaxNumberResults(this.perPage);
                pagination.setPageNumber(page);
                request.setPagination(pagination);
                
                GetFoldersList getFoldersListParams = new GetFoldersList();
                getFoldersListParams.setAuth(auth);
                getFoldersListParams.setRequest(request);
                getFoldersListParams.setSearchQuery(null);

                GetFoldersListResponse resp = getPanoptoSessionManagementSOAPService(serverName).getFoldersList(getFoldersListParams);
                
                listResponse = resp.getGetFoldersListResult();
                
                if (totalFoldersExpected == -1) {
                    // First time through, grab the expected total count.
                    totalFoldersExpected = listResponse.getTotalNumberResults();
                }
                

                Folder[] currFolders = listResponse.getResults().getFolder();
                if (totalFoldersExpected != 0 && currFolders != null) {
                    allFolders.addAll(Arrays.asList(currFolders));
                    
	                // Log which folders we got back. foldersWithCreatorAccess,
	                // folderIdList, and returnedFolders are all
	                // just in place for logging.
	                publicFolders = new HashSet<String>();
	                for (Folder folder : currFolders) {
	                    publicFolders.add(folder.getId().getGuid());
	                }
	                
	                String[] folderIdList = publicFolders.toArray(new String[0]);
	                Utils.logVerbose(
	                        String.format("getPublicFolders. User: %s, page: %d, returned from getPublicFolders: %s",
	                                bbUserName, page, Utils.encodeArrayOfStrings(folderIdList)));
	
	                responseCount += currFolders.length;
	                page++;
                }
            } while ((totalFoldersExpected != 0) && (responseCount < totalFoldersExpected) && (page < this.maxPages));
            
            Utils.logVerbose(
                    String.format("Expected %d folders, returned %d folders", totalFoldersExpected, allFolders.size()));
            return allFolders.toArray(new Folder[allFolders.size()]);

        } catch (RemoteException e) {
            Utils.log(e, String.format("Error getting public folders (server: %s, apiUserKey: %s).", serverName,
                    apiUserKey));
            return null;
        }
    }

    // Generates the list of selected folders for the course config page's
    // listbox
    public String generateCourseConfigSelectedFoldersOptionsHTML() {
        StringBuffer result = new StringBuffer();
        if (sessionGroupDisplayNames != null) {
            for (int i = 0; i < sessionGroupDisplayNames.length; i++) {
                result.append("<option");
                result.append(" value='" + sessionGroupPublicIDs[i] + "'");
                result.append(">");
                result.append(Utils.escapeHTML(sessionGroupDisplayNames[i]));
                result.append("</option>\n");
            }
        }

        return result.toString();
    }

    /**
     * The HTML generation code for copied Panopto folders so that it can be
     * properly displayed
     * 
     * @return HTML string of all copied folders as <options> tags
     */
    public String generateCourseConfigCopyFoldersOptionsHTML() {
        StringBuffer result = new StringBuffer();
        if (ValidateCourseCopyValues()) {
            for (int i = 0; i < copySessionGroupDisplayNames.length; i++) {
                result.append("<option");
                result.append(" value='" + copySessionGroupPublicIDs[i] + "'");
                result.append(">");
                result.append(Utils.escapeHTML(copySessionGroupDisplayNames[i]));
                result.append("</option>\n");
            }
        }

        return result.toString();
    }
    
    public boolean ValidateCourseCopyValues() {

        this.copySessionGroupPublicIDs = getCourseRegistryEntries(copySessionGroupIDsRegistryKey);
        this.copySessionGroupDisplayNames = getCourseRegistryEntries(copySessionGroupDisplayNamesRegistryKey);

        // Check that the list of Ids and names for course imports are valid. They must both be the
        // same length
        if ((this.copySessionGroupPublicIDs == null && this.copySessionGroupDisplayNames != null)
                || (this.copySessionGroupPublicIDs != null && this.copySessionGroupDisplayNames == null)
                || (this.copySessionGroupPublicIDs != null && this.copySessionGroupPublicIDs.length != this.copySessionGroupDisplayNames.length)) {
            Utils.log(String.format(
                    "Invalid course import registry settings. Reseting course imports to null. copySessionGroupPublicIDs = %s"
                            + "copySessionGroupDisplayNames = %s",
                    Utils.encodeArrayOfStrings(this.copySessionGroupPublicIDs),
                    Utils.encodeArrayOfStrings(this.copySessionGroupDisplayNames)));

            this.copySessionGroupPublicIDs = null;
            this.copySessionGroupDisplayNames = null;
            setCourseRegistryEntries(copySessionGroupIDsRegistryKey, null);
            setCourseRegistryEntries(copySessionGroupDisplayNamesRegistryKey, null);
            
            return false;
        } else if (this.copySessionGroupPublicIDs == null && this.copySessionGroupDisplayNames == null) {
            return false;
        }
        
        return true;
    }

    // Generates the list of available folders for the course config page's
    // listbox
    public String generateCourseConfigAvailableFoldersOptionsHTML() {
        StringBuffer result = new StringBuffer();

        // Get all the folder the user has access to
        Folder[] folders = getFoldersWithCreatorAccess();

        // Sort them by name
        ArrayList<Folder> sortedFolders = new ArrayList<Folder>();
        sortedFolders.addAll(Arrays.asList(folders));
        Collections.sort(sortedFolders, new FolderComparator());

        // Build a hash of the currently selected folders so we can quickly
        // exclude them
        HashSet<String> currentFolderIds = new HashSet<String>();
        currentFolderIds.addAll(Arrays.asList(sessionGroupPublicIDs));

        // Finally write out the options in sorted order
        for (Folder folder : sortedFolders) {
            if (!currentFolderIds.contains(folder.getId().toString())) {
                result.append("<option");
                result.append(" value='" + folder.getId() + "'");
                result.append(">");
                result.append(Utils.escapeHTML(folder.getName()));
                result.append("</option>\n");
            }
        }

        return result.toString();
    }

    // Looks up the display string for a given folderID. The folderId must be
    // already mapped with the course
    public String getFolderDisplayString(String folderId) {
        if (folderId != null) {
            for (int i = 0; i < sessionGroupDisplayNames.length; i++) {
                if (sessionGroupPublicIDs[i].equals(folderId)) {
                    return sessionGroupDisplayNames[i];
                }
            }
        }

        return null;
    }

    // Generate <option>s for available folders. Used by Item_Create to select a
    // folder
    public String generateFolderOptionsHTML(String folderId) {
        StringBuffer result = new StringBuffer();

        Folder[] publicFolders = this.getPublicFolders();
        Folder[] creatorFolders = Utils.pluginSettings.getVideoLinkToolIncludeCreatorFolders() ? this.getFoldersWithCreatorAccess() : null;
        
        int numFolders = getNumberOfFolders();
        numFolders += publicFolders != null ? publicFolders.length : 0;
        if (numFolders == 0) {
            result.append("<option value=''>-- No Folders Available --</option>");
        } else {
            result.append("<option value=''>-- Select a Folder --</option>");

            // Only use option groups if we have elements in both groups
            boolean useOptionalGroups = sessionGroupDisplayNames != null && sessionGroupDisplayNames.length > 0
                    && ((publicFolders != null && publicFolders.length > 0) || 
                        (creatorFolders != null && creatorFolders.length > 0));

            // Add all the mapped folders
            if (sessionGroupDisplayNames != null && sessionGroupDisplayNames.length > 0) {
                if (useOptionalGroups) {
                    result.append("<optgroup label='Mapped Folders'>\n");
                }
                for (int i = 0; i < sessionGroupDisplayNames.length; i++) {
                    String strDisplayName = Utils.escapeHTML(sessionGroupDisplayNames[i]);
                    String strID = sessionGroupPublicIDs[i];

                    result.append("<option");
                    result.append(" value='" + strID + "'");
                    if (strID.equals(folderId)) {
                        result.append(" SELECTED");
                    }
                    result.append(">");
                    result.append(strDisplayName);
                    result.append("</option>\n");
                }
                if (useOptionalGroups) {
                    result.append("</optgroup>\n");
                }
            }

            // Add all the public folders
            if (publicFolders != null && publicFolders.length > 0) {
                if (useOptionalGroups) {
                    result.append("<optgroup label='Public Folders'>\n");
                }
                for (int i = 0; i < publicFolders.length; i++) {
                    String strDisplayName = Utils.escapeHTML(publicFolders[i].getName());
                    String strID = publicFolders[i].getId().getGuid();

                    result.append("<option");
                    result.append(" value='" + strID + "'");
                    if (strID.equals(folderId)) {
                        result.append(" SELECTED");
                    }
                    result.append(">");
                    result.append(strDisplayName);
                    result.append("</option>\n");
                }
                if (useOptionalGroups) {
                    result.append("</optgroup>\n");
                }
            }

            // Add all the creator folders
            if (creatorFolders != null && creatorFolders.length > 0) {
                if (useOptionalGroups) {
                    result.append("<optgroup label='Creator Folders'>\n");
                }
                for (int i = 0; i < creatorFolders.length; i++) {
                    String strDisplayName = Utils.escapeHTML(creatorFolders[i].getName());
                    String strID = creatorFolders[i].getId().getGuid();

                    result.append("<option");
                    result.append(" value='" + strID + "'");
                    if (strID.equals(folderId)) {
                        result.append(" SELECTED");
                    }
                    result.append(">");
                    result.append(strDisplayName);
                    result.append("</option>\n");
                }
                if (useOptionalGroups) {
                    result.append("</optgroup>\n");
                }
            }
        }

        return result.toString();
    }

    // Generate <option>s for available sessions. Used by Item_Create to select
    // a session once a fodler is selected
    public String generateSessionOptionsHTML(String folderID) {
        StringBuffer result = new StringBuffer();

        if (folderID == null || folderID == "") {
            result.append("<option value=''>-- Please select a folder first --</option>\n");
        } else {
            Utils.log("Folder ID: " + folderID);
            Session[] sessions = this.getSessions(folderID);
            if (sessions != null) {
                if (sessions.length == 0) {
                    result.append("<option value=''>-- The folder is empty --</option>");
                } else {
                    result.append("<option value=''>-- Select a Lecture --</option>");

                    for (Session session : sessions) {
                        String strDisplayName = Utils.escapeHTML(session.getName());

                        result.append("<option");
                        result.append(" value='" + session.getViewerUrl() + "&instance=" + Utils.pluginSettings.getInstanceName() + "'");
                        result.append(">");
                        result.append(strDisplayName);
                        result.append("</option>\n");
                    }
                }
            } else {
                result.append("<option value=''>!! Unable to retrieve lecture list !!</option>\n");
            }
        }

        return result.toString();
    }

    // Returns true if the user is an instructor for this course
    public boolean IsInstructor() {
        return isInstructor;
    }

    public boolean canAddLinks() {
        return canAddLinks;
    }

    // Insert a content item in the current course with a link to the specified
    // delivery.
    public LinkAddedResult addBlackboardContentItem(String content_id, String lectureUrl, String title,
            String description) {
        Session[] sessionArray;
        LinkAddedResult linkAddedResult = LinkAddedResult.FAILURE;
        try {
            Utils.pluginSettings = new Settings();

            // Get folder ID from URL param and add to an array to pass into
            // updateFoldersAvailabilityStartSettings();
            Map<String, String> urlParams = this.getQueryMap(lectureUrl);
            String sessionID = urlParams.get("id");
            
            SessionManagementStub.ArrayOfguid sessionIds = new SessionManagementStub.ArrayOfguid();
            SessionManagementStub.Guid sessionId = new SessionManagementStub.Guid();
            sessionId.setGuid(sessionID);
            sessionIds.addGuid(sessionId);

            // retrieve the Db persistence manager from the persistence service
            BbPersistenceManager bbPm = PersistenceServiceFactory.getInstance().getDbPersistenceManager();

            // Generate AuthenticationInfo for calling availability window
            // update method.
            SessionManagementStub.AuthenticationInfo auth = new SessionManagementStub.AuthenticationInfo();
            auth.setAuthCode(apiUserAuthCode);
            auth.setPassword(null);
            auth.setUserKey(apiUserKey);
            
            // Get user's blackboard ID from their username
            UserDbLoader userLoader = (UserDbLoader) bbPm.getLoader(UserDbLoader.TYPE);
            User user = userLoader.loadByUserName(bbUserName);
            Id bbUserId = user.getId();

            // Load the user's membership in the current course to get their
            // role
            CourseMembershipDbLoader membershipLoader = (CourseMembershipDbLoader) bbPm
                    .getLoader(CourseMembershipDbLoader.TYPE);
            CourseMembership usersCourseMembership = membershipLoader.loadByCourseAndUserId(bbCourse.getId(), bbUserId);
            Role userRole = usersCourseMembership.getRole();

            // Determine if current user has creator access to session.
            boolean isCreator = (isInstructorRole(userRole)
                    || (isTARole(userRole) && Utils.pluginSettings.getGrantTACreator()));

            if (isCreator) {
                // isInAvailabilityWindow defaults to true, so sessions will be
                // added if calls to the availability
                // window API cannot be made.
               PanoptoAvailabilityWindow.AvailabilityState availabilityState = PanoptoAvailabilityWindow.AvailabilityState.Unknown;
               
               try {
                    // If user is a creator on Panopto, check if the session
                    // is in its availability window.
                    availabilityState = this.checkSessionAvailabilityState(sessionID, auth);
                } catch (Exception e) {
                    // Problem getting availability window information from
                    // the API. Do not add the session to the
                    // course.
                    availabilityState = PanoptoAvailabilityWindow.AvailabilityState.Unknown;
                    Utils.log(e, "Error getting availability information for sessions from server.");
                }
               
                if (availabilityState == PanoptoAvailabilityWindow.AvailabilityState.Available) {
                    // If the session is in its availability window. add it to
                    // the course and return success.
                    addSessionLinkToCourse(content_id, lectureUrl, title, description, bbPm);
                    linkAddedResult = LinkAddedResult.SUCCESS;
                } else if (availabilityState != PanoptoAvailabilityWindow.AvailabilityState.Unknown) {
                    // We successfully determined the availability but it's
                    // either unavailable or unpublished. Try to
                    // make the session available immediately.
                    try {
                        UpdateSessionsAvailabilityStartSettings updateSessionsAvailabilityStartSettingsParams = new UpdateSessionsAvailabilityStartSettings();
                        
                        
                        updateSessionsAvailabilityStartSettingsParams.setAuth(auth);
                        updateSessionsAvailabilityStartSettingsParams.setSessionIds(sessionIds);
                        updateSessionsAvailabilityStartSettingsParams.setSettingType(SessionStartSettingType.Immediately);
                        updateSessionsAvailabilityStartSettingsParams.setStartDate(null);
                        sessionManagement.updateSessionsAvailabilityStartSettings(updateSessionsAvailabilityStartSettingsParams);

                        // The session is now available, add the session and
                        // return success
                        addSessionLinkToCourse(content_id, lectureUrl, title, description, bbPm);
                        linkAddedResult = LinkAddedResult.SUCCESS;
                    } catch (Exception e) {
                        if (availabilityState == PanoptoAvailabilityWindow.AvailabilityState.Unpublished) {
                            // The session needs publishing, but our attempt to
                            // publish failed. We must not have publish
                            // rights
                            linkAddedResult = LinkAddedResult.NOTPUBLISHER;
                        }
                    }
                }
            } else {
                // If the user does not have a creator role, first call API to
                // determine whether session is already
                // available.
                GetSessionsById getSessionsByIdParams = new GetSessionsById();
                getSessionsByIdParams.setAuth(auth);
                getSessionsByIdParams.setSessionIds(sessionIds);
                
                
                sessionArray = sessionManagement.getSessionsById(getSessionsByIdParams).getGetSessionsByIdResult().getSession();
                if (sessionArray.length < 1) {
                    // If no session is returned, it means the session is not in
                    // its availability window, and the
                    // current user cannot make it, available. Return failure
                    // indicating the user must ask a creator to
                    // make the session available.
                    linkAddedResult = LinkAddedResult.NOTCREATOR;
                } else {
                    // If the session is currently in its availability window,
                    // it can be added to the course without
                    // having to make any API call. Return success.
                    addSessionLinkToCourse(content_id, lectureUrl, title, description, bbPm);
                    linkAddedResult = LinkAddedResult.SUCCESS;
                }
            }
        } catch (Exception e) {
            // General error when trying to add a session to a course. Print
            // details to log.
            Utils.log(e,
                    String.format(
                            "Error adding content item (content ID: %s, lecture Url: %s, title: %s, description: %s).",
                            content_id, lectureUrl, title, description));
            linkAddedResult = LinkAddedResult.FAILURE;
        }
        return linkAddedResult;
    }

    // Determine the availability state of a session
    private PanoptoAvailabilityWindow.AvailabilityState checkSessionAvailabilityState(String sessionId,
            SessionManagementStub.AuthenticationInfo auth) throws RemoteException {
        SessionManagementStub.ArrayOfguid sessionIds = new SessionManagementStub.ArrayOfguid();
        SessionManagementStub.Guid sessionGuid = new SessionManagementStub.Guid();
        sessionGuid.setGuid(sessionId);
        sessionIds.addGuid(sessionGuid);
        
        // Get availability window settings for the session.
        GetSessionsAvailabilitySettings getSessionsAvailabilitySettingsParams = new GetSessionsAvailabilitySettings();
        
        getSessionsAvailabilitySettingsParams.setAuth(auth);
        getSessionsAvailabilitySettingsParams.setSessionIds(sessionIds);
        
        SessionAvailabilitySettings sessionSettings = sessionManagement
                .getSessionsAvailabilitySettings(getSessionsAvailabilitySettingsParams)
                .getGetSessionsAvailabilitySettingsResult()
                .getResults()
                .getSessionAvailabilitySettings()[0];
        FolderAvailabilitySettings folderSettings = null;

        if (PanoptoAvailabilityWindow.isFolderRequiredForSessionAvailability(sessionSettings)) {
            // Folder availability settings are also needed to determine whether
            // the session is available. Load the
            // session data to get the folder, then get the folder availability
            GetSessionsById getSessionsByIdParams = new GetSessionsById();
            getSessionsByIdParams.setAuth(auth);
            getSessionsByIdParams.setSessionIds(sessionIds);
            
            Session session = sessionManagement.getSessionsById(getSessionsByIdParams).getGetSessionsByIdResult().getSession()[0];
            SessionManagementStub.ArrayOfguid currSessionGuids = new SessionManagementStub.ArrayOfguid();
            currSessionGuids.addGuid(session.getFolderId());
            
            try {
                GetFoldersAvailabilitySettings getFoldersAvailabilitySettingsParams = new GetFoldersAvailabilitySettings();
                getFoldersAvailabilitySettingsParams.setAuth(auth);
                getFoldersAvailabilitySettingsParams.setFolderIds(currSessionGuids);
                
                folderSettings = sessionManagement
                    .getFoldersAvailabilitySettings(getFoldersAvailabilitySettingsParams).getGetFoldersAvailabilitySettingsResult().getResults().getFolderAvailabilitySettings()[0];
            } catch (Exception e) {
                Utils.logVerbose("first attempt at getFoldersAvailabilitySettings failed, calling syncUser");
                
                // If the user has a role that bypasses normal syncing e.g. videographer then we will need to sync them before getting availability settings.
                syncUser();
                
                GetFoldersAvailabilitySettings getFoldersAvailabilitySettingsParams = new GetFoldersAvailabilitySettings();
                getFoldersAvailabilitySettingsParams.setAuth(auth);
                getFoldersAvailabilitySettingsParams.setFolderIds(currSessionGuids);
                
                folderSettings = sessionManagement
                    .getFoldersAvailabilitySettings(getFoldersAvailabilitySettingsParams).getGetFoldersAvailabilitySettingsResult().getResults().getFolderAvailabilitySettings()[0];
            }
        }
        return PanoptoAvailabilityWindow.getSessionAvailability(sessionSettings, folderSettings);
    }

    // Adds a link to a Panopto session to the content area of the current
    // Blackboard course.
    private void addSessionLinkToCourse(String content_id, String lectureUrl, String title, String description,
            BbPersistenceManager bbPm) throws PersistenceException, ValidationException {
        // Create a course document and set all desired attributes
        Content content = new Content();
        content.setTitle(title);
        content.setBody(new FormattedText(description, FormattedText.Type.HTML));
        content.setUrl(lectureUrl);
        content.setRenderType(Content.RenderType.URL);
        content.setLaunchInNewWindow(true);
        content.setContentHandler("hyperlink/coursecast");

        // Set course and parent content IDs (required)
        Id parentId = bbPm.generateId(Content.DATA_TYPE, content_id);
        content.setParentId(parentId);

        Id courseId = bbCourse.getId();
        content.setCourseId(courseId);

        // retrieve the content persister and persist the content item
        ContentDbPersister persister = (ContentDbPersister) bbPm.getPersister(ContentDbPersister.TYPE);
        persister.persist(content);
    }

    // Returns map of url's query parameters and their values. Used for getting
    // session ID for session to make
    // available.
    public Map<String, String> getQueryMap(String url) {
        Map<String, String> map = null;
        String[] splitURL = url.split("\\?");
        if (splitURL[1] != null && !splitURL[1].isEmpty()) {
            String[] params = splitURL[1].split("&");
            map = new HashMap<String, String>();
            for (String param : params) {
                String name = param.split("=")[0];
                String value = param.split("=")[1];
                map.put(name, value);
            }
        }
        return map;
    }

    // Sync's a user with Panopto so that his course memberships are up to date.
    public void syncUser() {
        PanoptoData.syncUser(serverName, bbUserName);
    }

    // Sync's a user with Panopto so that his course memberships are up to date.
    // Note: This method calculates External IDs for Panopto, this is not
    // standard practice as we are trying to keep all
    // string calculation code on the Panopto side.
    public static void syncUser(String serverName, String bbUserName) {
        String apiUserKey = Utils.decorateBlackboardUserName(bbUserName);
        String apiUserAuthCode = Utils.generateAuthCode(serverName, apiUserKey + "@" + serverName);
        UserManagementStub.AuthenticationInfo auth = new UserManagementStub.AuthenticationInfo();
        auth.setAuthCode(apiUserAuthCode);
        auth.setPassword(null);
        auth.setUserKey(apiUserKey);
        
        try {
            // Load the user's profile info
            BbPersistenceManager bbPm = PersistenceServiceFactory.getInstance().getDbPersistenceManager();
            UserDbLoader userLoader = (UserDbLoader) bbPm.getLoader(UserDbLoader.TYPE);
            User user = userLoader.loadByUserName(bbUserName);
            Id bbUserId = user.getId();

            CourseDbLoader courseLoader = (CourseDbLoader) bbPm.getLoader(CourseDbLoader.TYPE);
            CourseMembershipDbLoader courseMembershipLoader = (CourseMembershipDbLoader) bbPm
                    .getLoader(CourseMembershipDbLoader.TYPE);
            List<Course> instructorCourses = new ArrayList<Course>();
            List<Course> studentCourses = new ArrayList<Course>();
            List<Course> taCourses = new ArrayList<Course>();

            List<CourseMembership> allCourseMemberships = courseMembershipLoader.loadByUserId(bbUserId);

            Course currentCourse;

            Utils.pluginSettings = new Settings();
            for (CourseMembership membership : allCourseMemberships) {
                try {
                    // This is membership availability, this user was specifically marked unavailable for this membership. Affects all roles. 
                    if (!Utils.pluginSettings.getSyncAvailabilityStatus() || membership.getIsAvailable()) {
                        Role membershipRole = membership.getRole();
                        currentCourse = courseLoader.loadById(membership.getCourseId());
    
                        if (isInstructorRole(membershipRole)) {
                            instructorCourses.add(currentCourse);
                        } else if (isTARole(membershipRole)) {
                            taCourses.add(currentCourse);
                        } else if (isStudentRole(membershipRole)) {
                            studentCourses.add(currentCourse);
                        } else {
                            // This is for a user with no role for this course.
                            // This is not added to any course list.
                        }
                    } else {
                        Utils.logVerbose(String.format("The membership associated with the course with id %1$s is unavailable.",
                                membership.getCourseId()));
                    }
                } catch (KeyNotFoundException e) {
                    Utils.log(String.format("The course with id %1$s either does not exist or is unavailable.",
                            membership.getCourseId()));
                } catch (Exception ex) {
                    Utils.log(ex, String.format("Failed to load course %1$s for membership %2$s",
                            membership.getCourseId(), membership.getId()));
                }
            }

            ArrayList<String> externalGroupIds = new ArrayList<String>();
            StringBuilder courseList = new StringBuilder();
            for (Course course : studentCourses) {
                Calendar startDate = course.getStartDate();
                Calendar endDate = course.getEndDate();
                
                // Just to be safe let's make sure the current calendar is in the right timezone
                Calendar currentDate;
                boolean isAfterStartDate = true;
                boolean isBeforeEndDate = true;
                
                // we need to include the start and end date so offset the dates we are checking by 1 each.
                if (startDate != null) {
                    currentDate = Calendar.getInstance(startDate.getTimeZone());
                    isAfterStartDate = currentDate.after(startDate);
                }
                
                if (endDate != null) {
                    currentDate = Calendar.getInstance(endDate.getTimeZone());
                    isBeforeEndDate = currentDate.before(endDate);
                }
                
                // This is course based availability, only reason this is restricting students is because both instructors and TA's have access to unavailable courses they are enrolled in. 
                // Course availability only affects students in blackboard.
                if (!Utils.pluginSettings.getSyncAvailabilityStatus() || 
                        (course.getIsAvailable() && (isAfterStartDate && isBeforeEndDate))) {
                    courseList.append(course.getTitle());
                    Id courseId = course.getId();
                    String courseServerName = getCourseRegistryEntry(courseId, hostnameRegistryKey);
                    if (courseIsCorrectlyProvisioned(courseId, serverName, courseServerName, courseList)) {
                        String groupName = Utils.decorateBlackboardCourseID(courseId.toExternalString()) + "_viewers";
                        externalGroupIds.add(groupName);
                        courseList.append('(' + groupName + ')');
                    }
    
                    courseList.append(';');
                }
            }
            Utils.logVerbose(
                    String.format("Sync'ing user %s group membership to server %s. Student group membership: %s",
                            bbUserName, serverName, courseList.toString()));

            courseList = new StringBuilder();
            for (Course course : instructorCourses) {
                courseList.append(course.getTitle());
                Id courseId = course.getId();
                String courseServerName = getCourseRegistryEntry(courseId, hostnameRegistryKey);
                if (courseIsCorrectlyProvisioned(courseId, serverName, courseServerName, courseList)) {
                    String groupName = Utils.decorateBlackboardCourseID(courseId.toExternalString()) + "_creators";
                    externalGroupIds.add(groupName);
                    courseList.append('(' + groupName + ')');
                }

                courseList.append(';');
            }
            Utils.logVerbose(
                    String.format("Sync'ing user %s group membership to server %s. Instructor group membership: %s",
                            bbUserName, serverName, courseList.toString()));

            courseList = new StringBuilder();
            for (Course course : taCourses) {
                courseList.append(course.getTitle());
                Id courseId = course.getId();
                String courseServerName = getCourseRegistryEntry(courseId, hostnameRegistryKey);
                if (courseIsCorrectlyProvisioned(courseId, serverName, courseServerName, courseList)) {
                    String groupName;
                    if (Utils.pluginSettings.getGrantTACreator()) {
                        groupName = Utils.decorateBlackboardCourseID(courseId.toExternalString()) + "_creators";
                    } else {
                        groupName = Utils.decorateBlackboardCourseID(courseId.toExternalString()) + "_viewers";
                    }
                    externalGroupIds.add(groupName);
                    courseList.append('(' + groupName + ')');
                }

                courseList.append(';');
            }

            Utils.logVerbose(String.format("Sync'ing user %s group membership to server %s. TA group membership: %s",
                    bbUserName, serverName, courseList.toString()));
            
            SyncExternalUser syncExternalUserParams = new SyncExternalUser();
            syncExternalUserParams.setAuth(auth);
            syncExternalUserParams.setEmail(user.getEmailAddress());
            syncExternalUserParams.setFirstName(user.getGivenName());
            syncExternalUserParams.setLastName(user.getFamilyName());
            syncExternalUserParams.setEmailSessionNotifications(Utils.pluginSettings.getMailLectureNotifications());
            
            UserManagementStub.ArrayOfstring externalGroupIdsArray = new UserManagementStub.ArrayOfstring();
            externalGroupIdsArray.setString(externalGroupIds.toArray(new String[0]));
            
            syncExternalUserParams.setExternalGroupIds(externalGroupIdsArray);
            
            getPanoptoUserManagementSOAPService(serverName).syncExternalUser(syncExternalUserParams);
        } catch (Exception e) {
            Utils.log(e, String.format("Error sync'ing user's group membership (server: %s, user: %s).", serverName,
                    bbUserName));
        }
    }

    /**
     * Returns true if the course with the given id is provisioned to the
     * correct server
     * 
     * Checks both that the course server name in the registry entry is filled
     * in and correct.
     * 
     * Will also fill in any information to the courseList string builder.
     * 
     * @param courseId
     *            Course that we are checking if is provisioned
     * @param serverName
     *            Server name we want to know if course is provisioned against
     * @param courseServerName
     *            Server that course might be provisioned against
     * @param courseList
     *            StringBuilder that contains the notes for what happened
     * @return True if the course is provisioned
     */
    private static Boolean courseIsCorrectlyProvisioned(Id courseId, String serverName, String courseServerName,
            StringBuilder courseList) {

        Boolean result = false;
        if (courseServerName != null) {
            if (!courseServerName.equalsIgnoreCase(serverName)) {
                courseList.append("(provisioned against " + courseServerName + ")");
            } else {
                result = true;
            }
        }

        return result;
    }
    
    /** 
     * Attempts to report basic integration info to the panopto server, called after succesful reprovisioning.
     * @param auth authentication info of user performing the call.
     */
    private void reportIntegrationInfo() {

        try {
            if (this.serverVersion == null) {
                this.serverVersion = getServerVersion();
            }
            
            if (PanoptoVersions.canReportIntegrationInfo(this.serverVersion)) {
                // If one is pluginVersion is null set both the plugIn and platform versions.
                if (plugInVersion == null || platformVersion == null) {
                    PlugInManager plugInManager = PlugInManagerFactory.getInstance();
                    List<PlugIn> plugins = plugInManager.getPlugIns();
                    
                    for (PlugIn plugIn : plugins) {
                        if (plugIn.getName().equals("Panopto Connector")) {
                            plugInVersion = plugIn.getVersion().toString();
                            break;
                        }
                    }
                    
                    platformVersion = plugInManager.getPlatformVersion().toString();
                }
                
                AuthStub.AuthenticationInfo auth = new AuthStub.AuthenticationInfo();
                auth.setAuthCode(apiUserAuthCode);
                auth.setPassword(null);
                auth.setUserKey(apiUserKey);
                
                AuthStub authStub = getPanoptoAuthSOAPService(serverName);
                ReportIntegrationInfo reportIntegrationInfoParams = new ReportIntegrationInfo();
                reportIntegrationInfoParams.setAuth(auth);
                reportIntegrationInfoParams.setIdProviderName(Utils.pluginSettings.getInstanceName());
                reportIntegrationInfoParams.setModuleVersion(plugInVersion);
                reportIntegrationInfoParams.setTargetPlatformVersion(platformVersion);
                
                authStub.reportIntegrationInfo(reportIntegrationInfoParams);
            } else {
                Utils.log(
                    String.format("Newer version of Panopto Required to be able to report integration info. Current version: %s, Minimum version: %s",
                    this.serverVersion.toString(),
                    PanoptoVersions.V5_4)
                );
            }
        } catch (Exception ex) {
            Utils.log(ex, String.format("Error reporting Integration Info to server: %s", serverName));
        }
    }

    // Updates the course so it is mapped to the given folders
    public boolean reprovisionCourse(String[] folderIds) {
        try {
            
            // Reset the last provisioning error since this provisioning is new
            this.previousProvisioningError = "";
            
            Utils.pluginSettings = new Settings();

            String externalCourseId = Utils.decorateBlackboardCourseID(bbCourse.getId().toExternalString());
            String fullName = bbCourse.getCourseId() + ": " + bbCourse.getTitle();

            // Provision the course
            SessionManagementStub.AuthenticationInfo auth = new SessionManagementStub.AuthenticationInfo();
            auth.setAuthCode(apiUserAuthCode);
            auth.setPassword(null);
            auth.setUserKey(apiUserKey);
            
            SetExternalCourseAccess setExternalCourseAccessParams = new SetExternalCourseAccess();
            setExternalCourseAccessParams.setAuth(auth);
            setExternalCourseAccessParams.setExternalId(externalCourseId);
            setExternalCourseAccessParams.setName(fullName);
            
            SessionManagementStub.ArrayOfguid folderGuids = new SessionManagementStub.ArrayOfguid();
            for(int i = 0; i < folderIds.length; ++i) {
                SessionManagementStub.Guid folderGuid = new SessionManagementStub.Guid();
                folderGuid.setGuid(folderIds[i]);
                folderGuids.addGuid(folderGuid);
            }
            setExternalCourseAccessParams.setFolderIds(folderGuids);
            
            Folder[] folders = getPanoptoSessionManagementSOAPService(serverName)
                .setExternalCourseAccess(setExternalCourseAccessParams).getSetExternalCourseAccessResult().getFolder();
            
            if (folders == null || folders.length == 0) {
            	Utils.log("No folders returned from setExternalCourseAccess while re-provisioning. Could the folder have been deleted in Panopto?");
            	
            	// This string needs to be wrapped in quotes to be used in the Alert properly. 
            	this.previousProvisioningError = "\"Failed to reprovision course, could the target folder have been deleted in Panopto?\"";
            	return false; 
            }
            
            updateCourseFolders(folders);
            
            // Once a course is unprovisioned/reprovision all imports will be lost and need to be reinitialized.
            if (Utils.pluginSettings.getCourseCopyEnabled()) {
                reinitializeImports(); 
            }
            
            this.reportIntegrationInfo();

            // Add menu item if setting is enabled
            if (Utils.pluginSettings.getInsertLinkOnProvision()) {
                addCourseMenuLink();
            }
        } catch (Exception e) {
            String folderString = Utils.encodeArrayOfStrings(folderIds);
            String exceptionMessage = e.getMessage();
            
            if (folderString == null) {
                folderString = "empty";
            }
            
            // Save the last error so we can display it to the user if they are provisioning from the course_configure page.
            if (exceptionMessage != null) {
                this.previousProvisioningError = exceptionMessage;
            }
            
            Utils.log(e, String.format("Error reprovisioning course (id: %s, server: %s, user: %s, folders: %s).",
                    bbCourse.getId().toExternalString(), serverName, bbUserName, folderString));
            return false;
        }
        
        return true;
    }
    
    // Removes the external context from all Panopto folders and assignment folders) associated with this course.
    public boolean unprovisionExternalCourse() {
        boolean result;
        this.previousProvisioningError = "";
        
        SessionManagementStub.AuthenticationInfo auth = new SessionManagementStub.AuthenticationInfo();
        auth.setAuthCode(apiUserAuthCode);
        auth.setPassword(null);
        auth.setUserKey(apiUserKey);
        
        try {
            UnprovisionExternalCourse unprovisionExternalCourseParams = new UnprovisionExternalCourse();
            unprovisionExternalCourseParams.setAuth(auth);
            unprovisionExternalCourseParams.setExternalContextId(bbCourse.getId().toExternalString());
            result = getPanoptoSessionManagementSOAPService(serverName).unprovisionExternalCourse(unprovisionExternalCourseParams).getUnprovisionExternalCourseResult();
            
            if (!result) {
                Utils.log(String.format("Unprovisioning course did not succeed(id: %s, server: %s, user: %s).",
                        bbCourse.getId().toExternalString(), serverName, bbUserName));
            } else {
                Utils.logVerbose(String.format("Panopto folders unlinked to course course(unprovisioned) (id: %s, server: %s, user: %s).",
                    bbCourse.getId().toExternalString(), serverName, bbUserName));
            }
        } catch (Exception e) {
            String exceptionMessage = e.getMessage();
            
            // Save the last error so we can display it to the user if they are provisioning from the course_configure page.
            if (exceptionMessage != null) {
                this.previousProvisioningError = exceptionMessage;
            }
            Utils.log(e, String.format("Error unprovisioning course (id: %s, server: %s, user: %s).",
                    bbCourse.getId().toExternalString(), serverName, bbUserName));
            return false;
        }
        
        return result;
    }
    
    // This function will remap an external Id to an existing folder, currently only used after unprovisioing an old folder.
    public boolean updateFolderExternalIdWithProvider(String folderId) {
        SessionManagementStub.AuthenticationInfo auth = new SessionManagementStub.AuthenticationInfo();
        auth.setAuthCode(apiUserAuthCode);
        auth.setPassword(null);
        auth.setUserKey(apiUserKey);
        
        try {
            UpdateFolderExternalIdWithProvider updateFolderExternalIdWithProviderParams = new UpdateFolderExternalIdWithProvider();
            updateFolderExternalIdWithProviderParams.setAuth(auth);
            updateFolderExternalIdWithProviderParams.setExternalId(bbCourse.getId().toExternalString());
            
            SessionManagementStub.Guid folderGuid = new SessionManagementStub.Guid();
            folderGuid.setGuid(folderId);
            updateFolderExternalIdWithProviderParams.setFolderId(folderGuid);
            
            updateFolderExternalIdWithProviderParams.setSiteMembershipProviderName(Utils.pluginSettings.getInstanceName());
            getPanoptoSessionManagementSOAPService(serverName).updateFolderExternalIdWithProvider(updateFolderExternalIdWithProviderParams);
        } catch (Exception e) {
            String exceptionMessage = e.getMessage();
            
            // Save the last error so we can display it to the user if they are provisioning from the course_configure page.
            if (exceptionMessage != null) {
                this.previousProvisioningError = exceptionMessage;
            }
            Utils.log(e, String.format("Error unprovisioning course (id: %s, server: %s, user: %s).",
                    bbCourse.getId().toExternalString(), serverName, bbUserName));
        }
        return true;
    }

    // Updates the course so it has no Panopto data
    public void resetCourse() throws RemoteException {
        if (!isMapped()) {
            Utils.log(String.format("Cannot reset BB course, not mapped yet. ID: %s, Title: %s\n",
                    bbCourse.getId().toExternalString(), bbCourse.getTitle()));
        } else {

            // Before blowing away the data, get the folder list.
            Folder[] courseFolders = getFolders();
            
            Utils.log(String.format(
                    "Resetting BB course, ID: %s, Title: %s, Old Server: %s, Old Panopto IDs: %s\n, Old folders count: %s\n",
                    bbCourse.getId().toExternalString(), bbCourse.getTitle(), serverName,
                    Utils.encodeArrayOfStrings(sessionGroupPublicIDs), courseFolders.length));
            
            if (unprovisionExternalCourse()) {            
                // In the set registry entry function, we delete existing entries
                // and only create new ones if the value is
                // not null.
                setCourseRegistryEntry(hostnameRegistryKey, null);
                setCourseRegistryEntries(sessionGroupIDRegistryKey, null);
                setCourseRegistryEntries(sessionGroupDisplayNameRegistryKey, null);
                
                // Remove all existing course imports.
                setCourseRegistryEntries(copySessionGroupIDsRegistryKey, null);
                setCourseRegistryEntries(copySessionGroupDisplayNamesRegistryKey, null);
    
                // If there are empty Panopto folders for this course, then delete
                // it so we don't trail empty unused
                // folders. This also reduces provisioning errors when a folder
                // already exists.
                if (courseFolders.length < 0) {
                    SessionManagementStub.ArrayOfguid folderGuids = new SessionManagementStub.ArrayOfguid();
                    
                    for (int idx = 0; idx < courseFolders.length; idx++) {
                        Session[] sessionsInFolder = this.getSessions(courseFolders[idx].getId().getGuid());
                        if ((sessionsInFolder == null) || (sessionsInFolder.length == 0)) {
                            folderGuids.addGuid(courseFolders[idx].getId());
                        }
                    }
    
                    // Batch delete all empty folders to reduce the API calls made.
                    SessionManagementStub.AuthenticationInfo auth = new SessionManagementStub.AuthenticationInfo();
                    auth.setAuthCode(apiUserAuthCode);
                    auth.setPassword(null);
                    auth.setUserKey(apiUserKey);
                    
                    DeleteFolders deleteFoldersParams = new DeleteFolders();
                    deleteFoldersParams.setAuth(auth);
                    deleteFoldersParams.setFolderIds(folderGuids);
                    sessionManagement.deleteFolders(deleteFoldersParams);
                }
            }
        }
    }

    // Re-provisions the course with the current settings. If it has never been
    // provisioned before a new folder will be
    // created
    public boolean reprovisionCourse() {
        return reprovisionCourse(sessionGroupPublicIDs);
    }

    public boolean provisionCourse(String serverName) {
        Utils.pluginSettings = new Settings();
        updateServerName(serverName);
        setCourseRegistryEntry(hostnameRegistryKey, serverName);

        try {
            
            // Reset the last provisioning error since this provisioning is new
            this.previousProvisioningError = "";
            
            String externalCourseId = Utils.decorateBlackboardCourseID(bbCourse.getId().toExternalString());
            String fullName = bbCourse.getCourseId() + ": " + bbCourse.getTitle();

            // Provision the course
            SessionManagementStub.AuthenticationInfo auth = new SessionManagementStub.AuthenticationInfo();
            auth.setAuthCode(apiUserAuthCode);
            auth.setPassword(null);
            auth.setUserKey(apiUserKey);
            
            ProvisionExternalCourse provisionExternalCourseParams = new ProvisionExternalCourse();
            provisionExternalCourseParams.setAuth(auth);
            provisionExternalCourseParams.setExternalId(externalCourseId);
            provisionExternalCourseParams.setName(fullName);
            Folder[] folders = new Folder[] { getPanoptoSessionManagementSOAPService(serverName)
                    .provisionExternalCourse(provisionExternalCourseParams).getProvisionExternalCourseResult() };
            updateCourseFolders(folders);

            this.reportIntegrationInfo();

            // Add menu item if setting is enabled
            if (Utils.pluginSettings.getInsertLinkOnProvision()) {
                addCourseMenuLink();

            }
        } catch (Exception e) {
            String exceptionMessage = e.getMessage();
            
            // Store the previous error so we can display it if we want to later.
            if (exceptionMessage != null) {
                this.previousProvisioningError = exceptionMessage;
            }
            
            Utils.log(e, String.format("Error provisioning course (id: %s, server: %s, user: %s).",
                    bbCourse.getId().toExternalString(), serverName, bbUserName));
            return false;
        }

        return true;
    }

    /**
     * removes the source course registries from the target course so it does not think it is actually provisioned to the source course.
     * 
     * @param sourceCourse
     *            Blackboard Course that we are copying Panopto permission from
     */
    public void handleCopyRegistryChanges(PanoptoData sourceCourseData) {
        try {
            // If the copy succeeded there is a chance depending on the copy type that the source course registries were copied into the new course.
            //  If this is the case we should check and remove them all.
            if (sourceCourseData.isMapped()) {
                ArrayList<String> mappedSourceFolders = new ArrayList<String>();
                mappedSourceFolders.addAll(Arrays.asList(sourceCourseData.getFolderIDs()));
                ArrayList<String> mappedFolders = new ArrayList<String>();
                mappedFolders.addAll(Arrays.asList(getFolderIDs()));
                
                ArrayList<String> mappedSourceFolderNames = new ArrayList<String>();
                mappedSourceFolderNames.addAll(Arrays.asList(sourceCourseData.getFolderDisplayNames()));
                ArrayList<String> mappedFolderNames = new ArrayList<String>();
                mappedFolderNames.addAll(Arrays.asList(getFolderDisplayNames()));
                
                ArrayList<String> mappedCopiedSourceFolders = new ArrayList<String>();
                mappedCopiedSourceFolders.addAll(Arrays.asList(sourceCourseData.getCopiedFolderIDs()));
                ArrayList<String> mappedCopiedFolders = new ArrayList<String>();
                mappedCopiedFolders.addAll(Arrays.asList(getCopiedFolderIDs()));
                
                ArrayList<String> mappedCopiedSourceFolderNames = new ArrayList<String>();
                mappedCopiedSourceFolderNames.addAll(Arrays.asList(sourceCourseData.getCopiedFolderDisplayNames()));
                ArrayList<String> mappedCopiedFolderNames = new ArrayList<String>();
                mappedCopiedFolderNames.addAll(Arrays.asList(getCopiedFolderDisplayNames()));
                
                // If this is the case then a "Copy Settings" was performed, the target course now believes it is provisioned to the source course folder.
                //  This check removes that belief and provisions the original folder.
                if (mappedFolders.containsAll(mappedSourceFolders)) {
                    // Unfortunately if a "Course Settings" included copy is performed on a course then all imported courses and currently mapped folders will be lost forever.
                    //  The next best thing we can do is clean up the overwritten source folder registries since we don't support those, and attempt to provision the course back to the default folder.
                    mappedFolders.removeAll(mappedSourceFolders);
                    mappedFolderNames.removeAll(mappedSourceFolderNames);
                    
                    mappedCopiedFolders.removeAll(mappedCopiedSourceFolders);
                    mappedCopiedFolderNames.removeAll(mappedCopiedSourceFolderNames);
                    
                    // We can simply overwrite the previous copySessionGroup registries since that will contain the source folder's copied info
                    setCourseRegistryEntries(copySessionGroupIDsRegistryKey, mappedCopiedFolders.toArray(emptyStringArray));
                    setCourseRegistryEntries(copySessionGroupDisplayNamesRegistryKey, mappedCopiedFolderNames.toArray(emptyStringArray));
                    setCourseRegistryEntries(sessionGroupIDRegistryKey, mappedFolders.toArray(emptyStringArray));
                    setCourseRegistryEntries(sessionGroupDisplayNameRegistryKey, mappedFolderNames.toArray(emptyStringArray));
                    
                    // If no folders are no longer mapped to this folder(as will usually be the case when 'course settings' are copied. Then provision a new folder, if a folder exists in Panopto it should be re-linked so long as the copying user has proper access.
                    if (mappedFolders.size() <= 0) {
                        Utils.log("It appears either 'Exact Copy' or a copy including 'Course Settings' was performed using a provisioned course as the source. This will invalidate any current Panopto mappings to the target course. We will continue to provision the target course to a new default folder, and import the source course into it if course copy is enabled.");
                       provisionCourse(serverName);
                    }
                }
            }
        } catch (Exception e) {
            Utils.log(e, "There was an error trying to handle the target course registries after a course copy.");
        }
    }

    public void reinitializeImports( ) {
        try {
            this.copySessionGroupPublicIDs = getCourseRegistryEntries(copySessionGroupIDsRegistryKey);
            
            if (this.copySessionGroupPublicIDs != null && (this.copySessionGroupPublicIDs.length > 0)) {
                if (this.serverVersion == null) {
                    this.serverVersion = getServerVersion();
                }
                
                if (PanoptoVersions.canCallCopyApiMethods(serverVersion)) {
                    SessionManagementStub.AuthenticationInfo auth = new SessionManagementStub.AuthenticationInfo();
                    auth.setAuthCode(apiUserAuthCode);
                    auth.setPassword(null);
                    auth.setUserKey(apiUserKey);
                    
                    String externalCourseId = Utils.decorateBlackboardCourseID(bbCourse.getId().toExternalString());
                    String fullName = bbCourse.getCourseId() + ": " + bbCourse.getTitle();
                    
                    SetCopiedExternalCourseAccess setCopiedExternalCourseAccessParams = new SetCopiedExternalCourseAccess();
                    setCopiedExternalCourseAccessParams.setAuth(auth);
                    setCopiedExternalCourseAccessParams.setExternalId(externalCourseId);
                    setCopiedExternalCourseAccessParams.setName(fullName);
                    
                    SessionManagementStub.ArrayOfguid folderGuids = new SessionManagementStub.ArrayOfguid();
                    for (int i = 0; i < copySessionGroupPublicIDs.length; ++i) {
                        SessionManagementStub.Guid folderGuid = new SessionManagementStub.Guid();
                        folderGuid.setGuid(copySessionGroupPublicIDs[i]);
                        folderGuids.addGuid(folderGuid);
                    }
                    setCopiedExternalCourseAccessParams.setFolderIds(folderGuids);
                    
                    getPanoptoSessionManagementSOAPService(serverName).setCopiedExternalCourseAccess(setCopiedExternalCourseAccessParams);
    
                    // Log the action
                    Utils.logVerbose(String.format(
                            "reinitialized course imports, Target ID: %s, Title: %s, Server: %s, Panopto ID: %s\n",
                            bbCourse.getId().toExternalString(), bbCourse.getTitle(),
                            serverName, Utils.encodeArrayOfStrings(sessionGroupPublicIDs)));
                }
            }
        } catch (Exception e) {
            Utils.log(e, String.format("Error reinitializing imports (course id: %s, server: %s, user: %s).",
                    bbCourse.getId().toExternalString(), serverName, bbUserName));
        }
    }
    /**
     * Copy Panopto folder permissions from the source course into this course
     * as viewer access.
     * 
     * Copies Panopto folder permissions via taking all provisioned folders and
     * all copied permissions from the source and giving the current course's
     * Panopto groups viewer access to those folders. Will also add these copied
     * folder public IDs and display names to the course registry so it can be
     * later referenced.
     * 
     * @param sourceCourse
     *            Blackboard Course that we are copying Panopto permission from
     */
    public void copyCoursePermissions(Course sourceCourse) {
        try {
            // We only work against 5_3 and above so evaluate the current server
            // version before attempting to copy
            PanoptoData sourceCourseData = new PanoptoData(sourceCourse, this.bbUserName);
            updateServerName(sourceCourseData.serverName);
            if (this.serverVersion == null) {
                this.serverVersion = getServerVersion();
            }
            
            // We only do something here if the source course's users have
            // permissions to view some folders in Panopto
            // this can be either regular permission or copied permissions.
            if (PanoptoVersions.canCallCopyApiMethods(serverVersion)) {

                // Source course has some Panopto folder permissions, generate a
                // list of all folders it can see
                // including both direct and copied. Generate list of both ids
                // and names.
                Set<String> folderIDs = new HashSet<String>(
                        sourceCourseData.getNumberOfFolders() + sourceCourseData.getNumberOfCopiedFolders());
                folderIDs.addAll(Arrays.asList(sourceCourseData.getFolderIDs()));
                folderIDs.addAll(Arrays.asList(sourceCourseData.getCopiedFolderIDs()));

                Set<String> folderNames = new HashSet<String>(
                        sourceCourseData.getNumberOfFolders() + sourceCourseData.getNumberOfCopiedFolders());
                folderNames.addAll(Arrays.asList(sourceCourseData.getFolderDisplayNames()));
                folderNames.addAll(Arrays.asList(sourceCourseData.getCopiedFolderDisplayNames()));

                // Update registry to set this course as mapped to a server and
                // context
                setCourseRegistryEntry(hostnameRegistryKey, serverName);

                SessionManagementStub.AuthenticationInfo auth = new SessionManagementStub.AuthenticationInfo();
                auth.setAuthCode(apiUserAuthCode);
                auth.setPassword(null);
                auth.setUserKey(apiUserKey);
                
                String externalCourseId = Utils.decorateBlackboardCourseID(bbCourse.getId().toExternalString());
                String fullName = bbCourse.getCourseId() + ": " + bbCourse.getTitle();
                
                SetCopiedExternalCourseAccess setCopiedExternalCourseAccessParams = new SetCopiedExternalCourseAccess();
                setCopiedExternalCourseAccessParams.setAuth(auth);
                setCopiedExternalCourseAccessParams.setExternalId(externalCourseId);
                setCopiedExternalCourseAccessParams.setName(fullName);
                
                SessionManagementStub.ArrayOfguid folderGuids = new SessionManagementStub.ArrayOfguid();
                for (java.util.Iterator<String> walker = folderIDs.iterator(); walker.hasNext();) {
                    SessionManagementStub.Guid folderGuid = new SessionManagementStub.Guid();
                    folderGuid.setGuid(walker.next());
                    folderGuids.addGuid(folderGuid);
                }
                setCopiedExternalCourseAccessParams.setFolderIds(folderGuids);
                
                getPanoptoSessionManagementSOAPService(serverName).setCopiedExternalCourseAccess(setCopiedExternalCourseAccessParams);

                // Save the new list of folders back into the course registry,
                // includes both public IDs and names,
                // concatenated with any existing copied folders that might
                // already be there.
                folderIDs.addAll(Arrays.asList(this.getCopiedFolderIDs()));
                setCourseRegistryEntries(copySessionGroupIDsRegistryKey, folderIDs.toArray(emptyStringArray));
                folderNames.addAll(Arrays.asList(this.getCopiedFolderDisplayNames()));
                setCourseRegistryEntries(copySessionGroupDisplayNamesRegistryKey,
                        folderNames.toArray(emptyStringArray));

                // Log the action
                Utils.log(String.format(
                        "Copied BB course, Source ID: %s, Target ID: %s, Title: %s, Server: %s, Panopto ID: %s\n",
                        sourceCourse.getCourseId(), bbCourse.getId().toExternalString(), bbCourse.getTitle(),
                        serverName, Utils.encodeArrayOfStrings(sessionGroupPublicIDs)));
            }
        } catch (Exception e) {
            Utils.log(e, String.format("Error provisioning course copy (id: %s, server: %s, user: %s).",
                    bbCourse.getId().toExternalString(), serverName, bbUserName));
        }
    }

    // Called after provision or reprovision to update the local store of folder
    // metadata
    private void updateCourseFolders(Folder[] folders) {
        setCourseRegistryEntry(hostnameRegistryKey, serverName);

        // First sort the folders.
        ArrayList<Folder> sortedFolders = new ArrayList<Folder>();
        sortedFolders.addAll(Arrays.asList(folders));
        Collections.sort(sortedFolders, new FolderComparator());

        // Now construct the list of ids and the list of names
        this.sessionGroupPublicIDs = new String[folders.length];
        this.sessionGroupDisplayNames = new String[folders.length];
        for (int i = 0; i < folders.length; i++) {
            // These are GUIDs and will never be too long
            sessionGroupPublicIDs[i] = sortedFolders.get(i).getId().getGuid();

            // Display names might go past the 255 Blackboard limit so we elide
            // the string to be no more than 255
            // characters
            sessionGroupDisplayNames[i] = Utils.elideMiddle(sortedFolders.get(i).getName(), 100, 255);
        }

        // Save the new list of folders back into the registry
        Utils.log(String.format("Provisioned BB course, ID: %s, Title: %s, Server: %s, Panopto ID: %s\n",
                bbCourse.getId().toExternalString(), bbCourse.getTitle(), serverName,
                Utils.encodeArrayOfStrings(sessionGroupPublicIDs)));
        setCourseRegistryEntries(sessionGroupIDRegistryKey, sessionGroupPublicIDs);
        setCourseRegistryEntries(sessionGroupDisplayNameRegistryKey, sessionGroupDisplayNames);
    }

    public static boolean HasPanoptoServer(Course bbCourse) {
        return getCourseRegistryEntry(bbCourse.getId(), hostnameRegistryKey) != null;
    }

    // Gets all the members of the course from Blackboard
    private static List<CourseMembership> getCourseMemberships(Course bbCourse) {
        BbPersistenceManager bbPm = PersistenceServiceFactory.getInstance().getDbPersistenceManager();

        // Get the course membership (instructors, students, etc.)
        List<CourseMembership> courseMemberships = null;
        try {
            CourseMembershipDbLoader courseMembershipLoader = (CourseMembershipDbLoader) bbPm
                    .getLoader(CourseMembershipDbLoader.TYPE);

            courseMemberships = courseMembershipLoader.loadByCourseId(bbCourse.getId(), null, true);
        } catch (Exception e) {
            Utils.log(e, String.format("Error getting course membership (course ID: %s).", bbCourse.getId()));
        }

        return courseMemberships;
    }

    // Gets the user key of all the students of the course
    public List<String> getTAs() {
        ArrayList<String> lstTAs = new ArrayList<String>();

        // Get the course membership (instructors, students, etc.)
        List<CourseMembership> courseMemberships = getCourseMemberships(bbCourse);

        List<CourseMembership> TACourseMemberships = new ArrayList<CourseMembership>();
        for (CourseMembership membership : courseMemberships) {

            blackboard.data.course.CourseMembership.Role membershipRole = membership.getRole();
            if (isTARole(membershipRole)) {
                TACourseMemberships.add(membership);
            }
        }
        if (!TACourseMemberships.isEmpty()) {

            for (Object membershipObject : TACourseMemberships) {
                CourseMembership courseMembership = (CourseMembership) membershipObject;
                User courseUser = courseMembership.getUser();
                if (courseUser != null) {
                    String courseUserKey = Utils.decorateBlackboardUserName(courseUser.getUserName());
                    lstTAs.add(courseUserKey);
                }
            }
        }
        return lstTAs;
    }

    // Gets the user key of all the students of the course
    public List<String> getStudents() {
        ArrayList<String> lstStudents = new ArrayList<String>();

        // Get the course membership (instructors, students, etc.)
        List<CourseMembership> courseMemberships = getCourseMemberships(bbCourse);

        List<CourseMembership> studentCourseMemberships = new ArrayList<CourseMembership>();
        for (CourseMembership membership : courseMemberships) {

            blackboard.data.course.CourseMembership.Role membershipRole = membership.getRole();
            if (isStudentRole(membershipRole)) {
                studentCourseMemberships.add(membership);
            }
        }
        if (!studentCourseMemberships.isEmpty()) {

            for (Object membershipObject : studentCourseMemberships) {
                CourseMembership courseMembership = (CourseMembership) membershipObject;
                User courseUser = courseMembership.getUser();
                if (courseUser != null) {
                    String courseUserKey = Utils.decorateBlackboardUserName(courseUser.getUserName());

                    lstStudents.add(courseUserKey);
                }
            }
        }
        return lstStudents;
    }

    // Gets info about all the instructors of the course
    public List<String> getInstructors() {
        ArrayList<String> lstInstructors = new ArrayList<String>();

        // Get the course membership (instructors, students, etc.)
        List<CourseMembership> courseMemberships = getCourseMemberships(bbCourse);

        List<CourseMembership> instructorCourseMemberships = new ArrayList<CourseMembership>();
        for (CourseMembership membership : courseMemberships) {
            blackboard.data.course.CourseMembership.Role membershipRole = membership.getRole();
            if (isInstructorRole(membershipRole)) {
                instructorCourseMemberships.add(membership);
            }

        }

        if (!instructorCourseMemberships.isEmpty()) {
            for (Object membershipObject : instructorCourseMemberships) {
                CourseMembership courseMembership = (CourseMembership) membershipObject;
                User courseUser = courseMembership.getUser();
                if (courseUser != null) {
                    String courseUserKey = Utils.decorateBlackboardUserName(courseUser.getUserName());
                    lstInstructors.add(courseUserKey);
                }
            }
        }
        return lstInstructors;
    }

    /**
     * Gets info about all users with no role for the course.
     */
    public List<String> getNoRoleUsers() {
        ArrayList<String> lstNoRoleUsers = new ArrayList<String>();

        // Get the course membership (instructors, students, etc.)
        List<CourseMembership> courseMemberships = getCourseMemberships(bbCourse);

        List<CourseMembership> noRoleCourseMemberships = new ArrayList<CourseMembership>();
        for (CourseMembership membership : courseMemberships) {
            blackboard.data.course.CourseMembership.Role membershipRole = membership.getRole();
            if (isNoRole(membershipRole)) {
                noRoleCourseMemberships.add(membership);
            }

        }

        if (!noRoleCourseMemberships.isEmpty()) {
            for (Object membershipObject : noRoleCourseMemberships) {
                CourseMembership courseMembership = (CourseMembership) membershipObject;
                User courseUser = courseMembership.getUser();
                if (courseUser != null) {
                    String courseUserKey = Utils.decorateBlackboardUserName(courseUser.getUserName());
                    lstNoRoleUsers.add(courseUserKey);
                }
            }
        }
        return lstNoRoleUsers;
    }

    /*
     * Returns true if role should be treated as an Instructor. Instructors get
     * creator access in Panopto.
     */
    private static boolean isInstructorRole(blackboard.data.course.CourseMembership.Role membershipRole) {
        // Role is instructor role if it is the 'Instructor' or 'Course Builder'
        // built in blackboard role, or if it is
        // in the custom instructor roles list.
        return membershipRole.equals(CourseMembership.Role.INSTRUCTOR)
                || membershipRole.equals(CourseMembership.Role.COURSE_BUILDER)
                || getIdsForRole("instructor").contains(membershipRole.getIdentifier().toLowerCase());
    }

    /*
     * Returns true if role should be treated as a Student. Students get viewer
     * access in Panopto.
     */
    private static boolean isStudentRole(blackboard.data.course.CourseMembership.Role membershipRole) {
        // Role is student role if it is not a built in instructor or ta role,
        // or a mapped custom role.
        return !isInstructorRole(membershipRole) && !isTARole(membershipRole) && !isNoRole(membershipRole);
    }

    /*
     * Returns true if role should be treated as a TA. TA's get viewer access in
     * Panopto, unless otherwise specified in the Blackboard block settings. Any
     * custom blackboard roles are treated as TAs, unless they are marked with
     * the 'Act As Instructor' flag.
     */
    private static boolean isTARole(blackboard.data.course.CourseMembership.Role membershipRole) {
        // Role is a TA role if it is the 'Teaching Assistant' built in
        // blackboard role or if it is in the list of
        // custom ta roles
        return membershipRole.equals(Role.TEACHING_ASSISTANT)
                || (getIdsForRole("ta").contains(membershipRole.getIdentifier().toLowerCase()));
    }

    /**
     * Returns true if role should be treated as no privilege on Panopto. Users
     * with this role do not get access in Panopto, except publicly or
     * organization wide viewable.
     */
    private static boolean isNoRole(blackboard.data.course.CourseMembership.Role membershipRole) {
        return (getIdsForRole("none").contains(membershipRole.getIdentifier().toLowerCase()));
    }

    public boolean userMayProvision() {
        Utils.pluginSettings = new Settings();
        // Admins may provision any course. Instructors may provision their
        // own course if the setting is enabled
        return Utils.userCanConfigureSystem() || (IsInstructor() && Utils.pluginSettings.getInstructorsCanProvision());
    }

    // Check for whether a user may add course menu links. This may be done by
    // Admins and instructors at any time or by
    // TAs if setting is checked
    public boolean userMayAddLinks() {
        // Admins can add links to any course. Instructors can add links to
        // their own courses
        return Utils.userCanConfigureSystem() || this.canAddLinks();
    }

    public boolean userMayConfig() {
        // Admins may config any course. Instructors may configure their own
        // courses
        return Utils.userCanConfigureSystem() || this.IsInstructor();
    }

    /**
     * Determine if there are any copied permissions the user can see
     * 
     * Will be true for admins and instructors if there are any copied folders
     * for the course, is not used on any pages that students should be able to
     * see.
     * 
     * @return Boolean true if the course has copied Panopto permissions and
     *         user can see them
     */
    public boolean courseHasCopiedPermissionsToBeDisplayed() {
        return this.userMayConfig() && (this.getNumberOfCopiedFolders() > 0);
    }

    // Returns true if the specified user is an instructor of the specified
    // course
    // If checkTACanCreateLinks is true, a check will be performed if either
    // getGrantTAProvision
    // or getTAsCanCreateLinks returns true. This is used when checking if TAs
    // may add course menu links
    public static boolean isUserInstructor(Id bbCourseId, String bbUserName, boolean checkTACanCreateLinks) {
        BbPersistenceManager bbPm = PersistenceServiceFactory.getInstance().getDbPersistenceManager();
        
        // If there is no user associated just return false, this can happen on async tasks like course copy.
        if (bbUserName == null || bbUserName.isEmpty()) {
            return false;
        }
        
        try {
            // Get user's blackboard ID from their username
            UserDbLoader userLoader = (UserDbLoader) bbPm.getLoader(UserDbLoader.TYPE);
            User user = userLoader.loadByUserName(bbUserName);
            Id bbUserId = user.getId();

            // Load the user's membership in the current course to get their
            // role
            CourseMembershipDbLoader membershipLoader = (CourseMembershipDbLoader) bbPm
                    .getLoader(CourseMembershipDbLoader.TYPE);
            CourseMembership usersCourseMembership = membershipLoader.loadByCourseAndUserId(bbCourseId, bbUserId);
            Role userRole = usersCourseMembership.getRole();

            Utils.pluginSettings = new Settings();

            if (isInstructorRole(userRole)) {
                return true;
            }

            // If settings are configured to treat TAs as an instructor, and the
            // user is either a Teaching Assistant or
            // has a custom role, return true.
            else if (Utils.pluginSettings.getGrantTAProvision()
                    || (checkTACanCreateLinks && Utils.pluginSettings.getTAsCanCreateLinks())) {
                if (isTARole(userRole)) {
                    return true;
                }
            }
        } catch (Exception e) {
            Utils.log(e, String.format("Error getting user's course membership (course ID: %s, userName: %s).",
                    bbCourseId, bbUserName));
        }

        // User is not an instructor
        return false;
    }

    // Returns true if the specified user can add a course menu link. Nearly
    // identical to isUserInstructor but includes
    // a check for TAsCanCreateLinks
    public static boolean canUserAddLinks(Id bbCourseId, String bbUserName) {
        return isUserInstructor(bbCourseId, bbUserName, true);
    }

    // Will be used in the future.
    @SuppressWarnings("unused")
    private static AccessManagementStub getPanoptoAccessManagementSOAPService(String serverName) {
        AccessManagementStub stub = null;

        try {
            stub = new AccessManagementStub("https://" + serverName + "/Panopto/PublicAPI/4.6/AccessManagement.svc");
            
            // Multiply the value by 1000 to convert the time from seconds to ms.
            int maxConnectionWait = new Integer(Utils.pluginSettings.getMaxConnectionWait()) * 1000;
            
            stub._getServiceClient().getOptions().setProperty(HTTPConstants.CONNECTION_TIMEOUT, maxConnectionWait);
            
            int maxSocketWait = new Integer(Utils.pluginSettings.getMaxSocketWait()) * 1000;
            
            stub._getServiceClient().getOptions().setProperty(HTTPConstants.SO_TIMEOUT, maxSocketWait);
        } catch (Exception e) {
            Utils.log(e, String.format("Error getting Access Management SOAP service (server: %s).", serverName));
        }

        return stub;
    }

    private static SessionManagementStub getPanoptoSessionManagementSOAPService(String serverName) {
        SessionManagementStub stub = null;

        try {
            stub = new SessionManagementStub("https://" + serverName + "/Panopto/PublicAPI/4.6/SessionManagement.svc");

            // Multiply the value by 1000 to convert the time from seconds to ms.
            int maxConnectionWait = new Integer(Utils.pluginSettings.getMaxConnectionWait()) * 1000;
            
            stub._getServiceClient().getOptions().setProperty(HTTPConstants.CONNECTION_TIMEOUT, maxConnectionWait);
            
            int maxSocketWait = new Integer(Utils.pluginSettings.getMaxSocketWait()) * 1000;
            
            stub._getServiceClient().getOptions().setProperty(HTTPConstants.SO_TIMEOUT, maxSocketWait);
        } catch (Exception e) {
            Utils.log(e, String.format("Error getting Session Management SOAP service (server: %s).", serverName));
        }

        return stub;
    }

    private static UserManagementStub getPanoptoUserManagementSOAPService(String serverName) {
        UserManagementStub stub = null;

        try {
            stub = new UserManagementStub("https://" + serverName + "/Panopto/PublicAPI/4.6/UserManagement.svc");

            // Multiply the value by 1000 to convert the time from seconds to ms.
            int maxConnectionWait = new Integer(Utils.pluginSettings.getMaxConnectionWait()) * 1000;
            
            stub._getServiceClient().getOptions().setProperty(HTTPConstants.CONNECTION_TIMEOUT, maxConnectionWait);
            
            int maxSocketWait = new Integer(Utils.pluginSettings.getMaxSocketWait()) * 1000;
            
            stub._getServiceClient().getOptions().setProperty(HTTPConstants.SO_TIMEOUT, maxSocketWait);
        } catch (Exception e) {
            Utils.log(e, String.format("Error getting User Management SOAP service (server: %s).", serverName));
        }

        return stub;
    }

    private static AuthStub getPanoptoAuthSOAPService(String serverName) {
        AuthStub stub = null;

        try {
            stub = new AuthStub("https://" + serverName + "/Panopto/PublicAPI/4.6/Auth.svc");

            // Multiply the value by 1000 to convert the time from seconds to ms.
            int maxConnectionWait = new Integer(Utils.pluginSettings.getMaxConnectionWait()) * 1000;
            
            stub._getServiceClient().getOptions().setProperty(HTTPConstants.CONNECTION_TIMEOUT, maxConnectionWait);
            
            int maxSocketWait = new Integer(Utils.pluginSettings.getMaxSocketWait()) * 1000;
            
            stub._getServiceClient().getOptions().setProperty(HTTPConstants.SO_TIMEOUT, maxSocketWait);
        } catch (Exception e) {
            Utils.log(e, String.format("Error getting Auth SOAP service (server: %s).", serverName));
        }

        return stub;
    }

    // Instance method just calls out to static method below
    private String getCourseRegistryEntry(String key) {
        return getCourseRegistryEntry(bbCourse.getId(), key);
    }

    // Instance method just calls out to static method below
    private void setCourseRegistryEntry(String key, String value) {
        setCourseRegistryEntry(bbCourse.getId(), key, value);
    }

    private static String getCourseRegistryEntry(Id courseId, String key) {
        String value = null;

        try {
            CourseRegistryEntryDbLoader creLoader = CourseRegistryEntryDbLoader.Default.getInstance();
            Registry registry = creLoader.loadRegistryByCourseId(courseId);

            value = registry.getValue(key);
        } catch (Exception e) {
            Utils.log(e, String.format("Error getting course registry entry (key: %s).", key));
        }

        return value;
    }

    // Blackboard DB values have a limited length so we store each string entry
    // as its own value with a
    // number-post-fixed key. Here we have to handle legacy values that might be
    // stored with just the key name.
    // Assumption: only called for keys previously stored with
    // setCourseRegistryEntries or the old method with
    // double-quoted, comma-separated values.
    private String[] getCourseRegistryEntries(String key) {
        String[] values = null;
        try {
            Utils.logVerbose("Getting CourseRegistry for course: " + bbCourse.getId().toExternalString());

            CourseRegistryEntryDbLoader creLoader = CourseRegistryEntryDbLoader.Default.getInstance();
            Registry registry = creLoader.loadRegistryByCourseId(bbCourse.getId());

            String value = registry.getValue(key);

            // If there's a value for the plain key, we are dealing with a
            // legacy value which should be in the "","",""
            // format. We decode the string and return the individual values
            if (value != null) {
                values = Utils.decodeArrayOfStrings(value);
            }
            // If no value, then we are dealing with the new case where we split
            // values up into individual keys so look
            // each of those up until we stop finding them
            else {
                // Use an ArrayList because we don't know ahead of time how many
                // we may have. This is Ok for small
                // numbers (e.g., less than 100).
                ArrayList<String> list = new ArrayList<String>();

                String tempValue = registry.getValue(key + list.size());

                while (tempValue != null) {
                    list.add(tempValue);
                    tempValue = registry.getValue(key + list.size());
                }

                // If there were no values at all then 'value' will remain null
                // as expected
                if (list.size() > 0) {
                    values = new String[list.size()];
                    list.toArray(values);
                }
            }
        } catch (Exception e) {
            Utils.log(e, String.format("Error getting course registry entry (key: %s).", key));
        }

        return values;
    }

    // The Blackboard course registry stores key/values pairs per-course. Static
    // setter enables us to store registry
    // values for newly-provisioned courses without the cost of instantiating
    // and populating an object for each one.
    private static void setCourseRegistryEntry(Id courseId, String key, String value) {
        try {
            CourseRegistryEntryDbPersister crePersister = CourseRegistryEntryDbPersister.Default.getInstance();

            DeleteKeyForCourse(crePersister, key, courseId);

            if (value != null) {
                CourseRegistryEntry cre = new CourseRegistryEntry(key, value);
                cre.setCourseId(courseId);
                crePersister.persist(cre);
            }
        } catch (Exception e) {
            Utils.log(e, String.format("Error setting course registry entry (course ID: %s, key: %s, value: %s).",
                    courseId.toExternalString(), key, value));
        }
    }

    // Blackboard DB values have a limited length so we store each string entry
    // as its own value with a
    // number-post-fixed key.
    private void setCourseRegistryEntries(String key, String[] values) {
        try {
            Utils.logVerbose("Setting CourseRegistry for course: " + bbCourse.getId().toExternalString());

            CourseRegistryEntryDbPersister crePersister = CourseRegistryEntryDbPersister.Default.getInstance();
            CourseRegistryEntryDbLoader creLoader = CourseRegistryEntryDbLoader.Default.getInstance();
            Registry registry = creLoader.loadRegistryByCourseId(bbCourse.getId());

            // Delete any legacy key still hanging around
            Utils.logVerbose("Checking for legacy key " + key);
            RegistryEntry entry = registry.getEntry(key);
            if (entry != null) {
                Utils.logVerbose("Deleting legacy key " + key);
                DeleteKeyForCourse(crePersister, key, bbCourse.getId());
            }

            // Now delete any per-folder entries that may exist. We don't know
            // how many so we just start at 0 and keep
            // looking until we find a missing one.
            int j = 0;
            String keyToDelete = key + j;
            entry = registry.getEntry(keyToDelete);
            Utils.logVerbose("Checking for key " + keyToDelete);
            while (entry != null) {
                Utils.logVerbose("Deleting key " + keyToDelete);
                DeleteKeyForCourse(crePersister, keyToDelete, bbCourse.getId());

                j++;
                keyToDelete = key + j;
                Utils.logVerbose("Checking for key " + keyToDelete);
                entry = registry.getEntry(keyToDelete);
            }

            // If we have values, save them each in their own key
            if (values != null) {
                Utils.logVerbose("Adding new values - count: " + values.length);
                for (int i = 0; i < values.length; i++) {
                    Utils.logVerbose("Adding new key: " + key + i + " value: " + values[i]);
                    CourseRegistryEntry cre = new CourseRegistryEntry(key + i, values[i]);
                    cre.setCourseId(bbCourse.getId());
                    crePersister.persist(cre);
                }
            }
        } catch (Exception e) {
            Utils.log(e, String.format("Error setting course registry entry (course ID: %s, key: %s, value: %s).",
                    bbCourse.getId().toExternalString(), key, Utils.encodeArrayOfStrings(values)));
        }
    }

    // Helper method that just makes 6 lines into one in the calling method
    private static void DeleteKeyForCourse(CourseRegistryEntryDbPersister crePersister, String keyToDelete,
            Id courseId) {
        try {
            crePersister.deleteByKeyAndCourseId(keyToDelete, courseId);
        } catch (KeyNotFoundException knfe) {
        } catch (PersistenceException pe) {
        }
    }

    private PanoptoVersion getServerVersion() {
        PanoptoVersion ret = null;
        try {
            AuthStub authStub = getPanoptoAuthSOAPService(serverName);
            ret = PanoptoVersion.fetchOrEmpty(authStub);
        } catch (Exception e) {
            Utils.log(e, "Failed attempting to get the server version of " + serverName);
        }
        
        return ret;
    }

    private void addCourseMenuLink() throws ValidationException, PersistenceException {
        Id cid;
        cid = bbCourse.getId();
        Utils.pluginSettings = new Settings();

        // Get list of page's current menu links
        List<CourseToc> courseTocList = CourseTocDbLoader.Default.getInstance().loadByCourseId(cid);

        // Iterate through each link and check if it's text matches the text of
        // the item to be created
        boolean linkExists = false;
        while (courseTocList.iterator().hasNext() && linkExists == false) {
            CourseToc ct = courseTocList.iterator().next();

            // If the text matches, set linkExists to true so we don't add a
            // duplicate link
            linkExists = ct.getLabel().equals(Utils.pluginSettings.getMenuLinkText());

            // BBList's iterator doesn't support remove(), so we have to remove
            // manually by ID
            courseTocList.remove(ct);
        }

        // If link with desired text doesn't exists, create a new one
        if (!linkExists) {
            CourseToc panLink = new CourseToc();
            panLink.setCourseId(cid);

            // Create application type course link. This will direct to url set
            // for the target plugin in
            // bb-manifest.xml, with the coure's id appended as an argument.
            // This courseToc target type will allow the
            // link to open in within the current course without needing to be
            // wrapped in an external content frame, and
            // the plugin content will always point to the corresponding panopto
            // content of the course that the link is
            // being accessed from.
            panLink.setTargetType(CourseToc.Target.APPLICATION);
            panLink.setLabel(Utils.pluginSettings.getMenuLinkText());
            panLink.setLaunchInNewWindow(false);
            panLink.setIsEntryPoint(false);

            // Set the internal handle for the target application, in the case
            // the handle for the "Panopto course tool"
            // application Internal handle name is set by BlackBoard and we have
            // no control. Somehow, it is set other
            // than suffix -1. Try -2 and -3 as fallback.
            try {
                panLink.setInternalHandle("ppto-PanoptoCourseToolApp-nav-1");
                CourseTocDbPersister.Default.getInstance().persist(panLink);
            } catch (blackboard.persist.PersistenceException e1) {
                Utils.log(String.format(
                        "addCourseMenuLink(%s): ppto-PanoptoCourseToolApp-nav-1 failed. retrying with -2.",
                        bbCourse.getId().toExternalString()));
                try {
                    panLink.setInternalHandle("ppto-PanoptoCourseToolApp-nav-2");
                    CourseTocDbPersister.Default.getInstance().persist(panLink);
                } catch (blackboard.persist.PersistenceException e2) {
                    Utils.log(String.format(
                            "addCourseMenuLink(%s): ppto-PanoptoCourseToolApp-nav-1 failed. retrying with -3.",
                            bbCourse.getId().toExternalString()));
                    panLink.setInternalHandle("ppto-PanoptoCourseToolApp-nav-3");
                    CourseTocDbPersister.Default.getInstance().persist(panLink);
                }
            }
        }
    }

    // Retrieves list of role ids mapped to either the "ta" or "instructor" role
    // in the block settings. If a string
    // other than "ta" or "instructor" is passed, an empty list will be
    // returned.
    private static List<String> getIdsForRole(String rolename) {
        Utils.pluginSettings = new Settings();

        List<String> roleIds = new ArrayList<String>();
        String roleMappingsString = Utils.pluginSettings.getRoleMappingString();
        String[] roleMappingsSplit = roleMappingsString.split(";");
        for (String mappingString : roleMappingsSplit) {
            String[] mappingArray = mappingString.split(":");
            if (mappingArray.length == 2) {
                String RoleId = mappingArray[0];
                if (mappingArray[1].trim().equalsIgnoreCase(rolename)) {
                    roleIds.add(RoleId.trim().toLowerCase());
                }
            }
        }
        return roleIds;
    }

    // Enum types returned by addBlackboardContentItem, indicating whether a
    // Panopto link has been successfully added to
    // a course.
    public static enum LinkAddedResult {
        SUCCESS, // Link was added successfully.
        NOTCREATOR, // Link was not added because the session is not available
                    // and the user does not have creator access
                    // in order to make it available.
        NOTPUBLISHER, // Link was not added because session requires publisher
                      // approval.
        FAILURE; // Link was not added for an unspecified reason.
    }
}
