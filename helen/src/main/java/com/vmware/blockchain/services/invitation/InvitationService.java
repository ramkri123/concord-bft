/*
 * Copyright (c) 2019 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.services.invitation;

import java.io.IOException;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import com.google.common.collect.ImmutableList;
import com.vmware.blockchain.auth.AuthHelper;
import com.vmware.blockchain.common.csp.CspCommon;
import com.vmware.blockchain.common.csp.CspCommon.CspPatchServiceRolesRequest;
import com.vmware.blockchain.common.csp.CspConstants;
import com.vmware.blockchain.common.csp.api.client.CspApiClient;
import com.vmware.blockchain.services.profiles.Roles;

/**
 * Invitation controller.  Handles the call backs from CSP.
 */
@Service
public class InvitationService {
    static final Logger logger = LogManager.getLogger(InvitationService.class);

    private CspApiClient cspApiClient;
    private AuthHelper authHelper;
    private String serviceDefinitionLink;

    @Autowired
    public InvitationService(CspApiClient cspApiClient,
                             AuthHelper authHelper,
                             @Value("${vmbc.service.id:#null}") String serviceId) {
        this.cspApiClient = cspApiClient;
        this.authHelper = authHelper;
        this.serviceDefinitionLink = CspConstants.CSP_SERVICE_DEFINITION + "/external/" + serviceId;
    }

    /**
     * Handle a service invitation.  Called from the invitaion controller, so authentication is set up.
     */
    public void handleServiceInvitation(String invitationLink) throws IOException {


        logger.info("Handling service invitation {}", invitationLink);
        // first let's get the invitation
        try {
            CspCommon.CspServiceInvitation invitation = cspApiClient.getInvitation(invitationLink);
            String orgLink = CspConstants.CSP_ORG_API + "/" + authHelper.getOrganizationId().toString();
            // Let's be sure this is the right org and right service
            Assert.isTrue(invitation.getOrgLink().equals(orgLink),
                          "Logged in organization does not match invitation");
            Assert.isTrue(serviceDefinitionLink.equals(invitation.getServiceDefinitionLink()),
                          "Service defintion does not match invitation");
            // Based on the context, determine what roles need to be added.
            // TODO: right now the only thing we support is being a consortium owner.  At some point we
            // need to support invitation to a consortium.  This will be in the invitation's context.
            CspCommon.CspPatchServiceRolesRequest body = new CspPatchServiceRolesRequest();
            body.setServiceDefinitionLink(serviceDefinitionLink);
            List<String> roles = ImmutableList.of(Roles.CONSORTIUM_ADMIN.toString(), Roles.ORG_ADMIN.toString());
            body.setRoleNamesToAdd(roles);
            cspApiClient.patchOrgServiceRoles(authHelper.getAuthToken(), authHelper.getOrganizationId(),
                                              authHelper.getEmail(), body);
        } catch (Exception e) {
            logger.warn("Unable to process invitation", e);
            // if there's a problem getting this, don't do anything
            return;
        }

    }

}