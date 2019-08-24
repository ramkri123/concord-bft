/*
 * Copyright (c) 2019 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.services.blockchains;

import static com.vmware.blockchain.security.MvcTestSecurityConfig.createContext;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Assert;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.vmware.blockchain.MvcConfig;
import com.vmware.blockchain.auth.AuthHelper;
import com.vmware.blockchain.auth.AuthenticationContext;
import com.vmware.blockchain.common.HelenExceptionHandler;
import com.vmware.blockchain.common.NotFoundException;
import com.vmware.blockchain.common.fleetmanagment.FleetUtils;
import com.vmware.blockchain.deployment.v1.ConcordCluster;
import com.vmware.blockchain.deployment.v1.ConcordClusterIdentifier;
import com.vmware.blockchain.deployment.v1.ConcordClusterInfo;
import com.vmware.blockchain.deployment.v1.ConcordModelIdentifier;
import com.vmware.blockchain.deployment.v1.ConcordModelSpecification;
import com.vmware.blockchain.deployment.v1.ConcordNode;
import com.vmware.blockchain.deployment.v1.ConcordNodeEndpoint;
import com.vmware.blockchain.deployment.v1.ConcordNodeHostInfo;
import com.vmware.blockchain.deployment.v1.ConcordNodeIdentifier;
import com.vmware.blockchain.deployment.v1.ConcordNodeInfo;
import com.vmware.blockchain.deployment.v1.ConcordNodeStatus;
import com.vmware.blockchain.deployment.v1.CreateClusterRequest;
import com.vmware.blockchain.deployment.v1.DeploymentSession.Status;
import com.vmware.blockchain.deployment.v1.DeploymentSessionEvent;
import com.vmware.blockchain.deployment.v1.DeploymentSessionEvent.Type;
import com.vmware.blockchain.deployment.v1.DeploymentSessionIdentifier;
import com.vmware.blockchain.deployment.v1.OrchestrationSiteIdentifier;
import com.vmware.blockchain.deployment.v1.PlacementSpecification;
import com.vmware.blockchain.deployment.v1.ProvisionedResource;
import com.vmware.blockchain.deployment.v1.ProvisioningServiceStub;
import com.vmware.blockchain.deployment.v1.StreamClusterDeploymentSessionEventRequest;
import com.vmware.blockchain.operation.OperationContext;
import com.vmware.blockchain.security.MvcTestSecurityConfig;
import com.vmware.blockchain.security.SecurityTestUtils;
import com.vmware.blockchain.services.blockchains.BlockchainController.BlockchainNodeEntry;
import com.vmware.blockchain.services.blockchains.BlockchainController.BlockchainReplicaEntry;
import com.vmware.blockchain.services.blockchains.BlockchainController.BlockchainTaskResponse;
import com.vmware.blockchain.services.concord.ConcordService;
import com.vmware.blockchain.services.profiles.Consortium;
import com.vmware.blockchain.services.profiles.ConsortiumService;
import com.vmware.blockchain.services.profiles.DefaultProfiles;
import com.vmware.blockchain.services.profiles.Roles;
import com.vmware.blockchain.services.profiles.User;
import com.vmware.blockchain.services.profiles.UserService;
import com.vmware.blockchain.services.tasks.Task;
import com.vmware.blockchain.services.tasks.Task.State;
import com.vmware.blockchain.services.tasks.TaskController;
import com.vmware.blockchain.services.tasks.TaskService;

import io.grpc.stub.StreamObserver;

/**
 * Tests for the blockchain controller.
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(controllers = { BlockchainController.class, TaskController.class })
@ContextConfiguration(classes = {MvcTestSecurityConfig.class, MvcConfig.class})
@ComponentScan(basePackageClasses = { BlockchainControllerTest.class, HelenExceptionHandler.class,
        TaskController.class })
public class BlockchainControllerTest {
    static final UUID BC_ID = UUID.fromString("437d97b2-76df-4596-b0d8-3d8a9412ff2f");
    static final UUID BC2_ID = UUID.fromString("7324cb8f-0ffc-4311-b57e-4c3e1e10a3aa");
    static final UUID BC_NEW = UUID.fromString("4b8a5ec6-91ad-437d-b574-45f5b7345b96");
    static final UUID C2_ID = UUID.fromString("04e4f62d-5364-4363-a582-b397075b65a3");
    static final UUID C3_ID = UUID.fromString("a4b8f7ed-00b3-451e-97bc-4aa51a211288");
    static final UUID TASK_ID = UUID.fromString("c23ed97d-f29c-472e-9f63-cc6be883a5f5");
    static final UUID ORG_ID = UUID.fromString("5c373085-0cd1-47e4-b4f2-66d418f22fdf");
    static final UUID ORG2_ID = UUID.fromString("a774d0e3-b182-4330-93df-6738c8b1b2de");
    static final UUID DEP_ID = UUID.fromString("67376aed-333c-4e35-b6b6-c59800752dc3");
    private static final UUID SITE_1 = UUID.fromString("84b9a0ed-c162-446a-b8c0-2e45755f3844");
    private static final UUID SITE_2 = UUID.fromString("275638a3-8860-4925-85de-c73d45cb7232");
    private static final UUID NODE_1 = UUID.fromString("f81899ce-861f-4479-9adf-f3ad753fcaf6");
    private static final UUID NODE_2 = UUID.fromString("81a70aeb-c13c-4f36-9e98-564c1e6eccdc");

    // use consortium c2 in this, Unspecified placement
    static final String POST_BODY_UNSP = "{"
                                         + "    \"consortium_id\": \"04e4f62d-5364-4363-a582-b397075b65a3\","
                                         + "    \"f_count\": 1,"
                                         + "    \"c_count\": 0,"
                                         + "    \"deployment_type\": \"UNSPECIFIED\"" + "}";

    // Fixed placement.  three in site1, one is site 2
    static final String POST_BODY_FIXED = "{"
                                         + "    \"consortium_id\": \"04e4f62d-5364-4363-a582-b397075b65a3\","
                                         + "    \"f_count\": 1,"
                                         + "    \"c_count\": 0,"
                                         + "    \"deployment_type\": \"FIXED\","
                                         + "    \"zone_ids\": ["
                                         + "            \"84b9a0ed-c162-446a-b8c0-2e45755f3844\","
                                         + "            \"84b9a0ed-c162-446a-b8c0-2e45755f3844\","
                                         + "            \"275638a3-8860-4925-85de-c73d45cb7232\","
                                         + "            \"84b9a0ed-c162-446a-b8c0-2e45755f3844\"]" + "}";

    static final String POST_BODY_FIXED_WITH_TYPE = "{"
                                          + "    \"consortium_id\": \"04e4f62d-5364-4363-a582-b397075b65a3\","
                                          + "    \"f_count\": 1,"
                                          + "    \"c_count\": 0,"
                                          + "    \"deployment_type\": \"FIXED\","
                                          + "    \"blockchain_type\": \"DAML\","
                                          + "    \"zone_ids\": ["
                                          + "            \"84b9a0ed-c162-446a-b8c0-2e45755f3844\","
                                          + "            \"84b9a0ed-c162-446a-b8c0-2e45755f3844\","
                                          + "            \"275638a3-8860-4925-85de-c73d45cb7232\","
                                          + "            \"84b9a0ed-c162-446a-b8c0-2e45755f3844\"]" + "}";

    // Bad placement, wrong number of sites
    static final String POST_BODY_BAD = "{"
                                          + "    \"consortium_id\": \"04e4f62d-5364-4363-a582-b397075b65a3\","
                                          + "    \"f_count\": 1,"
                                          + "    \"c_count\": 0,"
                                          + "    \"deployment_type\": \"FIXED\","
                                          + "    \"zone_ids\": ["
                                          + "            \"84b9a0ed-c162-446a-b8c0-2e45755f3844\","
                                          + "            \"275638a3-8860-4925-85de-c73d45cb7232\","
                                          + "            \"275638a3-8860-4925-85de-c73d45cb7232\"]" + "}";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @MockBean
    DefaultProfiles defaultProfiles;

    @Autowired
    Jackson2ObjectMapperBuilder jacksonBuilder;

    @MockBean
    UserService userService;

    @MockBean
    ConsortiumService consortiumService;

    @MockBean
    BlockchainService blockchainService;

    @MockBean
    ProvisioningServiceStub client;

    @MockBean
    OperationContext operationContext;

    @MockBean
    ConcordService concordService;

    @Autowired
    TaskService taskService;

    @Autowired
    AuthHelper authHelper;

    private User user;
    private Consortium consortium;
    private ObjectMapper objectMapper;

    private AuthenticationContext adminAuth;
    private AuthenticationContext consortiumAuth;
    private AuthenticationContext userAuth;
    private AuthenticationContext user2Auth;

    private DeploymentSessionIdentifier dsId;

    private void setCreateCluster(Answer answer) {
        doAnswer(answer).when(client)
                .createCluster(any(CreateClusterRequest.class), any(StreamObserver.class));
    }

    private void setStreamCluster(Answer answer) {
        doAnswer(answer).when(client)
                .streamClusterDeploymentSessionEvents(any(StreamClusterDeploymentSessionEventRequest.class),
                                                      any(StreamObserver.class));
    }

    /**
     * A mockito Answer that saves the result of the mock, so we can
     * look at it later.
     */
    static class ResultAnswer<T> implements Answer {
        T result;
        Function<InvocationOnMock, T> function;

        public ResultAnswer(Function<InvocationOnMock, T> function) {
            this.function = function;
        }

        public T getResult() {
            return result;
        }

        @Override
        public Object answer(InvocationOnMock invocation) throws Throwable {
            result = function.apply(invocation);
            return result;
        }
    }

    private ResultAnswer<Blockchain> blockchainResultAnswer;

    /**
     * Initialize various mocks.
     */
    @BeforeEach
    public void init() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        user = SecurityTestUtils.getUser();
        consortium = SecurityTestUtils.getConsortium();
        Consortium c2 = new Consortium();
        c2.setId(C2_ID);
        Consortium c3 = new Consortium();
        c3.setId(C3_ID);
        UUID c1Id = consortium.getId();
        when(userService.getByEmail(user.getEmail())).thenReturn(user);
        when(consortiumService.get(c1Id)).thenReturn(consortium);
        when(consortiumService.get(C2_ID)).thenReturn(c2);
        when(consortiumService.get(C3_ID)).thenReturn(c3);

        final Blockchain b = Blockchain.builder()
                .consortium(consortium.getId())
                .nodeList(Stream.of("1", "2", "3")
                        .map(s -> new Blockchain.NodeEntry(UUID.randomUUID(), s, "", "", SITE_1))
                        .collect(Collectors.toList()))
                .build();
        final Blockchain b2 = Blockchain.builder()
                .consortium(c2.getId())
                .nodeList(Stream.of("4", "5", "6")
                        .map(s -> new Blockchain.NodeEntry(UUID.randomUUID(), s, "", "", SITE_2))
                        .collect(Collectors.toList()))
                .build();
        final Blockchain bn = Blockchain.builder()
                .consortium(UUID.fromString("04e4f62d-5364-4363-a582-b397075b65a3"))
                .nodeList(Stream.of("one", "two", "three")
                        .map(s -> new Blockchain.NodeEntry(UUID.randomUUID(), s, "http://".concat(s), "cert-".concat(s), SITE_2))
                        .collect(Collectors.toList()))
                .build();
        when(blockchainService.listByConsortium(consortium)).thenReturn(Collections.singletonList(b));
        when(blockchainService.listByConsortium(c3)).thenReturn(Collections.emptyList());
        when(blockchainService.list()).thenReturn(ImmutableList.of(b, b2));
        b.setId(BC_ID);
        b2.setId(BC2_ID);
        bn.setId(BC_NEW);
        when(blockchainService.get(BC_ID)).thenReturn(b);
        when(blockchainService.get(BC2_ID)).thenReturn(b2);
        when(blockchainService.get(BC_NEW)).thenReturn(bn);
        when(blockchainService.get(C2_ID)).thenThrow(new NotFoundException("Not found"));
        when(blockchainService.listByIds(any(List.class))).thenAnswer(i -> {
            return ((List<UUID>) i.getArgument(0)).stream().map(blockchainService::get).collect(Collectors.toList());
        });
        blockchainResultAnswer = new ResultAnswer<>(i -> {
            Blockchain bc = new Blockchain.BlockchainBuilder()
                    .consortium(i.getArgument(1))
                    .nodeList(i.getArgument(2))
                    .build();
            bc.setId(i.getArgument(0));
            return bc;
        });
        when(blockchainService.create(any(UUID.class), any(UUID.class), any(List.class)))
                .thenAnswer(blockchainResultAnswer);
        when(defaultProfiles.getBlockchain()).thenReturn(bn);
        Task t = new Task();
        t.setId(TASK_ID);
        t.setState(State.SUCCEEDED);
        t.setMessage("Done");
        t.setResourceId(BC_NEW);
        when(taskService.put(any())).thenReturn(t);
        when(taskService.get(TASK_ID)).thenReturn(t);
        // This creates our default object mapper
        objectMapper = jacksonBuilder.build();

        // Create authorizations for the different users.
        adminAuth = createContext("operator", ORG_ID,
                                  ImmutableList.of(Roles.SYSTEM_ADMIN, Roles.ORG_USER),
                                  ImmutableList.of(C2_ID),
                                  ImmutableList.of(BC_ID), "");

        consortiumAuth = createContext("consortium", ORG_ID,
                                  ImmutableList.of(Roles.CONSORTIUM_ADMIN, Roles.ORG_USER),
                                  Collections.emptyList(),
                                  Collections.emptyList(), "");

        userAuth = createContext("operator", ORG_ID,
                                 ImmutableList.of(Roles.ORG_USER),
                                 ImmutableList.of(C2_ID),
                                 ImmutableList.of(BC_ID), "");

        user2Auth = createContext("operator", ORG2_ID,
                                 ImmutableList.of(Roles.ORG_USER),
                                 ImmutableList.of(C3_ID),
                                 Collections.emptyList(), "");

        dsId = FleetUtils.identifier(DeploymentSessionIdentifier.class, DEP_ID);
        setCreateCluster(i -> {
            StreamObserver ob = i.getArgument(1);
            ob.onNext(dsId);
            ob.onCompleted();
            return null;
        });
    }


    private boolean compareNodeToReplica(BlockchainNodeEntry nodeEntry, BlockchainReplicaEntry replicaEntry) {
        return nodeEntry.getNodeId().equals(replicaEntry.getReplicaId())
               && nodeEntry.getIp().equals(replicaEntry.getIp())
               && nodeEntry.getUrl().equals(replicaEntry.getUrl())
               && nodeEntry.getCert().equals(replicaEntry.getCert())
               && Objects.equals(nodeEntry.getZoneId(), replicaEntry.getZoneId());
    }

    private void assertEquivalentLists(List<BlockchainNodeEntry> nodeList, List<BlockchainReplicaEntry> replicaList) {
        Assertions.assertEquals(nodeList.size(), replicaList.size());
        for (int i = 0; i < replicaList.size(); i++) {
            Assertions.assertTrue(compareNodeToReplica(nodeList.get(i), replicaList.get(i)));
        }
    }

    @Test
    void getBlockchainOperatorList() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/blockchains/").with(authentication(adminAuth))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
        String body = result.getResponse().getContentAsString();
        List<BlockchainController.BlockchainGetResponse> res =
                objectMapper.readValue(body, new TypeReference<List<BlockchainController.BlockchainGetResponse>>() {});
        // As an operator, we should see both blockchains.
        Assertions.assertEquals(2, res.size());
        // Now check that the lists are equivalent
        assertEquivalentLists(res.get(0).getNodeList(), res.get(0).getReplicaList());
        assertEquivalentLists(res.get(1).getNodeList(), res.get(1).getReplicaList());
    }

    @Test
    void getBlockchainUserList() throws Exception {
        UUID cid = consortium.getId();
        MvcResult result = mockMvc.perform(get("/api/blockchains/").with(authentication(userAuth))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
        String body = result.getResponse().getContentAsString();
        List<BlockchainController.BlockchainGetResponse> res = objectMapper.readValue(body, new TypeReference<>() {});
        // As a user in this consortium, we should only see one blockchain
        Assertions.assertEquals(1, res.size());
    }

    @Test
    void getBlockchainUser2List() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/blockchains/").with(authentication(user2Auth))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
        String body = result.getResponse().getContentAsString();
        List<BlockchainController.BlockchainGetResponse> res = objectMapper.readValue(body, new TypeReference<>() {});
        // As a user in this consortium, we should only see one blockchain
        Assertions.assertEquals(0, res.size());
    }

    @Test
    void getBlockchainOperator() throws Exception {
        mockMvc.perform(get("/api/blockchains/" + BC_ID.toString()).with(authentication(adminAuth))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void getBlockchainOperatorNotFound() throws Exception {
        // There is no such blockchain.
        mockMvc.perform(get("/api/blockchains/" + C2_ID.toString()).with(authentication(adminAuth))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void getBlockchainUser() throws Exception {
        mockMvc.perform(get("/api/blockchains/" + BC_ID.toString()).with(authentication(userAuth))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void getBlockchainOperatorBc2() throws Exception {
        mockMvc.perform(get("/api/blockchains/" + BC2_ID.toString()).with(authentication(adminAuth))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void getBlockchainUserBc2() throws Exception {
        mockMvc.perform(get("/api/blockchains/" + BC2_ID.toString()).with(authentication(userAuth))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void getBlockchainNoAccess() throws Exception {
        mockMvc.perform(get("/api/blockchains/" + BC_ID.toString())
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postUserAccess() throws Exception {
        mockMvc.perform(post("/api/blockchains").with(authentication(userAuth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(POST_BODY_UNSP)
                .characterEncoding("utf-8"))
            .andExpect(status().isForbidden());
    }

    @Test
    void createUnspecified() throws Exception {
        ArgumentCaptor<CreateClusterRequest> captor = ArgumentCaptor.forClass(CreateClusterRequest.class);
        mockMvc.perform(post("/api/blockchains").with(authentication(consortiumAuth))
                                                   .contentType(MediaType.APPLICATION_JSON)
                                                   .content(POST_BODY_UNSP).characterEncoding("utf-8"))
                .andExpect(status().isAccepted());
        verify(client).createCluster(captor.capture(), any(StreamObserver.class));
        CreateClusterRequest request = captor.getValue();
        Assertions.assertEquals(4, request.getSpecification().getClusterSize());
        List<PlacementSpecification.Entry> entries = request.getSpecification().getPlacement().getEntries();
        Assertions.assertEquals(4, entries.size());
        Assertions.assertTrue(entries.stream().allMatch(e -> e.getType() == PlacementSpecification.Type.UNSPECIFIED));
    }

    @Test
    void createFixed() throws Exception {
        ArgumentCaptor<CreateClusterRequest> captor = ArgumentCaptor.forClass(CreateClusterRequest.class);
        mockMvc.perform(post("/api/blockchains").with(authentication(consortiumAuth))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(POST_BODY_FIXED).characterEncoding("utf-8"))
                .andExpect(status().isAccepted());
        verify(client).createCluster(captor.capture(), any(StreamObserver.class));
        CreateClusterRequest request = captor.getValue();
        Assertions.assertEquals(4, request.getSpecification().getClusterSize());
        List<PlacementSpecification.Entry> entries = request.getSpecification().getPlacement().getEntries();
        Assertions.assertEquals(4, entries.size());
        Assertions.assertTrue(entries.stream().allMatch(e -> e.getType() == PlacementSpecification.Type.FIXED));
        Assertions.assertEquals(3, entries.stream()
                .filter(e -> FleetUtils.identifier(OrchestrationSiteIdentifier.class, SITE_1).equals(e.getSite()))
                .count());
        Assertions.assertEquals(1, entries.stream()
                .filter(e -> FleetUtils.identifier(OrchestrationSiteIdentifier.class, SITE_2).equals(e.getSite()))
                .count());
    }

    @Test
    void createFixedOfTypeDaml() throws Exception {
        ArgumentCaptor<CreateClusterRequest> captor = ArgumentCaptor.forClass(CreateClusterRequest.class);
        mockMvc.perform(post("/api/blockchains").with(authentication(consortiumAuth))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(POST_BODY_FIXED_WITH_TYPE).characterEncoding("utf-8"))
                .andExpect(status().isAccepted());
        verify(client).createCluster(captor.capture(), any(StreamObserver.class));
        CreateClusterRequest request = captor.getValue();
        Assertions.assertEquals(4, request.getSpecification().getClusterSize());
        List<PlacementSpecification.Entry> entries = request.getSpecification().getPlacement().getEntries();
        Assertions.assertEquals(4, entries.size());
        Assertions.assertTrue(entries.stream().allMatch(e -> e.getType() == PlacementSpecification.Type.FIXED));
        Assertions.assertEquals(3, entries.stream()
                .filter(e -> FleetUtils.identifier(OrchestrationSiteIdentifier.class, SITE_1).equals(e.getSite()))
                .count());
        Assertions.assertEquals(1, entries.stream()
                .filter(e -> FleetUtils.identifier(OrchestrationSiteIdentifier.class, SITE_2).equals(e.getSite()))
                .count());
    }

    @Test
    void testGetComponentsByBlockchainType() {
        BlockchainController blockchainController = mock(BlockchainController.class);
        when(blockchainController.getComponentsByBlockchainType(any())).thenCallRealMethod();

        var output = blockchainController.getComponentsByBlockchainType(ConcordModelSpecification.BlockchainType.DAML);
        Assert.assertEquals(output.size(), 4);

        output = blockchainController.getComponentsByBlockchainType(ConcordModelSpecification.BlockchainType.ETHEREUM);
        Assert.assertEquals(output.size(), 3);

        output = blockchainController.getComponentsByBlockchainType(ConcordModelSpecification.BlockchainType.HLF);
        Assert.assertEquals(output.size(), 3);
    }

    @Test
    void creatBad() throws Exception {
        mockMvc.perform(post("/api/blockchains").with(authentication(consortiumAuth))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(POST_BODY_BAD).characterEncoding("utf-8"))
                .andExpect(status().isBadRequest());

    }

    @Test
    void postCreateCluster() throws Exception {
        // Build the data structure DeploymentService will return, and wire the mock to return it.
        // Note that this isn't really the cluster we would get from the POST.
        List<ConcordNode> members = ImmutableList.of(
            buildNode(NODE_1, SITE_1, 1, "http:/node1"),
            buildNode(NODE_2, SITE_2, 2, "http://node2")
        );
        ConcordCluster cluster = buildCluster(BC_NEW, members);
        setStreamCluster(i -> {
            StreamObserver ob = i.getArgument(1);
            ob.onNext(buildEvent(Type.CLUSTER_DEPLOYED, cluster, Status.ACTIVE));
            ob.onNext(buildEvent(Type.COMPLETED, cluster, Status.SUCCESS));
            ob.onCompleted();
            return null;
        });

        MvcResult result = mockMvc.perform(post("/api/blockchains").with(authentication(consortiumAuth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(POST_BODY_UNSP).characterEncoding("utf-8"))
            .andExpect(status().isAccepted()).andReturn();
        String body = result.getResponse().getContentAsString();

        BlockchainController.BlockchainTaskResponse t = objectMapper.readValue(body, BlockchainTaskResponse.class);
        Assertions.assertEquals(TASK_ID, t.getTaskId());

        result = mockMvc.perform(get("/api/tasks/" + TASK_ID.toString())
                .with(authentication(consortiumAuth))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk()).andReturn();

        body = result.getResponse().getContentAsString();
        Map<String, String> r = objectMapper.readValue(body, new TypeReference<Map<String, String>>() {});
        Assertions.assertEquals(TASK_ID.toString(), r.get("task_id"));
        Assertions.assertEquals("SUCCEEDED", r.get("state"));
        Assertions.assertEquals("Operation finished", r.get("message"));
        Assertions.assertEquals(BC_NEW.toString(), r.get("resource_id"));
        Blockchain blockchain = blockchainResultAnswer.getResult();
        Assertions.assertEquals(BC_NEW, blockchain.getId());
        Assertions.assertEquals(2, blockchain.getNodeList().size());
        Assertions.assertEquals(NODE_1, blockchain.getNodeList().get(0).getNodeId());
        Assertions.assertEquals(SITE_1, blockchain.getNodeList().get(0).getZoneId());
        Assertions.assertEquals(NODE_2, blockchain.getNodeList().get(1).getNodeId());
        Assertions.assertEquals(SITE_2, blockchain.getNodeList().get(1).getZoneId());

    }

    ConcordNode buildNode(UUID nodeId, UUID siteId, int ip, String url) {
        ConcordNodeEndpoint endpoint = new ConcordNodeEndpoint(url, "cert");
        ConcordNodeHostInfo hostInfo =
                new ConcordNodeHostInfo(FleetUtils.identifier(OrchestrationSiteIdentifier.class, siteId),
                                        ImmutableMap.of(ip, ip),
                                        ImmutableMap.of("ethereum-rpc", endpoint));
        ConcordNodeInfo nodeInfo = new ConcordNodeInfo(new ConcordModelIdentifier(1, 0),
                                                       ImmutableMap.of("ip", ip));
        return new ConcordNode(FleetUtils.identifier(ConcordNodeIdentifier.class, nodeId),
                                           nodeInfo, hostInfo);
    }

    ConcordCluster buildCluster(UUID clusterId, List<ConcordNode> members) {
        ConcordClusterInfo info = new ConcordClusterInfo(members);
        return new ConcordCluster(FleetUtils.identifier(ConcordClusterIdentifier.class, clusterId), info);
    }

    DeploymentSessionEvent buildEvent(Type type, ConcordCluster cluster, Status status) {
        return new DeploymentSessionEvent(type, dsId, status, new ProvisionedResource(),
                                          new ConcordNode(), new ConcordNodeStatus(), cluster);
    }
}
