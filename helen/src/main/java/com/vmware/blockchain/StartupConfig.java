/*
 * Copyright (c) 2019 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain;

import java.io.IOException;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import com.vmware.blockchain.connections.ConnectionPoolManager;
import com.vmware.blockchain.services.profiles.Blockchain;
import com.vmware.blockchain.services.profiles.BlockchainManager;
import com.vmware.blockchain.services.profiles.DefaultProfiles;

/**
 * Handle the Startup event, and initialize things that need initializing.
 */
@Configuration
public class StartupConfig {

    private static final Logger logger = LogManager.getFormatterLogger(StartupConfig.class);

    private DefaultProfiles defaultProfiles;
    private BlockchainManager blockchainManger;
    private ConnectionPoolManager connectionPoolManager;

    @Autowired
    public StartupConfig(DefaultProfiles defaultProfiles, BlockchainManager blockchainManager,
            ConnectionPoolManager connectionPoolManager) {
        this.defaultProfiles = defaultProfiles;
        this.blockchainManger = blockchainManager;
        this.connectionPoolManager = connectionPoolManager;
    }

    /**
     * Perform initialization tasks after Application is ready.
     */
    @EventListener(classes = ApplicationStartedEvent.class)
    public void applicationStarted() {
        // Create a connection pool for all the blockchains
        List<Blockchain> blockchains = blockchainManger.list();
        for (Blockchain b : blockchains) {
            try {
                connectionPoolManager.createPool(b);
            } catch (IOException e) {
                logger.warn("Could not create connection pool for {}", b.getId());
            }
        }
        defaultProfiles.initialize();
    }

}