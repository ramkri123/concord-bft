package com.vmware.blockchain.performance;

import com.vmware.blockchain.samples.Ballot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;

import java.math.BigInteger;
import java.util.Random;

public class Voter implements Runnable{

   private static final Logger log = LoggerFactory.getLogger(BallotDApp.class);

   private Ballot ballot;

   private Credentials credentials;

   public Voter(Web3j web3j, String path, Credentials credentials) throws Exception {
      this.credentials = credentials;
      ballot = Utils.loadContract(web3j, path, credentials);
   }
   @Override
   public void run() {
      Random random = new Random(System.currentTimeMillis());
      try {
         long start = System.nanoTime();
         log.info(credentials.getAddress() + ": " + start);
         ballot.vote(BigInteger.valueOf(random.nextInt(2))).send();
         long end = System.nanoTime();
         log.info(credentials.getAddress() + ": " + end);
         log.info("Response Time for " + credentials.getAddress() + "is " + (end - start) + " nano seconds");
      } catch (Exception e) {
         e.printStackTrace();
      }
   }
}