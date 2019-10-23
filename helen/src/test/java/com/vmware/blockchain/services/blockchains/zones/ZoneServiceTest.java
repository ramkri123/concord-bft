/*
 * Copyright (c) 2019 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.services.blockchains.zones;

import static com.vmware.blockchain.services.blockchains.zones.Zone.Type.NONE;
import static com.vmware.blockchain.services.blockchains.zones.Zone.Type.ON_PREM;
import static com.vmware.blockchain.services.blockchains.zones.Zone.Type.VMC_AWS;
import static com.vmware.blockchain.services.blockchains.zones.ZoneTestUtils.getOnpremZone;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.vmware.blockchain.MvcConfig;
import com.vmware.blockchain.auth.AuthHelper;
import com.vmware.blockchain.common.Constants;
import com.vmware.blockchain.common.NotFoundException;
import com.vmware.blockchain.dao.GenericDao;
import com.vmware.blockchain.dao.TestDaoConfig;
import com.vmware.blockchain.db.DbConfig;
import com.vmware.blockchain.db.TestMapper;
import com.vmware.blockchain.deployment.v1.ListOrchestrationSitesRequest;
import com.vmware.blockchain.deployment.v1.ListOrchestrationSitesResponse;
import com.vmware.blockchain.deployment.v1.MessageHeader;
import com.vmware.blockchain.deployment.v1.OrchestrationSiteIdentifier;
import com.vmware.blockchain.deployment.v1.OrchestrationSiteInfo.Type;
import com.vmware.blockchain.deployment.v1.OrchestrationSiteServiceGrpc.OrchestrationSiteServiceStub;
import com.vmware.blockchain.deployment.v1.OrchestrationSiteView;
import com.vmware.blockchain.security.JwtTokenProvider;
import com.vmware.blockchain.security.ServiceContext;
import com.vmware.blockchain.services.profiles.ConsortiumService;
import com.vmware.blockchain.services.profiles.DefaultProfiles;
import com.vmware.blockchain.services.profiles.Organization;
import com.vmware.blockchain.services.profiles.OrganizationService;
import com.vmware.blockchain.services.profiles.ProfilesService;
import com.vmware.blockchain.services.profiles.UserService;
import com.vmware.blockchain.services.tasks.TaskService;

import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;

/**
 * Test the ZoneService.
 */
@ExtendWith(SpringExtension.class)
@TestPropertySource(locations = "classpath:db-postgres-test.properties")
@ComponentScan(basePackageClasses = {GenericDao.class, ConsortiumService.class, OrganizationService.class})
@EnableAutoConfiguration
@AutoConfigureEmbeddedDatabase
@ContextConfiguration(classes = {MvcConfig.class, DbConfig.class, TestDaoConfig.class })
public class ZoneServiceTest {
    private static final UUID SITE_1 = UUID.fromString("84b9a0ed-c162-446a-b8c0-2e45755f3844");
    private static final UUID SITE_2 = UUID.fromString("275638a3-8860-4925-85de-c73d45cb7232");
    private static final UUID ONPREM_ORG = UUID.fromString("747c4d97-63a5-4d1c-bed1-b9ae4f4dfcd0");
    private static final UUID ORG_2 = UUID.fromString("d88d6a72-1ddf-4309-9217-b6d0f74603ce");
    private static final UUID USER_ID = UUID.fromString("5df113c4-6f7d-45a1-82f3-806dc6ec6a7e");
    private static final String USER_EMAIL = "test@foo.com";

    @Mock
    OrchestrationSiteServiceStub client;

    @Autowired
    GenericDao genericDao;

    @Autowired
    TestMapper testMapper;

    @MockBean
    private UserService userService;

    @MockBean
    OrganizationService organizationService;

    @MockBean
    private ProfilesService prm;

    @MockBean
    private PasswordEncoder passwordEncoder;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private DefaultProfiles profiles;

    @MockBean
    AuthHelper authHelper;

    @MockBean
    ServiceContext serviceContext;

    @MockBean
    TaskService taskService;

    @MockBean
    @Qualifier("provisioningServerChannel")
    ManagedChannel channel;

    private UUID onPremId;
    private UUID onPrem2Id;

    private Organization onpreOrg;

    private ZoneService zoneService;

    private void setCallbacks(Answer answer) {
        doAnswer(answer).when(client)
                .listOrchestrationSites(any(ListOrchestrationSitesRequest.class), any(StreamObserver.class));
    }

