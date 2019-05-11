#########################################################################
# Copyright 2018 - 2019 VMware, Inc.  All rights reserved. -- VMware Confidential
#
# This class is a helper file to test persephone gRPC
#########################################################################

import sys
import json
sys.path.append('lib/persephone')
from rpc_helper import RPCHelper
from model_service_helper import ModelServiceRPCHelper
from provisioning_service_helper import ProvisioningServiceRPCHelper
from grpc_python_bindings import core_pb2
from google.protobuf.json_format import MessageToJson
sys.path.append('../../')
from util.product import Product as Product
import yaml
import logging

log = logging.getLogger(__name__)


class RPCTestHelper():
   def __init__(self, cmdlineArgs):
      self.cmdlineArgs = cmdlineArgs
      try:
         self.model_rpc_helper = ModelServiceRPCHelper(
            self.cmdlineArgs)
         self.provision_rpc_helper = ProvisioningServiceRPCHelper(
            self.cmdlineArgs)
         # self.fleet_rpc_helper = FleetServiceRPCHelper(
         #    self.cmdlineArgs)
      except Exception as e:
         raise Exception(e)

   def rpc_add_model(self):
      '''
      Helper method to call AddModel gRPC
      '''
      header = core_pb2.MessageHeader()
      concord_model_specification = self.model_rpc_helper.create_concord_model_specification()
      add_model_request = self.model_rpc_helper.create_add_model_request(
         header,
         concord_model_specification)

      add_model_response = self.model_rpc_helper.rpc_AddModel(
         add_model_request)
      log.info("AddModel response:")
      for item in add_model_response:
         log.info(item)
      return add_model_request, add_model_response

   def rpc_list_models(self):
      '''
      Helper method to call ListModel gRPC
      :return: Metadata
      '''
      metadata = self.model_rpc_helper.rpc_ListModels()
      for item in metadata:
         log.info("Metadata: {}".format(item))
      return metadata

   def rpc_create_cluster(self, cluster_size=4, placement_type="FIXED"):
      '''
      Helper method to call create cluster gRPC
      :param cluster_size: cluster size
      :param placement_type: FIXED/UNSPECIFIED to place the concord memebers on site
      :return: deployment session ID
      '''
      concord_model_specification = self.model_rpc_helper.create_concord_model_specification()
      placement_specification = self.provision_rpc_helper.create_placement_specification(
         cluster_size, placement_type=placement_type)
      concord_deployment_specification = self.provision_rpc_helper.create_deployment_specification(
         cluster_size, concord_model_specification, placement_specification)
      header = core_pb2.MessageHeader()
      create_cluster_request = self.provision_rpc_helper.create_cluster_request(
         header, concord_deployment_specification)

      session_id = self.provision_rpc_helper.rpc_CreateCluster(
         create_cluster_request)
      log.info("Session ID: ")
      for item in session_id:
         log.info(item)

      return session_id

   def rpc_stream_cluster_deployment_session_events(self, session_id):
      '''
      Helper method to stream deployment session events
      :param session_id: deployment session ID
      :return: deployment events
      '''
      header = core_pb2.MessageHeader()
      get_events_request = self.provision_rpc_helper.create_cluster_deployment_session_event_request(
         header, session_id)
      events = self.provision_rpc_helper.rpc_StreamClusterDeploymentSessionEvents(
         get_events_request)
      for event in events:
         log.info("Event: {}".format(event))

      return events
