/*
 * Copyright (c) 2019 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.concord.agent.services.configuration;


import java.util.List;

import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Link;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

import lombok.Getter;
import lombok.Setter;

/**
 * Enumeration of [ContainerConfig] for known container images.
 * This should be ultimately moved to manifest.
 */
@Getter
public enum EthereumConfig implements BaseContainerSpec {

    CONCORD("concord", ConcordHelper.getDefaultPortBindings(),
            ConcordHelper.getDefaultVolBinds(),
            null, null, null),
    ETHEREUM_API("ethrpc",
            List.of(new PortBinding(Ports.Binding.bindPort(8545), ExposedPort.tcp(8545))),
            null,
            List.of(new Link("concord", "concord")), null,
            List.of("CONCORD_AUTHORITIES=concord:5458"));

    @Setter
    private String imageId;

    private String containerName;
    private List<PortBinding> portBindings;
    private List<Bind> volumeBindings;
    private List<Link> links;
    private List<String> cmds;
    private List<String> environment;

    EthereumConfig(String containerName,
                   List<PortBinding> portBindings, List<Bind> volumeBindings,
                   List<Link> links, List<String> cmds, List<String> environment) {
        this.containerName = containerName;
        this.portBindings = portBindings;
        this.volumeBindings = volumeBindings;
        this.links = links;
        this.cmds = cmds;
        this.environment = environment;
    }

}