    @BeforeEach
    void setUp() throws Exception {
        when(authHelper.hasAnyAuthority(anyString())).thenReturn(true);
        when(authHelper.getOrganizationId()).thenReturn(ONPREM_ORG);
        when(authHelper.getUserId()).thenReturn(USER_ID);
        when(authHelper.getEmail()).thenReturn(USER_EMAIL);

        onpreOrg = new Organization("TestOrg");
        onpreOrg.setId(ONPREM_ORG);
        when(organizationService.get(ONPREM_ORG)).thenReturn(onpreOrg);


        OrchestrationSiteView v1 = OrchestrationSiteView.newBuilder()
                .setId(OrchestrationSiteIdentifier.newBuilder()
                                .setLow(SITE_1.getLeastSignificantBits())
                                .setHigh(SITE_1.getMostSignificantBits())
                                .build())
                .setType(Type.VMC)
                .putAllLabels(ImmutableMap.of("name", "US_WEST"))
                .build();

        OrchestrationSiteView v2 = OrchestrationSiteView.newBuilder()
                .setId(OrchestrationSiteIdentifier.newBuilder()
                        .setLow(SITE_2.getLeastSignificantBits())
                        .setHigh(SITE_2.getMostSignificantBits())
                        .build())
                .setType(Type.NONE)
                .putAllLabels(ImmutableMap.of("name", "US_EAST"))
                .build();

        ListOrchestrationSitesResponse response = ListOrchestrationSitesResponse.newBuilder()
                .setHeader(MessageHeader.newBuilder().build())
                .addAllSites(ImmutableList.of(v1, v2))
                .build();

        setCallbacks(i -> {
            StreamObserver ob = i.getArgument(1);
            ob.onNext(response);
            ob.onCompleted();
            return null;
        });
        zoneService = new ZoneService(client, genericDao, organizationService, authHelper);

        OnpremZone ozone = getOnpremZone(ONPREM_ORG);
        OnpremZone ozone2 = getOnpremZone(ORG_2);

        zoneService.loadZones();
        Zone z = zoneService.put(ozone);
        onPremId = z.getId();
        z = zoneService.put(ozone2);
        onPrem2Id = z.getId();
    }

    @AfterEach
    void cleanup() {
        // Remove any entities we created
        testMapper.deleteEntity();
        testMapper.deleteEntityHistory();
        testMapper.deleteLink();
    }


    @Test
    void testLoad() throws Exception {
        List<Zone> l = zoneService.getZones();
        Assertions.assertEquals(4, l.size());
        Zone z1 = l.get(0);
        Zone z2 = l.get(1);
        Assertions.assertEquals(SITE_1, z1.getId());
        Assertions.assertEquals(VMC_AWS, z1.getType());
        Assertions.assertEquals("US_WEST", z1.getName());
        Assertions.assertEquals(SITE_2, z2.getId());
        Assertions.assertEquals(NONE, z2.getType());
        Assertions.assertEquals("US_EAST", z2.getName());
        Assertions.assertTrue(l.get(2) instanceof OnpremZone);
    }

    @Test
    void typeTest() throws Exception {
        List<Zone> l = zoneService.getByType(ON_PREM);
        Assertions.assertEquals(2, l.size());
    }

    @Test
    void onPremTest() throws Exception {
        List<OnpremZone> l = zoneService.getOnpremZones(ONPREM_ORG);
        Assertions.assertEquals(1, l.size());
    }

    @Test
    void vmcAwsTest() throws Exception {
        VmcAwsZone zone = new VmcAwsZone();
        zone.setType(VMC_AWS);
        zone.setName("SDDC 1");
        zoneService.put(zone);

        List<Zone> l = zoneService.getZones();
        Assertions.assertEquals(5, l.size());

        l = zoneService.getByType(VMC_AWS);
        Assertions.assertEquals(1, l.size());
        Assertions.assertTrue(l.get(0) instanceof VmcAwsZone);

    }

    @Test
    void testGetAllAuthorized() throws Exception {
        when(authHelper.isSystemAdmin()).thenReturn(false);
        List<Zone> l = zoneService.getAllAuthorized();
        // We should see the two loaded zones, and the one zone we have access to
        Assertions.assertEquals(3, l.size());
    }

    @Test
    void testGetAuthorized() throws Exception {
        when(authHelper.isSystemAdmin()).thenReturn(false);
        Zone zone = zoneService.getAuthorized(onPremId);
        Assertions.assertNotNull(zone);
    }

    @Test
    void testGetUnauthorized() throws Exception {
        when(authHelper.isSystemAdmin()).thenReturn(false);
        Assertions.assertThrows(NotFoundException.class, () -> zoneService.getAuthorized(onPrem2Id));
    }

    @Test
    void testGetAllAuthorizedProperties() throws Exception {
        onpreOrg.setOrganizationProperties(ImmutableMap.of(Constants.ORG_ZONES, "VMC_AWS, ON_PREM"));
        when(authHelper.isSystemAdmin()).thenReturn(false);
        List<Zone> l = zoneService.getAllAuthorized();
        // We should see one of the loaded zones, and the one zone we have access to
        Assertions.assertEquals(2, l.size());
    }

    @Test
    void testGetAuthorizedProperties() throws Exception {
        onpreOrg.setOrganizationProperties(ImmutableMap.of(Constants.ORG_ZONES, "VMC_AWS, ON_PREM"));
        when(authHelper.isSystemAdmin()).thenReturn(false);
        Zone zone = zoneService.getAuthorized(onPremId);
        Assertions.assertNotNull(zone);
    }

    @Test
    void testGetUnAuthorizedProperties() throws Exception {
        onpreOrg.setOrganizationProperties(ImmutableMap.of(Constants.ORG_ZONES, "VMC_AWS, ON_PREM"));
        when(authHelper.isSystemAdmin()).thenReturn(false);
        // We should not be able to see SITE_2, becuase we don't have access to that zone type
        Assertions.assertThrows(NotFoundException.class, () -> zoneService.getAuthorized(SITE_2));
    }


    @Test
    void getUuid() throws Exception {
        final UUID noSuchSite = UUID.fromString("0b51cd29-17cf-4baa-bfab-6bed938b1396");
        Zone z = zoneService.get(onPremId);
        Assertions.assertTrue(z instanceof OnpremZone);

        z = zoneService.get(SITE_1);
        Assertions.assertNotNull(z);
        Assertions.assertTrue(z instanceof Zone);
        Assertions.assertThrows(NotFoundException.class, () -> zoneService.get(noSuchSite));

    }

}