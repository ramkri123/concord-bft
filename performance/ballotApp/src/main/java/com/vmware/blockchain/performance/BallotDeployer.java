package com.vmware.blockchain.performance;

import com.vmware.blockchain.samples.Ballot;

import okhttp3.OkHttpClient;

import org.apache.logging.log4j.*;

import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetBalance;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.gas.DefaultGasProvider;
import org.web3j.utils.Numeric;

import java.io.*;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BallotDeployer {

	private BigInteger nonce;

	private static final Logger logger = LogManager.getLogger(BallotDApp.class);

	private static Credentials credentials;

	private Ballot ballot;

	public BallotDeployer() {

	}

	public static Credentials getChairperson() {
		return credentials;
	}

	public void deploy(String proposalPath, String contractPath, String password, String path) throws Exception {

		OkHttpClient client = BallotDApp.CLIENT;

		HttpService httpServiceEth = new HttpService(BallotDApp.ENDPOINT, client, false);
		httpServiceEth.addHeader("Authorization", okhttp3.Credentials.basic(BallotDApp.CONCORD_USERNAME, BallotDApp.CONCORD_PASSWORD));
		Web3j web3j = Web3j.build(httpServiceEth);

		logger.info("Connected to Ethereum client version: "
				+ web3j.web3ClientVersion().send().getWeb3ClientVersion());

		//load credentials
		credentials = Utils.loadCredential(password, path);

		List<byte[]> proposals = Utils.getProposals(proposalPath);

		try {
			long deployStartTime = System.nanoTime();;
			ballot = Ballot.deploy(web3j, credentials, DefaultGasProvider.GAS_PRICE, DefaultGasProvider.GAS_LIMIT, proposals).send();
			long deployEndTime = System.nanoTime();
			PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(BallotDApp.STAT_DATA_PATH, true)));
			writer.println("STAT_DEPLOY_CONTRACT_LATENCY=" + (deployEndTime-deployStartTime) + " ns");
			writer.close();
			logger.log(Level.getLevel("STAT"), "STAT_DEPLOY_CONTRACT_LATENCY=" + (deployEndTime-deployStartTime) + " ns");

		} catch(IOException e) {
			e.printStackTrace();
		} catch(Exception e) {
			logger.info(e.getMessage());
		}


		String contractAddress = ballot.getContractAddress();
		logger.info("Smart contract deployed to address " + contractAddress);

		BufferedWriter writer = new BufferedWriter(new FileWriter(new File(contractPath)));
		writer.write(contractAddress);
		writer.close();

		TransactionReceipt receipt = ballot.getTransactionReceipt().get();
		logger.info("Transaction info about deploying contract:\n" + receipt);
	}

	public double grantRightToVote(Credentials[] credentials) throws Exception {
		long startTime = System.nanoTime();
		List<CompletableFuture<TransactionReceipt>> tasks = new ArrayList<>();
		ExecutorService executor = Executors.newFixedThreadPool(BallotDApp.NUMBER_THREADS);
		for (Credentials credential : credentials)  {
			CompletableFuture<TransactionReceipt> task =
					execute(executor, ballot, credential.getAddress()).whenComplete((entry, error) -> {
						if (error != null) {
							logger.error("Error occurred during granting right: ", error);
						}
					});

			tasks.add(task);
		}
		executor.shutdown();

		CompletableFuture<Void> allDone =
				CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]));
		CompletableFuture.allOf(allDone).join();
		long endTime = System.nanoTime();

		logger.info("Total Time for Granting Right is " + (endTime - startTime) + " nano seconds");
		if (BallotDApp.NUMBER != 0) {
			return (endTime - startTime) *(1.0e-9) / BallotDApp.NUMBER;
		}
		return 0;

	}

	public CompletableFuture<TransactionReceipt> execute(ExecutorService executor, Ballot ballot, String voter) {
		final CompletableFuture<TransactionReceipt> promise = new CompletableFuture<>();
		CompletableFuture.runAsync(() -> {
			try {
				ballot.giveRightToVote(voter).sendAsync();
			} catch (Exception e) {
				promise.completeExceptionally(e);
			}

		}, executor).thenRun(() -> {
			promise.complete(new TransactionReceipt());
		});
		return promise;
	}

	public double grantRightToVoteEthereum(Credentials[] credentials) throws Exception {

		OkHttpClient client = BallotDApp.CLIENT;

		HttpService httpServiceEth = new HttpService(BallotDApp.ENDPOINT, client, false);
		Web3j web3j = Web3j.build(httpServiceEth);


		// Transferring funds to every account
		// This does not count to transaction time

		// Calculate initial nonce
		EthGetTransactionCount ethGetTransactionCount = web3j.ethGetTransactionCount(getChairperson().getAddress(), DefaultBlockParameterName.PENDING).sendAsync().get();
		nonce = ethGetTransactionCount.getTransactionCount();

		long start = System.nanoTime();

		logger.info("Transfering funds to all accounts");

		// Transferring funds to all accounts
		for (Credentials credential : credentials) {
			RawTransaction rawTransaction = RawTransaction.createEtherTransaction(nonce, DefaultGasProvider.GAS_PRICE, DefaultGasProvider.GAS_LIMIT, credential.getAddress(), new BigInteger("1000000000000000000"));//BigDecimal.valueOf(10))
			byte[] signedMsg = TransactionEncoder.signMessage(rawTransaction, getChairperson());
			String hexValue = Numeric.toHexString(signedMsg);
			EthSendTransaction ethSendFundTransaction = web3j.ethSendRawTransaction(hexValue).sendAsync().get();
			if (BallotDApp.ENABLE_LOGGING) {
				logger.info("Transfer details - ");
				logger.info("ID = " + ethSendFundTransaction.getId());
				logger.info("JSON RPC = " + ethSendFundTransaction.getJsonrpc());
				logger.info("Result = " + ethSendFundTransaction.getResult());
			}
			if (ethSendFundTransaction.getError() != null) 
				logger.info("Error = " + ethSendFundTransaction.getError().getMessage());
			if (BallotDApp.ENABLE_LOGGING) {
				logger.info("Raw response = " + ethSendFundTransaction.getRawResponse());
				String transactionHash = ethSendFundTransaction.getTransactionHash();
				logger.info("TxHash = " + transactionHash);
				logger.info("Nonce = " + nonce);
			}
			
			nonce = nonce.add(new BigInteger("1"));
		}

		int numberOfTransfersCompleted = 0;
		int prevNumberOfTransfersCompleted = -1;
		boolean[] isTransferDone = new boolean[BallotDApp.NUMBER];
		for(int i=0; i<BallotDApp.NUMBER; ++i) {
			isTransferDone[i] = false;
		}
		while (numberOfTransfersCompleted != BallotDApp.NUMBER) {
			for (int i=0; i<BallotDApp.NUMBER; ++i) {
				if(isTransferDone[i]) continue;
				Credentials credential = credentials[i];
				EthGetBalance ethGetBalance = web3j
						.ethGetBalance(credential.getAddress(), DefaultBlockParameterName.LATEST)
						.sendAsync()
						.get();
				BigInteger balance = ethGetBalance.getBalance();
				if(balance.compareTo(BigInteger.valueOf(0)) == 1) {
					isTransferDone[i] = true;
					++numberOfTransfersCompleted;
				}
			}
			if(numberOfTransfersCompleted != prevNumberOfTransfersCompleted) {
				prevNumberOfTransfersCompleted = numberOfTransfersCompleted;
			}
			Thread.sleep(100);
		}
		long end = System.nanoTime();
		logger.info("All transfers completed in " + (end - start) + " nanosec");
		
		return (end-start) *(1.0e-9) / BallotDApp.NUMBER;
	}
}
