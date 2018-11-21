/*
 * Copyright (c) 2018 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.services.profiles;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONAware;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import com.vmware.blockchain.common.ConcordProperties;
import com.vmware.blockchain.connections.ConcordConnectionPool;
import com.vmware.blockchain.services.BaseServlet;
import com.vmware.blockchain.services.ethereum.ApiHelper;
import com.vmware.concord.Concord;

/**
 * A Controller that manages all GET/POST/PATCH requests related to user management API of helen.
 */
@Controller
public class AgreementServlet extends BaseServlet {

    private static final Logger logger = LogManager.getLogger(AgreementServlet.class);


    private AgreementsRegistryManager arm;

    @Autowired
    public AgreementServlet(AgreementsRegistryManager arm, ConcordProperties config,
            ConcordConnectionPool connectionPool) {
        super(config, connectionPool);
        this.arm = arm;
    }

    /**
     * Get an agreement.
     * @param id Id
     * @return Agreement
     */
    @RequestMapping(path = "/api/agreements/{id}", method = RequestMethod.GET)
    public ResponseEntity<JSONAware> getAgreementFromId(@PathVariable("id") String id) {
        JSONObject result = arm.getAgreementWithId(id);
        if (result.isEmpty()) {
            result.put("error", "Agreement not found");
            return new ResponseEntity<>(result, standardHeaders, HttpStatus.NOT_FOUND);
        } else {
            return new ResponseEntity<>(result, standardHeaders, HttpStatus.OK);
        }
    }

    /**
     * Update an Agreement.
     * @param id Id
     * @param requestBody Patch details.
     */
    @RequestMapping(path = "/api/agreements/{id}", method = RequestMethod.PATCH)
    public ResponseEntity<JSONAware> doPatch(@PathVariable(name = "id") String id, @RequestBody String requestBody) {
        HttpStatus responseStatus;
        JSONObject responseJson;

        try {
            JSONParser parser = new JSONParser();
            JSONObject requestJson = (JSONObject) parser.parse(requestBody);

            arm.updateAgreement(id, requestJson);
            logger.debug("Agreement accepted");
            responseJson = new JSONObject();
            responseStatus = HttpStatus.OK;

        } catch (ParseException e) {
            responseJson = ApiHelper.errorJson(e.getMessage());
            responseStatus = HttpStatus.BAD_REQUEST;
        }

        return new ResponseEntity<>(responseJson, standardHeaders, responseStatus);
    }

    @Override
    protected JSONAware parseToJson(Concord.ConcordResponse concordResponse) {
        throw new UnsupportedOperationException("parseToJSON method is not " + "supported in ProfileManager class");
    }
}
