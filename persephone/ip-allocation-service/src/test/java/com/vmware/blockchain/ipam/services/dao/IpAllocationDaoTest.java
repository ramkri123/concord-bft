/*
 * Copyright (c) 2016 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.ipam.services.dao;

import static org.mockito.Mockito.when;

import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.vmware.blockchain.base.auth.BaseAuthHelper;
import com.vmware.blockchain.common.NotFoundException;
import com.vmware.blockchain.dao.GenericDao;
import com.vmware.blockchain.dao.TestDaoConfig;
import com.vmware.blockchain.db.DbConfig;
import com.vmware.blockchain.db.mapper.TestMapper;
import com.vmware.blockchain.ipam.services.BlockSpecification;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;


/**
 * Test the IPAM.
 */
@ExtendWith(SpringExtension.class)
@TestPropertySource(locations = "classpath:db-postgres-test.properties")
@ComponentScan(basePackageClasses = {GenericDao.class})
@EnableAutoConfiguration
@AutoConfigureEmbeddedDatabase
@ContextConfiguration(classes = {DbConfig.class, TestDaoConfig.class })
class IpAllocationDaoTest {
    private static final UUID ADDRESS_BLOCK_1 = UUID.fromString("88907046-715b-4486-a6c0-4ff01fc28ff1");
    private static final UUID ADDRESS_BLOCK_2 = UUID.fromString("6c16b776-4285-4ad6-8655-1c7c028cf18e");

    private static final UUID ADDRESS_BLOCK_SEGMENT_1 = UUID.fromString("903622c7-85f2-4f0f-a85c-8644dda58ef0");
    private static final UUID ADDRESS_BLOCK_SEGMENT_2 = UUID.fromString("b00d5aab-97bb-4ef1-9310-680fd6feffff");

    private static final UUID BAD_ADDRESS_BLOCK = UUID.fromString("3aa24e10-3c5d-44fb-9cde-bd17dc275d60");
    private static final UUID BAD_ADDRESS_BLOCK_SEGMENT = UUID.fromString("aeb8400f-6580-4bc5-82ab-b434bc179ad4");

    private Random rd = new Random();

    private AddressBlock addressBlock1;
    private AddressBlock addressBlock2;

    private AddressBlockSegment addressBlockSegment1;
    private AddressBlockSegment addressBlockSegment2;

    private UUID entityId;

    private IpAllocationDao ipAllocationDao;

    @Autowired
    GenericDao genericDao;

    @Autowired
    TestMapper testMapper;

    @MockBean
    BaseAuthHelper authHelper;

    @BeforeEach
    void setUp() {
        UUID userId;

        ipAllocationDao = new IpAllocationDao(this.genericDao);
        userId = UUID.fromString("7a833e12-6de8-4525-b55e-7cd482f6846e");
        when(authHelper.getUserId()).thenReturn(userId);
        when(authHelper.getEmail()).thenReturn("mockuser");

        addressBlock1 = new AddressBlock("AB1", new BlockSpecification(0, 0), AddressBlock.State.ACTIVE);
        addressBlock2 = new AddressBlock("AB2", new BlockSpecification(0, 0), AddressBlock.State.ACTIVE);

        addressBlock1.setId(ADDRESS_BLOCK_1);
        addressBlock2.setId(ADDRESS_BLOCK_2);

        addressBlock1 = ipAllocationDao.createAddressBlock(addressBlock1);

        byte[] arr = new byte[20];

        rd.nextBytes(arr);
        addressBlockSegment1 = new AddressBlockSegment("ABS1", 0, arr);
        rd.nextBytes(arr);
        addressBlockSegment2 = new AddressBlockSegment("ABS2", 0, arr);

        addressBlockSegment1.setId(ADDRESS_BLOCK_SEGMENT_1);
        addressBlockSegment2.setId(ADDRESS_BLOCK_SEGMENT_2);

        addressBlockSegment1 = ipAllocationDao.createAddressBlockSegment(addressBlockSegment1);
    }

    @AfterEach
    void cleanup() {
        // Remove any entities we created
        testMapper.deleteEntity();
        testMapper.deleteEntityHistory();
        testMapper.deleteLink();
    }

    /**
     * Make sure the we get the requested AddressBlock.
     */
    @Test
    void testSimpleGetAddressBlock() {
        entityId = addressBlock1.getId();
        AddressBlock addressBlock = ipAllocationDao.getAddressBlock(entityId);
        String name = addressBlock.getName();
        Assertions.assertEquals("AB1", name);
    }

    /**
     * Make sure the we get the requested AddressBlockSegment.
     */
    @Test
    void testSimpleGetAddressBlockSegment() {
        entityId = addressBlockSegment1.getId();
        AddressBlockSegment addressBlockSegment = ipAllocationDao.getAddressBlockSegment(entityId);
        String name = addressBlockSegment.getName();
        Assertions.assertEquals("ABS1", name);
    }

