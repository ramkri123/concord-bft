/*
 * Copyright (c) 2018 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.services.ethereum;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import com.google.protobuf.ByteString;
import com.vmware.athena.Athena;
import com.vmware.athena.Athena.EthRequest;
import com.vmware.athena.Athena.EthRequest.EthMethod;
import com.vmware.athena.Athena.EthResponse;
import com.vmware.blockchain.common.AthenaProperties;
import com.vmware.blockchain.connections.AthenaConnectionPool;
import com.vmware.blockchain.services.contracts.ContractRegistryManager;

/**
 * <p>
 * This handler is used to service eth_sendTransaction and eth_call POST requests. These are bundled together here
 * because functionally, the processing for both these request types is similar.
 * </p>
 */
public class EthSendTxHandler extends AbstractEthRpcHandler {

    private static Logger logger = LogManager.getLogger(EthSendTxHandler.class);
    private static boolean isInternalContract;
    private ContractRegistryManager registryManager;
    private AthenaConnectionPool connectionPool;

    /**
     * Send transaction constructor.
     */
    public EthSendTxHandler(AthenaProperties config, AthenaConnectionPool connectionPool,
            ContractRegistryManager registryManager, boolean isInternalContract) {
        super(config);
        // If isInternalContract is true, the handler is processing a contract created from the Helen UI.
        this.isInternalContract = isInternalContract;
        this.registryManager = registryManager;
        this.connectionPool = connectionPool;
    }

    /**
     * Builds the Athena request builder. Extracts the method name, from, to, data and value fields from the request and
     * uses it to set up an Athena Request builder with an EthRequest.
     *
     * <p>'from' is mandatory for send tx and 'to' is mandatory for call contract.
     *
     * @param athenaRequestBuilder Object in which request is built
     * @param requestJson Request parameters passed by the user
     */
    @Override
    public void buildRequest(Athena.AthenaRequest.Builder athenaRequestBuilder, JSONObject requestJson)
            throws EthRpcHandlerException, ApiHelper.HexParseException, RlpParser.RlpEmptyException {

        Athena.EthRequest ethRequest = null;
        EthRequest.Builder b = initializeRequestObject(requestJson);
        String method = EthDispatcher.getEthMethodName(requestJson);

        JSONArray params = extractRequestParams(requestJson);
        if (method.equals(config.getSendTransaction_Name())) {
            b.setMethod(EthMethod.SEND_TX);
            buildRequestFromObject(b, (JSONObject) params.get(0), true /* isSendTx */);
        } else if (method.equals(config.getSendRawTransaction_Name())) {
            b.setMethod(EthMethod.SEND_TX);
            buildRequestFromString(b, (String) params.get(0));
        } else {
            b.setMethod(EthMethod.CALL_CONTRACT);
            buildRequestFromObject(b, (JSONObject) params.get(0), false /* isSendTx */);
            // add "block" parameter
            if (params.size() == 2) {
                long blockNumber = ApiHelper.parseBlockNumber(params);
                if (blockNumber != -1) {
                    b.setBlockNumber(blockNumber);
                }
            }
        }

        ethRequest = b.build();
        athenaRequestBuilder.addEthRequest(ethRequest);
    }

    private void buildRequestFromObject(EthRequest.Builder b, JSONObject obj, boolean isSendTx)
            throws EthRpcHandlerException, ApiHelper.HexParseException {

        if (obj.containsKey("from")) {
            String from = (String) obj.get("from");
            ByteString fromAddr = ApiHelper.hexStringToBinary(from);
            b.setAddrFrom(fromAddr);
        } else if (isSendTx) {
            // TODO: if we allow r/s/v signature fields, we don't have to require
            // 'from' when they're present
            logger.error("From field missing in params");
            throw new EthRpcHandlerException("'from' must be specified");
        }

        if (obj.containsKey("to")) {
            String to = (String) obj.get("to");
            ByteString toAddr = ApiHelper.hexStringToBinary(to);
            b.setAddrTo(toAddr);
        } else if (!isSendTx) {
            logger.error("To field missing in params");
            throw new EthRpcHandlerException("'to' must be specified");
        }

        if (obj.containsKey("data")) {
            String data = (String) obj.get("data");
            if (data != null) {
                ByteString dataBytes = ApiHelper.hexStringToBinary(data);
                b.setData(dataBytes);
            }
        }

        if (obj.containsKey("value")) {
            String value = (String) obj.get("value");
            if (value != null) {
                ByteString valueBytes = ApiHelper.hexStringToBinary(ApiHelper.padZeroes(value));
                b.setValue(valueBytes);
            }
        }

        // TODO: add gas, gasPrice, nonce, r, s, v
        // (no, rsv are not specified in the doc, but why not?)
    }

