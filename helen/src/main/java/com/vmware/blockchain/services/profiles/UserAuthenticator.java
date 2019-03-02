/*
 * Copyright (c) 2018-2019 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.services.profiles;

import static com.vmware.blockchain.services.profiles.UsersApiMessage.EMAIL_LABEL;
import static com.vmware.blockchain.services.profiles.UsersApiMessage.PASSWORD_LABEL;

import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONAware;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.vmware.blockchain.common.EntityModificationException;
import com.vmware.blockchain.common.HelenException;
import com.vmware.blockchain.security.HelenUserDetails;
import com.vmware.blockchain.security.JwtTokenProvider;
import com.vmware.blockchain.services.ethereum.ApiHelper;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A servlet for handling the user authentication flow of helen. This servlet is just added for temporary usage. Actual
 * authentication will be done with CSP. Do NOT rely on this servlet for primary authentication method.
 */
@Controller
public class UserAuthenticator {

    private static final Logger logger = LogManager.getLogger(UserAuthenticator.class);

    private UserRepository userRepository;

    private KeystoreRepository keystoreRepository;

    private ProfilesRegistryManager prm;

    private PasswordEncoder passwordEncoder;

    private JwtTokenProvider jwtTokenProvider;

    private KeystoresRegistryManager krm;

    @Getter
    @Setter
    @NoArgsConstructor
    private static class LoginRequest {
        private String email;
        private String password;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonInclude(value =  Include.NON_EMPTY)
    private static class LoginResponse {
        // login response potentially has all the fields of User
        private UUID userId;
        private String userName;
        private String firstName;
        private String lastName;
        private String email;
        private String role;
        private String password;
        private Long lastLogin;
        private String organizationName;
        private String consortiumName;
        private UUID organizationId;
        private UUID consortiumId;
        private Boolean authenticated;
        private String token;
        private String refreshToken;
        private Long tokenExpires;
        private String walletAddress;
        private String error;

        // Convenience function for dealing with the user fields
        public void setUser(User user) {
            this.userId = user.getUserId();
            this.userName = user.getName();
            this.firstName = user.getFirstName();
            this.lastName = user.getLastName();
            this.email = user.getEmail();
            this.role = user.getRole();
            this.lastLogin = user.getLastLogin();
            this.organizationId = user.getOrganization().getOrganizationId();
            this.organizationName = user.getOrganization().getOrganizationName();
            this.consortiumId = user.getConsortium().getConsortiumId();
            this.consortiumName = user.getConsortium().getConsortiumName();
        }

    }

    @Autowired
    public UserAuthenticator(UserRepository userRepository, ProfilesRegistryManager prm,
            PasswordEncoder passwordEncoder, JwtTokenProvider jwtTokenProvider,
            KeystoresRegistryManager krm, KeystoreRepository keystoreRepository) {
        this.userRepository = userRepository;
        this.keystoreRepository = keystoreRepository;
        this.prm = prm;
        this.krm = krm;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }


    // TODO: This is not a proper way to authenticate the user. We have plans to
    // authenticate every user via CSP, however that integration will take time
    // and till then some way of authentication is needed. Hence, we have added
    // this temporary (and not very secure) login feature. Remove this and
    // authenticate every user with CSP as soon as possible
    @RequestMapping(method = RequestMethod.POST, path = "/api/auth/login")
    protected ResponseEntity<LoginResponse> doPost(@RequestBody LoginRequest request) {
        HttpStatus responseStatus;
        LoginResponse loginResponse = new LoginResponse();
        try {
            String password = request.getPassword();
            String email = request.getEmail();
            User u = userRepository.findUserByEmail(email).orElse(null);

            if (u != null && passwordEncoder.matches(password, u.getPassword())) {
                // need to get another image of user
                List<Keystore> keystores = keystoreRepository.findKeystoresByUser(u);
                if (keystores.size() > 0) {
                    loginResponse.setWalletAddress(keystores.get(0).getAddress());
                } else {
                    loginResponse.setWalletAddress("");
                }
                responseStatus = HttpStatus.OK;
                loginResponse.setUser(u);
                loginResponse.setAuthenticated(true);
                loginResponse.setToken(jwtTokenProvider.createToken(u));
                loginResponse.setRefreshToken(jwtTokenProvider.createRefreshToken(u));
                loginResponse.setTokenExpires(jwtTokenProvider.getValidityInMilliseconds());
                // This needs to be after we have copied the old user data
                prm.loginUser(u);
            } else {
                loginResponse.setError("Invalid email/password");
                responseStatus = HttpStatus.UNAUTHORIZED;
            }
        } catch (EntityModificationException e) {
            loginResponse.setError(e.getMessage());
            responseStatus = HttpStatus.BAD_REQUEST;
        }

        return new ResponseEntity<>(loginResponse, responseStatus);
    }