    /**
     * Make sure the AddressBlock we create is properly created.
     */
    @Test
    void testSimplePutAddressBlock() {
        AddressBlock addressBlock = ipAllocationDao.createAddressBlock(addressBlock2);

        Assertions.assertEquals(addressBlock.getName(), addressBlock2.getName());
        Assertions.assertEquals(addressBlock.getSpecification(), addressBlock2.getSpecification());
        Assertions.assertEquals(addressBlock.getState(), addressBlock2.getState());

        Assertions.assertNotNull(addressBlock.getCreated());
        Assertions.assertNotNull(addressBlock.getUpdated());
        Assertions.assertNotNull(addressBlock.getId());
    }

    /**
     * Make sure the AddressBlockSegment we create is properly created.
     */
    @Test
    void testSimplePutAddressBlockSegment() {
        AddressBlockSegment addressBlockSegment = ipAllocationDao.createAddressBlockSegment(addressBlockSegment2);

        Assertions.assertEquals(addressBlockSegment.getName(), addressBlockSegment2.getName());
        Assertions.assertEquals(addressBlockSegment.getSegment(), addressBlockSegment2.getSegment());
        Assertions.assertEquals(addressBlockSegment.getAllocations(), addressBlockSegment2.getAllocations());

        Assertions.assertNotNull(addressBlockSegment.getCreated());
        Assertions.assertNotNull(addressBlockSegment.getUpdated());
        Assertions.assertNotNull(addressBlockSegment.getId());
    }

    /**
     * Make sure the we properly update a AddressBlock.
     */
    @Test
    void testUpdateAddressBlock() {
        int version = addressBlock1.getVersion();
        addressBlock1.setName("Pastel*Palettes");

        addressBlock1 = ipAllocationDao.updateAddressBlock(addressBlock1);

        Assertions.assertEquals(version + 1, addressBlock1.getVersion());
        Assertions.assertEquals("Pastel*Palettes", addressBlock1.getName());
    }

    /**
     * Make sure the we properly update a AddressBlockSegment.
     */
    @Test
    void testUpdateAddressBlockSegment() {
        int version = addressBlockSegment1.getVersion();
        addressBlockSegment1.setName("Hakumei Parallel");

        addressBlockSegment1 = ipAllocationDao.updateAddressBlockSegment(addressBlockSegment1);

        Assertions.assertEquals(version + 1, addressBlockSegment1.getVersion());
        Assertions.assertEquals("Hakumei Parallel", addressBlockSegment1.getName());
    }

    /**
     * Make sure AddressBlock we want to delete is deleted.
     */
    @Test
    void testSimpleDeleteAddressBlock() {
        UUID addressBlockId = addressBlock1.getId();
        ipAllocationDao.deleteAddressBlock(addressBlockId);

        Assertions.assertNull(ipAllocationDao.getAddressBlock(addressBlockId));
    }

    /**
     * Make sure AddressBlockSegment we want to delete is deleted.
     */
    @Test
    void testSimpleDeleteAddressBlockSegment() {
        UUID absId = addressBlockSegment1.getId();
        ipAllocationDao.deleteAddressBlockSegment(absId);

        Assertions.assertThrows(NotFoundException.class, () -> ipAllocationDao.getAddressBlockSegment(absId));
    }

    /**
     * Make sure that we get a null if no AddressBlock is present.
     */
    @Test
    void testGetNotAvailableAddressBlock() {
        AddressBlock addressBlock = ipAllocationDao.getAddressBlock(BAD_ADDRESS_BLOCK);

        Assertions.assertNull(addressBlock);
    }

    /**
     * Make sure that we get a null if no AddressBlockSegment is present.
     */
    @Test
    void testGetNotAvailableAddressBlockSegment() {
        Assertions
                .assertThrows(NotFoundException.class,
                    () -> ipAllocationDao.getAddressBlockSegment(BAD_ADDRESS_BLOCK_SEGMENT));
    }

    /**
     * Make sure the we get a null if no AddressBlockSegment is present.
     */
    @Test
    void testDeleteUnavailableAddressBlock() {
        Assertions.assertThrows(NotFoundException.class, () -> ipAllocationDao.deleteAddressBlock(BAD_ADDRESS_BLOCK));
    }

    /**
     * Make sure the we get a null if no AddressBlockSegment is present.
     */
    @Test
    void testDeleteUnavailableAddressBlockSegment() {
        Assertions.assertThrows(
                NotFoundException.class,
            () -> ipAllocationDao.deleteAddressBlockSegment(BAD_ADDRESS_BLOCK_SEGMENT)
        );
    }
}