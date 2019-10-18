// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.

package com.digitalasset.kvbc.validator

import java.time.{Duration, Instant}

import com.daml.ledger.participant.state.backport.TimeModel
import com.daml.ledger.participant.state.kvutils.{DamlKvutils => KV, _}
import com.daml.ledger.participant.state.v1.{Configuration, ParticipantId}
import com.digitalasset.daml.lf.data.{Ref, Time}
import com.digitalasset.daml.lf.engine.Engine
import com.digitalasset.kvbc.daml_data._
import com.digitalasset.kvbc.daml_validator._
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import com.google.protobuf.ByteString
import io.grpc.{Status, StatusRuntimeException}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class KVBCValidator extends ValidationServiceGrpc.ValidationService {
  type ReplicaId = Long
  type RawEntryId = ByteString
  type StateInputs = Map[KV.DamlStateKey, Option[KV.DamlStateValue]]

  private val logger = LoggerFactory.getLogger(this.getClass)
  private val engine = Engine()

  // Use the global execution context. This uses threads proportional to
  // available processors.
  implicit val ec: ExecutionContext = ExecutionContext.global

  private val cache: Cache[(ReplicaId, KV.DamlStateKey), KV.DamlStateValue] = {
    val cacheSizeEnv = System.getenv("KVBC_VALIDATOR_CACHE_SIZE")
    val cacheSize =
      if (cacheSizeEnv == null) {
        logger.warn("KVBC_VALIDATOR_CACHE_SIZE unset, defaulting to 'KVBC_VALIDATOR_CACHE_SIZE=256' (megabytes)")
        256 * 1024 * 1024
      } else {
        cacheSizeEnv.toInt
      }

    Scaffeine()
      .recordStats
      .expireAfterWrite(1.hour)
      .maximumWeight(cacheSize)
      .weigher[(ReplicaId, KV.DamlStateKey), KV.DamlStateValue]{
        case (k: (ReplicaId, KV.DamlStateKey), v: KV.DamlStateValue) => v.getSerializedSize
      }
      .build[(ReplicaId, KV.DamlStateKey), KV.DamlStateValue]
  }

  case class PendingSubmission(
    participantId: ParticipantId,
    entryId: RawEntryId,
    recordTime: Time.Timestamp,
    submission: KV.DamlSubmission,
    inputState: Map[KV.DamlStateKey, Option[KV.DamlStateValue]]
  )

  // Pending submissions for each replica that require further state to process.
  // We only keep the last submission and assume that each replica executes each
  // request sequentially.
  private val pendingSubmissions =
    scala.collection.concurrent.TrieMap.empty[ReplicaId, PendingSubmission]

  // The default configuration to use if none has been uploaded.
  // FIXME(JM): Move into common place so getLedgerInitialConditions can use it as well.
  private val defaultConfig = Configuration(
    0,
    TimeModel(Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofMinutes(2)).get,
    None,
    true)

  private def buildTimestamp(ts: Time.Timestamp): com.google.protobuf.timestamp.Timestamp = {
    val instant = ts.toInstant
    com.google.protobuf.timestamp.Timestamp(instant.getEpochSecond, instant.getNano)
  }

  private def parseTimestamp(ts: com.google.protobuf.timestamp.Timestamp): Time.Timestamp =
    Time.Timestamp.assertFromInstant(Instant.ofEpochSecond(ts.seconds, ts.nanos))

  def catchedFutureThunk[A](act: => A): Future[A] =
    Future {
      try {
        act
      } catch {
        case e: Throwable =>
          logger.error(s"Exception: $e")
          throw e
      }
    }

  def provideState(request: ProvideStateRequest): Future[ProvideStateResponse] = catchedFutureThunk {
    val replicaId = request.replicaId
    logger.trace(s"Completing submission: replicaId=$replicaId, entryId=${request.entryId.toStringUtf8}")

    val pendingSubmission =
        pendingSubmissions
          .remove(replicaId)
          .getOrElse(sys.error(s"No pending submission for ${replicaId}!"))

    if (pendingSubmission.entryId != request.entryId) {
      sys.error(s"Pending submission was for different entryId: ${pendingSubmission.entryId} != ${request.entryId}")
    }

    val providedInputs: StateInputs =
      request.inputState.map { kv =>
        val key =
          KeyValueCommitting.unpackDamlStateKey(kv.key)

        key ->
          (if (kv.value.isEmpty)
            None
          else {
            Envelope.open(kv.value) match {
              case Right(Envelope.StateValueMessage(v)) =>
                cache.put(replicaId -> key, v)
                Some(v)
              case _ =>
                logger.error(s"Corrupted state value of $key")
                throw new StatusRuntimeException(
                  Status.INVALID_ARGUMENT.withDescription("Corrupted input state value")
                )
            }
          })
      }
      .toMap

    val result = processPendingSubmission(
      replicaId,
      pendingSubmission.copy(inputState = pendingSubmission.inputState ++ providedInputs)
    )
    ProvideStateResponse(Some(result))
  }

  def validateSubmission(request: ValidateRequest): Future[ValidateResponse] = catchedFutureThunk {
    val replicaId = request.replicaId
    val participantId = Ref.LedgerString.assertFromString(request.participantId)

    logger.trace(s"Validating submission: replicaId=$replicaId, participantId=${request.participantId}, entryId=${request.entryId.toStringUtf8}")

    // Unpack the submission.
    val submission =
      Envelope.open(request.submission) match {
        case Right(Envelope.SubmissionMessage(submission)) => submission
        case _ =>
          logger.error("Failed to parse submission")
          throw new StatusRuntimeException(
            Status.INVALID_ARGUMENT.withDescription("Unparseable submission")
          )
      }

    val allInputs: Set[KV.DamlStateKey] = submission.getInputDamlStateList.asScala.toSet
    val cachedInputs: StateInputs =
      cache.getAllPresent(allInputs.map(replicaId -> _)).map { case ((_, key), v) => key -> Some(v) }
    val pendingSubmission = PendingSubmission(
      participantId,
      entryId = request.entryId,
      recordTime = parseTimestamp(request.recordTime.get),
      submission = submission,
      inputState = cachedInputs
    )

    val missingInputs =
      (allInputs -- cachedInputs.keySet)
        .map(KeyValueCommitting.packDamlStateKey)
        .toSeq

    if (missingInputs.nonEmpty) {
      logger.info(s"Requesting ${missingInputs.size} missing inputs...")
      pendingSubmissions(replicaId) = pendingSubmission
      ValidateResponse(
        ValidateResponse.Response.NeedState(
          ValidateResponse.NeedState(missingInputs))
      )
    } else {
      ValidateResponse(
        ValidateResponse.Response.Result(
          processPendingSubmission(replicaId, pendingSubmission)
        )
      )
    }
  }

  private def processPendingSubmission(replicaId: ReplicaId, pendingSubmission: PendingSubmission): Result = {
    val submission = pendingSubmission.submission
    val allInputs: Set[KV.DamlStateKey] = submission.getInputDamlStateList.asScala.toSet
    assert((allInputs -- pendingSubmission.inputState.keySet).isEmpty)

    val (logEntry, stateUpdates) = KeyValueCommitting.processSubmission(
      engine = engine,
      entryId = KV.DamlLogEntryId.newBuilder.setEntryId(pendingSubmission.entryId).build,
      recordTime = pendingSubmission.recordTime,
      defaultConfig = defaultConfig,
      submission = submission,
      participantId = pendingSubmission.participantId,
      inputState = pendingSubmission.inputState)

    stateUpdates.foreach { case(k, v) => cache.put(replicaId -> k, v) }

    val outKeyPairs = stateUpdates
      .toArray
      .map { case (k, v) =>
        KeyValuePair(
          KeyValueCommitting.packDamlStateKey(k),
          Envelope.enclose(v)
        )
      }
      // NOTE(JM): Since kvutils (still) uses 'Map' the results end up
      // in a non-deterministic order. Sort them to fix that.
      .sortBy(_.key.toByteArray.toIterable)

    val result =
      Result(
        Envelope.enclose(logEntry),
        outKeyPairs
      )

    logger.info(s"Submission validated. entryId=${pendingSubmission.entryId.toStringUtf8} " +
      s"participantId=${pendingSubmission.participantId} inputStates=${pendingSubmission.inputState.size} stateUpdates=${stateUpdates.size} " +
      s"resultPayload=${logEntry.getPayloadCase.toString} "
    )
    result
  }
}