    private void buildRequestFromString(EthRequest.Builder b, String rlp)
            throws EthRpcHandlerException, ApiHelper.HexParseException, RlpParser.RlpEmptyException {

        RlpParser envelopeParser = new RlpParser(rlp);
        ByteString envelope = envelopeParser.next();

        if (!envelopeParser.atEnd()) {
            throw new EthRpcHandlerException("Unable to parse raw transaction (extra data after envelope)");
        }

        RlpParser parser = new RlpParser(envelope);

        final ByteString nonceV = nextPart(parser, "nonce");
        final ByteString gasPriceV = nextPart(parser, "gas price");
        final ByteString gasV = nextPart(parser, "start gas");
        final ByteString to = nextPart(parser, "to address");
        final ByteString value = nextPart(parser, "value");
        final ByteString data = nextPart(parser, "data");
        final ByteString vV = nextPart(parser, "signature V");

        if (!parser.atEnd()) {
            throw new EthRpcHandlerException("Unable to parse raw transaction (extra data in envelope)");
        }

        final long nonce = ApiHelper.bytesToLong(nonceV);
        final long gasPrice = ApiHelper.bytesToLong(gasPriceV);
        final long gas = ApiHelper.bytesToLong(gasV);
        final long v = ApiHelper.bytesToLong(vV);

        if (to.size() != 0 && to.size() != 20) {
            throw new EthRpcHandlerException("Invalid raw transaction (to address too short)");
        }

        ByteString r = nextPart(parser, "signature R");
        if (r.size() > 32) {
            throw new EthRpcHandlerException("Invalid raw transaction (signature R too large)");
        } else if (r.size() < 32) {
            // pad out to 32 bytes to make things easy for Athena
            byte[] leadingZeros = new byte[32 - r.size()];
            r = ByteString.copyFrom(leadingZeros).concat(r);
        }

        ByteString s = nextPart(parser, "signature S");
        if (s.size() > 32) {
            throw new EthRpcHandlerException("Invalid raw transaction (signature S too large)");
        } else if (s.size() < 32) {
            // pad out to 32 bytes to make things easy for Athena
            byte[] leadingZeros = new byte[32 - s.size()];
            s = ByteString.copyFrom(leadingZeros).concat(s);
        }

        b.setNonce(nonce);
        b.setGasPrice(gasPrice);
        b.setGas(gas);
        if (to.size() > 0) {
            b.setAddrTo(to);
        }
        b.setValue(value);
        b.setData(data);
        b.setSigV(v);
        b.setSigR(r);
        b.setSigS(s);
    }

    private ByteString nextPart(RlpParser parser, String label) throws EthRpcHandlerException {
        try {
            ByteString b = parser.next();
            logger.trace("Extracted " + label + ": " + b.size() + " bytes");
            return b;
        } catch (RlpParser.RlpEmptyException e) {
            throw new EthRpcHandlerException("Unable to decode " + label + " from raw transaction");
        }
    }

    /**
     * Builds the response object to be returned to the user.
     *
     * @param athenaResponse Response received from Athena
     * @param requestJson Request parameters passed by the user
     * @return response to be returned to the user
     */
    @SuppressWarnings("unchecked")
    @Override
    public JSONObject buildResponse(Athena.AthenaResponse athenaResponse, JSONObject requestJson) {
        EthResponse ethResponse = athenaResponse.getEthResponse(0);
        JSONObject respObject = initializeResponseObject(ethResponse);
        // Set method specific responses
        String method = (String) requestJson.get("method");
        String fromParam = "";
        String toParam = "";
        String byteCode = "";
        // params will only be an object when using eth_sendTransaction. To avoid parsing errors,
        // only perform the following steps if this is the method being used
        boolean isSendTransaction = method.equals(config.getSendTransaction_Name());
        if (isSendTransaction) {
            JSONArray paramsArray = (JSONArray) requestJson.get("params");
            JSONObject params = (JSONObject) paramsArray.get(0);
            fromParam = (String) params.get("from");
            toParam = (String) params.get("to");
            byteCode = (String) params.get("data");
            if (byteCode != null) {
                byteCode = byteCode.replace("0x", "");
            }
        }
        respObject.put("result", ApiHelper.binaryStringToHex(ethResponse.getData()));
        if (isSendTransaction && !isInternalContract && toParam == null) {
            try {
                handleSmartContractCreation(ApiHelper.binaryStringToHex(ethResponse.getData()), fromParam, byteCode);
            } catch (Exception e) {
                logger.error("Error in smart contract linking.", e);
            }
        }
        return respObject;
    }

    private void handleSmartContractCreation(String transactionHash, String from, String byteCode) throws Exception {
        final JSONObject ethRequest = new JSONObject();
        final JSONArray paramsArray = new JSONArray();
        ethRequest.put("id", 1);
        ethRequest.put("jsonrpc", jsonRpc);
        ethRequest.put("method", "eth_getTransactionReceipt");
        paramsArray.add(transactionHash);
        ethRequest.put("params", paramsArray);
        String responseString =
                new EthDispatcher(registryManager, config, connectionPool).dispatch(ethRequest).toJSONString();
        try {
            JSONObject txReceipt = (JSONObject) new JSONParser().parse(responseString);
            JSONObject result = (JSONObject) txReceipt.get("result");
            if (result.get("contractAddress") != null) {
                String contractVersion = "1";
                String contractAddress = (String) result.get("contractAddress");
                String metaData = "";
                String solidityCode = "";
                boolean success = registryManager.addNewContractVersion(contractAddress, from, contractVersion,
                        contractAddress, metaData, byteCode, solidityCode);
            }
        } catch (Exception e) {
            logger.error("Error parsing transaction receipt response", e);
        }

    }
}
