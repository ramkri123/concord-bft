/* **************************************************************************
 * Copyright (c) 2019 VMware, Inc. All rights reserved. VMware Confidential
 * **************************************************************************/
package com.vmware.blockchain.deployment.service.provision

import com.vmware.blockchain.deployment.orchestration.OrchestratorProvider
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
class TestOrchestratorModule {

    /**
     * Provide an [OrchestratorProvider] instance.
     *
     * @return
     *   a singleton [OrchestratorProvider] instance.
     */
    @Provides
    @Singleton
    fun providesOrchestratorProvider(): OrchestratorProvider {
        return TestOrchestratorFactory()
    }
}