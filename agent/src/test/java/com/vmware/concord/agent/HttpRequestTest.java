/*
 * Copyright (c) 2019 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.concord.agent;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;


import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.protobuf.ProtobufHttpMessageConverter;
import org.springframework.test.context.junit.jupiter.SpringExtension;
//import org.springframework.test.web.client.RestTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;


import com.vmware.blockchain.agent.ConcordAgent;

/**
 *  Test for REST based Agent Controller.
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(secure = false, controllers = AgentRestController.class)
@ComponentScan(basePackageClasses = {HttpRequestTest.class })
public class HttpRequestTest {

    /**
     * For getting the Protobuf response converted to REST message.
     */
    @Configuration
    public static class RestClientConfiguration {

        //@Bean
        //RestTemplate restTemplate(ProtobufHttpMessageConverter msg) {
        //    return new RestTemplate(msg);
        //}

        @Bean
        ProtobufHttpMessageConverter protobufHttpMessageConverter() {
            return new ProtobufHttpMessageConverter();
        }
    }

    //@Autowired
    //private RestTemplate restTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentRestController agentController;

    /**
     * Initialize mocks.
     */
    @BeforeEach
    void setup() throws IOException {
        MockitoAnnotations.initMocks(this);
        agentController = mock(AgentRestController.class);
    }


    @Test
    public void getStatusOk() throws Exception {

        ConcordAgent.ConcordAgentStatus status = ConcordAgent.ConcordAgentStatus.newBuilder()
                           .setVersion(1)
                           .setStatus(ConcordAgent.ConcordAgentStatus.HealthStatus.GREEN)
                           .build();
        when(agentController.status()).thenReturn(status);
        mockMvc.perform(get("/api/status/"))
                            .andExpect(status().isOk()).andReturn();
    }

    @Test
    public void getRestStatusOk() throws Exception {

        ConcordAgent.ConcordAgentStatus status = ConcordAgent.ConcordAgentStatus.newBuilder()
                           .setVersion(1)
                           .setStatus(ConcordAgent.ConcordAgentStatus.HealthStatus.GREEN)
                           .build();
        when(agentController.status()).thenReturn(status);
        MvcResult result = mockMvc.perform(get("/api/status/")).andReturn();
        String response = result.getResponse().getContentAsString();
        System.out.println("String response: " + response);
    }
}