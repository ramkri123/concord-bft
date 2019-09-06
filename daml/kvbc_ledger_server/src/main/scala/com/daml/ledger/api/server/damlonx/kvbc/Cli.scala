package com.daml.ledger.api.server.damlonx.kvbc

import java.io.File

import com.digitalasset.ledger.api.tls.TlsConfiguration
import com.digitalasset.platform.index.config.Config


//TODO: Replace with Config class after upgrade to version 100.13.22
final case class ExtConfig(
    config: Config,
    participantId: String,
    replicaHost: String,
    replicaPort: Int)

object ExtConfig {
  def default: ExtConfig =
    new ExtConfig(
      Config.default,
      "standalone-participant",
      "localhost",
      50051)
}

object Cli {

  private val pemConfig = (path: String, ec: ExtConfig) =>
    ec.copy(
      config = ec.config.copy(
        tlsConfig = ec.config.tlsConfig.fold(
          Some(TlsConfiguration(enabled = true, None, Some(new File(path)), None)))(c =>
          Some(c.copy(keyFile = Some(new File(path)))))))

  private val crtConfig = (path: String, ec: ExtConfig) =>
    ec.copy(
      config = ec.config.copy(
        tlsConfig = ec.config.tlsConfig.fold(
          Some(TlsConfiguration(enabled = true, Some(new File(path)), None, None)))(c =>
          Some(c.copy(keyCertChainFile = Some(new File(path)))))))

  private val cacrtConfig = (path: String, ec: ExtConfig) =>
    ec.copy(
      config = ec.config.copy(
        tlsConfig = ec.config.tlsConfig.fold(
          Some(TlsConfiguration(enabled = true, None, None, Some(new File(path)))))(c =>
          Some(c.copy(trustCertCollectionFile = Some(new File(path)))))))

  private def cmdArgParser(binaryName: String, description: String) =
    new scopt.OptionParser[ExtConfig](binaryName) {
      head(description)
      opt[Int]("port")
        .optional()
        .action((p, ec) => ec.copy(config = ec.config.copy(port = p)))
        .text("Server port. If not set, a random port is allocated.")
      opt[File]("port-file")
        .optional()
        .action((f, ec) => ec.copy(config = ec.config.copy(portFile = Some(f))))
        .text("File to write the allocated port number to. Used to inform clients in CI about the allocated port.")
      opt[String]("pem")
        .optional()
        .text("TLS: The pem file to be used as the private key.")
        .action(pemConfig)
      opt[String]("crt")
        .optional()
        .text("TLS: The crt file to be used as the cert chain. Required if any other TLS parameters are set.")
        .action(crtConfig)
      opt[String]("cacrt")
        .optional()
        .text("TLS: The crt file to be used as the the trusted root CA.")
        .action(cacrtConfig)
      opt[Int]("maxInboundMessageSize")
        .action((x, ec) => ec.copy(config = ec.config.copy(maxInboundMessageSize = x)))
        .text(
          s"Max inbound message size in bytes. Defaults to ${Config.DefaultMaxInboundMessageSize}.")
      opt[String]("jdbc-url")
        .text(s"The JDBC URL to the postgres database used for the indexer and the index.")
        .action((u, ec) => ec.copy(config = ec.config.copy(jdbcUrl = u)))
      opt[String]("participant-id")
        .optional()
        .text(s"The participant id given to all components of a ledger api server. Defaults to ${ExtConfig.default.participantId}")
        .action((p, c) => c.copy(participantId = p))
      opt[Int]('p', "replica-port")
        .optional()
        .action((x, c) => c.copy(replicaPort = x))
        .text(s"Port of the concord replica. Defaults to ${ExtConfig.default.replicaPort}.")
      opt[String]('h', "replica-host")
        .optional()
        .action((x, c) => c.copy(replicaHost = x))
        .text(s"Host address of the concord replioca. Defaults to ${ExtConfig.default.replicaHost}.")
      arg[File]("<archive>...")
        .optional()
        .unbounded()
        .action((f, ec) => ec.copy(config = ec.config.copy(archiveFiles = f :: ec.config.archiveFiles)))
        .text("DAR files to load. Scenarios are ignored. The servers starts with an empty ledger by default.")
    }

  def parse(args: Array[String], binaryName: String, description: String): Option[ExtConfig] =
    cmdArgParser(binaryName, description).parse(args, ExtConfig.default)
}