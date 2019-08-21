#########################################################################
# Copyright 2018 - 2019 VMware, Inc.  All rights reserved. -- VMware Confidential
#
# This class is a helper file to test provisioning services (deployment services)
#########################################################################

import json
import logging
import sys
from rpc_helper import RPCHelper
from model_service_helper import ModelServiceRPCHelper
from vmware.blockchain.deployment.v1 import core_pb2
from vmware.blockchain.deployment.v1 import orchestration_pb2
from vmware.blockchain.deployment.v1 import provisioning_service_pb2
from vmware.blockchain.ethereum.type import genesis_pb2

sys.path.append('../../')
from util.product import Product as Product


log = logging.getLogger(__name__)


class ProvisioningServiceRPCHelper(RPCHelper):
   PLACEMENT_TYPE_FIXED = "FIXED"
   PLACEMENT_TYPE_UNSPECIFIED = "UNSPECIFIED"

   UPDATE_DEPLOYMENT_ACTION_NOOP = "NOOP"
   UPDATE_DEPLOYMENT_ACTION_DEPROVISION_ALL = "DEPROVISION_ALL"

   def __init__(self, args):
      super().__init__(args)
      self.service_name = Product.PERSEPHONE_SERVICE_PROVISIONING
      self.service_port = self.get_persephone_service_port(self.service_name)
      self.persephone_config_file = self.get_provisioning_config_file(
         self.service_name)

      self.grpc_server = "localhost:{}".format(self.service_port)
      self.channel = self.create_channel(self.service_name)
      self.stub = self.create_stub(self.channel)

   def __del__(self):
      self.close_channel(self.service_name)

   def create_placement_specification(self, cluster_size,
                                      placement_type=PLACEMENT_TYPE_FIXED,
                                      orchestration_sites=None):
      '''
      Helper method to create place specification used for create cluster
      :param cluster_size: Number of placement sites
      :param placement_type: Placement type FIXED/UNSPECIFIED
      :param orchestration_sites: List of orchestration Sites/zones
      :return: placement specification
      '''
      log.info("Concord node placement type: {}".format(placement_type))

      entries = []
      if placement_type == self.PLACEMENT_TYPE_UNSPECIFIED:
         for placement_count in range(0, cluster_size):
            placement_entry = provisioning_service_pb2.PlacementSpecification.Entry(
               type=provisioning_service_pb2.PlacementSpecification.UNSPECIFIED)
            entries.append(placement_entry)
      else:
         deployment_sites = []
         for site in orchestration_sites[0].sites:
            deployment_sites.append(site.id)
         log.info("Deploying on Orchestration Sites/zones: {}".format(
            deployment_sites))

         for placement_count in range(0, cluster_size):
            site_number = placement_count % len(deployment_sites)
            deployment_site = deployment_sites[site_number]
            log.debug("Placing concord[{}] on {}".format(placement_count, deployment_site))
            placement_entry = provisioning_service_pb2.PlacementSpecification.Entry(
               type=provisioning_service_pb2.PlacementSpecification.FIXED,
               site=deployment_site)
            entries.append(placement_entry)

      placement_specification = provisioning_service_pb2.PlacementSpecification(
         entries=entries
      )
      return placement_specification

   def create_genesis_specification(self):
      '''
      Helper method to create genesis bspec
      :return: genesis spec
      '''
      log.debug("Creating genesis spec")
      genesis_spec=genesis_pb2.Genesis(
          config=genesis_pb2.Genesis.Config(
              chain_id=1,
              homestead_block=0,
              eip155_block=0,
              eip158_block=0
          ),
          nonce="0x0000000000000000",
          difficulty="0x400",
          mixhash="0x0000000000000000000000000000000000000000000000000000000000000000",
          parent_hash="0x0000000000000000000000000000000000000000000000000000000000000000",
          gas_limit="0xf4240",
          alloc={
              "262c0d7ab5ffd4ede2199f6ea793f819e1abb019": genesis_pb2.Genesis.Wallet(balance="12345"),
              "5bb088f57365907b1840e45984cae028a82af934": genesis_pb2.Genesis.Wallet(balance="0xabcdef"),
              "0000a12b3f3d6c9b0d3f126a83ec2dd3dad15f39": genesis_pb2.Genesis.Wallet(balance="0x7fffffffffffffff")
          }
      )
      return genesis_spec

   def create_deployment_specification(self, cluster_size, model, placement, genesis_spec):
      '''
      Helper method to create deployment specification
      :param cluster_size: Number of concord members on the cluster cluster
      :param model: Metadata for deployment
      :param placement: placement site/SDDC
      :param genesis_spec: genesis spec
      :return: deployment specifcation
      '''
      deployment_specification = provisioning_service_pb2.DeploymentSpecification(
         cluster_size=cluster_size, model=model, placement=placement,
         genesis=genesis_spec)
      return deployment_specification

   def create_cluster_request(self, header, specification):
      '''
      Helper method to create a cluster request
      :param header: concord core header
      :param specification: deployment specification
      :return: cluster request spec
      '''
      create_cluster_request = provisioning_service_pb2.CreateClusterRequest(
         header=header, specification=specification)
      return create_cluster_request

   def create_cluster_deployment_session_event_request(self, header,
                                                       session_id):
      '''
      Helper method to create cluster deployment session event request
      :param header: cocnord core header
      :param session_id: deployment session ID
      :return: event request spec
      '''
      events_request = provisioning_service_pb2.StreamClusterDeploymentSessionEventRequest(
         header=header, session=session_id)
      return events_request

   def create_all_cluster_deployment_session_event_request(self, header):
      '''
      Helper method to create cluster deployment session event request
      :param header: cocnord core header
      :return: Deployment event session request
      '''
      all_events_request = provisioning_service_pb2.StreamAllClusterDeploymentSessionEventRequest(
         header=header)
      return all_events_request

   def update_deployment_session_request(self, header, session_id,
                                         action=UPDATE_DEPLOYMENT_ACTION_NOOP):
      '''
      Helper method to call gRPC UpdateDeploymentSessionRequest
      :param header: concord core header
      :param session_id: Deployment Session ID
      :param action: action to perform like DEPROVISION_ALL
      :return: Update deployment session request
      '''
      if action == self.UPDATE_DEPLOYMENT_ACTION_DEPROVISION_ALL:
         action_obj = provisioning_service_pb2.UpdateDeploymentSessionRequest.DEPROVISION_ALL
      else:
         action_obj = provisioning_service_pb2.UpdateDeploymentSessionRequest.NOOP

      update_deployment_session_request = provisioning_service_pb2.UpdateDeploymentSessionRequest(
         header=header, action=action_obj, session=session_id)
      return update_deployment_session_request

   def rpc_CreateCluster(self, create_cluster_request, stub=None):
      '''
      Helper method to call gRPC CreateCluster
      :param create_cluster_request: Create cluster request spec
      :param stub: Default stub if running default provisioning service on port
      9001, else, stub for the non-default instance
      :return: deployment session ID
      '''
      log.info("createCluster RPC")
      response = None
      try:
         if stub is None:
            stub = self.stub
         response = self.call_api(stub.CreateCluster, create_cluster_request)
      except Exception as e:
         self.handle_exception(e)
      return response

   def rpc_StreamClusterDeploymentSessionEvents(self, get_events_request, stub=None):
      '''
      Helper method to call gRPC rpc_StreamClusterDeploymentSessionEvents
      :param get_events_request: Deployment session ID
      :param stub: Default stub if running default provisioning service on port
      9001, else, stub for the non-default instance
      :return: Deployemtn Event Stream
      '''
      log.info("StreamClusterDeploymentSessionEvents RPC")
      response = None
      try:
         if stub is None:
            stub = self.stub
         # Increasing default stream_timeout to 10 mins due to bug VB-1289
         response = self.call_api(
            stub.StreamClusterDeploymentSessionEvents,
            get_events_request, stream=True, stream_timeout=600)
      except Exception as e:
         self.handle_exception(e)
      return response

   def rpc_StreamAllClusterDeploymentSessionEvents(self, all_events_request):
      '''
      Helper method to call gRPC rpc_StreamAllClusterDeploymentSessionEvents
      This rpc call returns a stream and could be called to run in background
      when provisioning service starts.
      NOTE: Stream timeout is set to 7200 seconds (120 mins) to capture all events
      :param get_events_request: Deployment Session Events Request
      :return: All Deployement Events Stream
      '''
      log.info("StreamAllClusterDeploymentSessionEvents RPC")
      response = None
      try:
         response = self.call_api(
            self.stub.StreamAllClusterDeploymentSessionEvents,
            all_events_request, stream=True, stream_forever=True,
            stream_timeout=7200)
      except Exception as e:
         self.handle_exception(e)
      return response

   def rpc_UpdateDeploymentSession(self, update_deployment_session_request, stub=None):
      '''
      Helper method to call gRPC UpdateDeploymentSession
      :param update_deployment_session_request: Update cluster request spec
      :param stub: Default stub if running default provisioning service on port
      9001, else, stub for the non-default instance
      :return: Empty protobug message {}
      '''
      log.info("UpdateDeploymentSession RPC")
      response = None
      try:
         if stub is None:
            stub = self.stub
         response = self.call_api(stub.UpdateDeploymentSession,
                                  update_deployment_session_request)
      except Exception as e:
         self.handle_exception(e)
      return response
