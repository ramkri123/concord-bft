# -*- coding: utf-8 -*-
# Generated by the protocol buffer compiler.  DO NOT EDIT!
# source: tee.proto

from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from google.protobuf import reflection as _reflection
from google.protobuf import symbol_database as _symbol_database
# @@protoc_insertion_point(imports)

_sym_db = _symbol_database.Default()




DESCRIPTOR = _descriptor.FileDescriptor(
  name='tee.proto',
  package='com.vmware.concord.tee',
  syntax='proto3',
  serialized_options=None,
  serialized_pb=b'\n\ttee.proto\x12\x16\x63om.vmware.concord.tee\".\n\tTestInput\x12\x12\n\ntest_input\x18\x01 \x01(\t\x12\r\n\x05\x66lags\x18\x02 \x01(\r\"!\n\nTestOutput\x12\x13\n\x0btest_output\x18\x01 \x01(\t\"1\n\x0fRawSkvbcRequest\x12\x0f\n\x07\x63ontent\x18\x01 \x01(\x0c\x12\r\n\x05\x66lags\x18\x02 \x01(\r\"#\n\x10RawSkvbcResponse\x12\x0f\n\x07\x63ontent\x18\x01 \x01(\x0c\x32\xa5\x02\n\nTeeService\x12R\n\x07RunTest\x12!.com.vmware.concord.tee.TestInput\x1a\".com.vmware.concord.tee.TestOutput\"\x00\x12`\n\tSkvbcRead\x12\'.com.vmware.concord.tee.RawSkvbcRequest\x1a(.com.vmware.concord.tee.RawSkvbcResponse\"\x00\x12\x61\n\nSkvbcWrite\x12\'.com.vmware.concord.tee.RawSkvbcRequest\x1a(.com.vmware.concord.tee.RawSkvbcResponse\"\x00\x62\x06proto3'
)




_TESTINPUT = _descriptor.Descriptor(
  name='TestInput',
  full_name='com.vmware.concord.tee.TestInput',
  filename=None,
  file=DESCRIPTOR,
  containing_type=None,
  fields=[
    _descriptor.FieldDescriptor(
      name='test_input', full_name='com.vmware.concord.tee.TestInput.test_input', index=0,
      number=1, type=9, cpp_type=9, label=1,
      has_default_value=False, default_value=b"".decode('utf-8'),
      message_type=None, enum_type=None, containing_type=None,
      is_extension=False, extension_scope=None,
      serialized_options=None, file=DESCRIPTOR),
    _descriptor.FieldDescriptor(
      name='flags', full_name='com.vmware.concord.tee.TestInput.flags', index=1,
      number=2, type=13, cpp_type=3, label=1,
      has_default_value=False, default_value=0,
      message_type=None, enum_type=None, containing_type=None,
      is_extension=False, extension_scope=None,
      serialized_options=None, file=DESCRIPTOR),
  ],
  extensions=[
  ],
  nested_types=[],
  enum_types=[
  ],
  serialized_options=None,
  is_extendable=False,
  syntax='proto3',
  extension_ranges=[],
  oneofs=[
  ],
  serialized_start=37,
  serialized_end=83,
)


_TESTOUTPUT = _descriptor.Descriptor(
  name='TestOutput',
  full_name='com.vmware.concord.tee.TestOutput',
  filename=None,
  file=DESCRIPTOR,
  containing_type=None,
  fields=[
    _descriptor.FieldDescriptor(
      name='test_output', full_name='com.vmware.concord.tee.TestOutput.test_output', index=0,
      number=1, type=9, cpp_type=9, label=1,
      has_default_value=False, default_value=b"".decode('utf-8'),
      message_type=None, enum_type=None, containing_type=None,
      is_extension=False, extension_scope=None,
      serialized_options=None, file=DESCRIPTOR),
  ],
  extensions=[
  ],
  nested_types=[],
  enum_types=[
  ],
  serialized_options=None,
  is_extendable=False,
  syntax='proto3',
  extension_ranges=[],
  oneofs=[
  ],
  serialized_start=85,
  serialized_end=118,
)


_RAWSKVBCREQUEST = _descriptor.Descriptor(
  name='RawSkvbcRequest',
  full_name='com.vmware.concord.tee.RawSkvbcRequest',
  filename=None,
  file=DESCRIPTOR,
  containing_type=None,
  fields=[
    _descriptor.FieldDescriptor(
      name='content', full_name='com.vmware.concord.tee.RawSkvbcRequest.content', index=0,
      number=1, type=12, cpp_type=9, label=1,
      has_default_value=False, default_value=b"",
      message_type=None, enum_type=None, containing_type=None,
      is_extension=False, extension_scope=None,
      serialized_options=None, file=DESCRIPTOR),
    _descriptor.FieldDescriptor(
      name='flags', full_name='com.vmware.concord.tee.RawSkvbcRequest.flags', index=1,
      number=2, type=13, cpp_type=3, label=1,
      has_default_value=False, default_value=0,
      message_type=None, enum_type=None, containing_type=None,
      is_extension=False, extension_scope=None,
      serialized_options=None, file=DESCRIPTOR),
  ],
  extensions=[
  ],
  nested_types=[],
  enum_types=[
  ],
  serialized_options=None,
  is_extendable=False,
  syntax='proto3',
  extension_ranges=[],
  oneofs=[
  ],
  serialized_start=120,
  serialized_end=169,
)


