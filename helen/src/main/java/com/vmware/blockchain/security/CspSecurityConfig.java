/*
 * Copyright (c) 2019 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.vmware.blockchain.common.Constants;


/**
 * Security Configuration for Helen.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(value = Constants.USE_CSP_AUTH, havingValue = "true")
public class CspSecurityConfig extends WebSecurityConfigurerAdapter {

    @Autowired
    TokenAuthenticationProvider tokenAuthenticationProvider;

    @Autowired
    VmbcBasicAuthProver vmbcBasicAuthProver;

    @Autowired
    private RestAuthenticationEntryPoint restAuthticationEntryPoint;

    @Autowired
    private TokenRefreshFilter tokenRefreshFilter;

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        // Disable CSRF (cross site request forgery)
        http.csrf().disable().sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
                .authorizeRequests()
                .antMatchers("/api/auth/login", "/api/auth/token", "/api/agreements/1", "/", "/assets/**")
                .permitAll()
                .antMatchers("/api/oauth/login", "/api/oauth/oauth").permitAll()
                .antMatchers("/api/users")
                .hasAnyAuthority("vmbc-consortium:admin", "vmbc-system:admin", "vmbc-org:admin")
                .antMatchers("/api/concord/**", "/api/blockchains/**", "/api/tasks/**")
                .hasAnyAuthority("vmbc-consortium:admin", "vmbc-system:admin", "vmbc-org:admin", "vmbc-org:dev",
                                 "vmbc-org:user")
                .anyRequest()
                .authenticated().and().exceptionHandling()
                .authenticationEntryPoint(restAuthticationEntryPoint)
                .and().anonymous().and().httpBasic();
        http.addFilterBefore(new TokenAuthenticationFilter(authenticationManager()),
                             UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(tokenRefreshFilter, TokenAuthenticationFilter.class);


    }

    @Override
    public void configure(WebSecurity web) throws Exception {
        // Allow access to auth, UI routing URLs, and UI assets, without authentication
        web.ignoring().antMatchers("/api/agreements/1").antMatchers("/api/auth/token", "/api/auth/login");
    }

    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth.authenticationProvider(tokenAuthenticationProvider);
        auth.authenticationProvider(vmbcBasicAuthProver);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(4);
    }

    @Bean
    public HelenUserDetailsService userDetails() {
        return new HelenUserDetailsService();
    }

}