    @Getter
    @Setter
    @NoArgsConstructor
    private static class TokenRequest {
        private String refreshToken;
    }

    @RequestMapping(value = "/api/auth/token", method = RequestMethod.POST)
    protected ResponseEntity<LoginResponse> refreshToken(@RequestBody TokenRequest request) {
        HttpStatus responseStatus;
        LoginResponse loginResponse = new LoginResponse();

        try {
            String token = request.getRefreshToken();

            if (token != null) {
                responseStatus = HttpStatus.OK;
                Authentication auth = jwtTokenProvider.getAuthentication(token);
                SecurityContextHolder.getContext().setAuthentication(auth);
                HelenUserDetails details = (HelenUserDetails) auth.getPrincipal();

                String email = details.getUsername();
                User u = userRepository.findUserByEmail(email).get();
                String newToken = jwtTokenProvider.createToken(u);
                String refreshToken = jwtTokenProvider.createRefreshToken(u);
                loginResponse.setToken(newToken);
                loginResponse.setRefreshToken(refreshToken);
                loginResponse.setTokenExpires(jwtTokenProvider.getValidityInMilliseconds());
            } else {
                responseStatus = HttpStatus.BAD_REQUEST;
                loginResponse.setError("Bad Token");
            }
        } catch (HelenException e) {
            responseStatus = HttpStatus.BAD_REQUEST;
            loginResponse.setError(e.getMessage());
        }

        return new ResponseEntity<>(loginResponse, responseStatus);

    }

    @RequestMapping(method = RequestMethod.POST, path = "/api/auth/change-password")
    protected ResponseEntity<JSONAware> doChangePassword(@RequestBody String requestBody) {
        JSONParser parser = new JSONParser();
        HttpStatus responseStatus;
        JSONObject responseJson;

        try {
            JSONObject requestJson = (JSONObject) parser.parse(requestBody);
            if (requestJson.containsKey(EMAIL_LABEL) && requestJson.containsKey(PASSWORD_LABEL)) {

                String email = requestJson.get(EMAIL_LABEL).toString();
                User u = userRepository.findUserByEmail(email).get();
                String password = requestJson.get(PASSWORD_LABEL).toString();

                if (passwordEncoder.matches(password, u.getPassword())) {
                    responseJson = ApiHelper.errorJson("Can't use same password!");
                    responseStatus = HttpStatus.BAD_REQUEST;
                } else {
                    String enPw = passwordEncoder.encode(password);
                    responseStatus = HttpStatus.OK;
                    responseJson = prm.changePassword(email, enPw);
                }

            } else {
                responseJson = ApiHelper.errorJson("email or password " + "field missing");
                responseStatus = HttpStatus.BAD_REQUEST;
            }
        } catch (ParseException | EntityModificationException e) {
            responseStatus = HttpStatus.BAD_REQUEST;
            responseJson = ApiHelper.errorJson(e.getMessage());
        }

        return new ResponseEntity<>(responseJson, responseStatus);
    }
}