_RAWSKVBCRESPONSE = _descriptor.Descriptor(
  name='RawSkvbcResponse',
  full_name='com.vmware.concord.tee.RawSkvbcResponse',
  filename=None,
  file=DESCRIPTOR,
  containing_type=None,
  fields=[
    _descriptor.FieldDescriptor(
      name='content', full_name='com.vmware.concord.tee.RawSkvbcResponse.content', index=0,
      number=1, type=12, cpp_type=9, label=1,
      has_default_value=False, default_value=b"",
      message_type=None, enum_type=None, containing_type=None,
      is_extension=False, extension_scope=None,
      serialized_options=None, file=DESCRIPTOR),
  ],
  extensions=[
  ],
  nested_types=[],
  enum_types=[
  ],
  serialized_options=None,
  is_extendable=False,
  syntax='proto3',
  extension_ranges=[],
  oneofs=[
  ],
  serialized_start=171,
  serialized_end=206,
)

DESCRIPTOR.message_types_by_name['TestInput'] = _TESTINPUT
DESCRIPTOR.message_types_by_name['TestOutput'] = _TESTOUTPUT
DESCRIPTOR.message_types_by_name['RawSkvbcRequest'] = _RAWSKVBCREQUEST
DESCRIPTOR.message_types_by_name['RawSkvbcResponse'] = _RAWSKVBCRESPONSE
_sym_db.RegisterFileDescriptor(DESCRIPTOR)

TestInput = _reflection.GeneratedProtocolMessageType('TestInput', (_message.Message,), {
  'DESCRIPTOR' : _TESTINPUT,
  '__module__' : 'tee_pb2'
  # @@protoc_insertion_point(class_scope:com.vmware.concord.tee.TestInput)
  })
_sym_db.RegisterMessage(TestInput)

TestOutput = _reflection.GeneratedProtocolMessageType('TestOutput', (_message.Message,), {
  'DESCRIPTOR' : _TESTOUTPUT,
  '__module__' : 'tee_pb2'
  # @@protoc_insertion_point(class_scope:com.vmware.concord.tee.TestOutput)
  })
_sym_db.RegisterMessage(TestOutput)

RawSkvbcRequest = _reflection.GeneratedProtocolMessageType('RawSkvbcRequest', (_message.Message,), {
  'DESCRIPTOR' : _RAWSKVBCREQUEST,
  '__module__' : 'tee_pb2'
  # @@protoc_insertion_point(class_scope:com.vmware.concord.tee.RawSkvbcRequest)
  })
_sym_db.RegisterMessage(RawSkvbcRequest)

RawSkvbcResponse = _reflection.GeneratedProtocolMessageType('RawSkvbcResponse', (_message.Message,), {
  'DESCRIPTOR' : _RAWSKVBCRESPONSE,
  '__module__' : 'tee_pb2'
  # @@protoc_insertion_point(class_scope:com.vmware.concord.tee.RawSkvbcResponse)
  })
_sym_db.RegisterMessage(RawSkvbcResponse)



_TEESERVICE = _descriptor.ServiceDescriptor(
  name='TeeService',
  full_name='com.vmware.concord.tee.TeeService',
  file=DESCRIPTOR,
  index=0,
  serialized_options=None,
  serialized_start=209,
  serialized_end=502,
  methods=[
  _descriptor.MethodDescriptor(
    name='RunTest',
    full_name='com.vmware.concord.tee.TeeService.RunTest',
    index=0,
    containing_service=None,
    input_type=_TESTINPUT,
    output_type=_TESTOUTPUT,
    serialized_options=None,
  ),
  _descriptor.MethodDescriptor(
    name='SkvbcRead',
    full_name='com.vmware.concord.tee.TeeService.SkvbcRead',
    index=1,
    containing_service=None,
    input_type=_RAWSKVBCREQUEST,
    output_type=_RAWSKVBCRESPONSE,
    serialized_options=None,
  ),
  _descriptor.MethodDescriptor(
    name='SkvbcWrite',
    full_name='com.vmware.concord.tee.TeeService.SkvbcWrite',
    index=2,
    containing_service=None,
    input_type=_RAWSKVBCREQUEST,
    output_type=_RAWSKVBCRESPONSE,
    serialized_options=None,
  ),
])
_sym_db.RegisterServiceDescriptor(_TEESERVICE)

DESCRIPTOR.services_by_name['TeeService'] = _TEESERVICE

# @@protoc_insertion_point(module_scope)
