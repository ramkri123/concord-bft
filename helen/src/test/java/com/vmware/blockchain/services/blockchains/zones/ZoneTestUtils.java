/*
 * Copyright (c) 2019 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.services.blockchains.zones;

import static com.vmware.blockchain.services.blockchains.zones.Zone.Network.builder;
import static com.vmware.blockchain.services.blockchains.zones.Zone.Type.ON_PREM;

import java.util.UUID;

import com.google.common.collect.ImmutableList;

/**
 * Test utilities for Zone.
 */
public class ZoneTestUtils {

    /**
     * create an on Prem zone.
     */
    public static  OnpremZone getOnpremZone(UUID id, UUID orgId) {
        // Now construct an onprem zone
        OnpremZone ozone = new OnpremZone();
        ozone.setId(id);
        ozone.setType(ON_PREM);
        ozone.setName("On Prem");
        ozone.setOrgId(orgId);
        ozone.setVCenter(new OnpremZone.EndPoint("http://vcenter", "admin", "password"));
        ozone.setResourcePool("pool");
        ozone.setStorage("datastore");
        ozone.setFolder("folder");
        ozone.setNetwork(builder()
                                 .name("Network 1")
                                 .ipPool(ImmutableList.of("10.1.1.16-10.1.1.64", "10.1.1.100-10.1.1.200"))
                                 .subnet("24")
                                 .gateway("10.1.1.1")
                                 .nameServers(ImmutableList.of("10.1.1.3"))
                                 .build());
        ozone.setContainerRepo(new OnpremZone.EndPoint("docker-repo", "user", "docker"));
        return ozone;
    }

    public static  OnpremZone getOnpremZone(UUID orgId) {
        return getOnpremZone(null, orgId);
    }



}