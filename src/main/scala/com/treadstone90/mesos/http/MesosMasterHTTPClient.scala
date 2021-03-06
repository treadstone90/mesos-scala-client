package com.treadstone90.mesos.http

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, TimeUnit}

import com.google.common.net.HostAndPort
import com.treadstone90.mesos.scheduler.{Scheduler, SchedulerDriver}
import com.twitter.concurrent.AsyncStream
import com.twitter.finagle.Http
import com.twitter.finagle.http.Status.{ClientError, ServerError, Successful}
import com.twitter.finagle.http.{Request, Response, Status => FinagleStatus}
import com.twitter.io.{Buf, Reader}
import com.twitter.logging.Logger
import com.twitter.util.{Future, Promise, Try}
import org.apache.mesos.v1.mesos.{FrameworkID, FrameworkInfo}
import org.apache.mesos.v1.scheduler.scheduler.Call.Subscribe
import org.apache.mesos.v1.scheduler.scheduler.Call.Type.SUBSCRIBE
import org.apache.mesos.v1.scheduler.scheduler.{Call, Event}

/**
  * Created by karthik on 7/11/17.
  */
class MesosMasterHTTPClient(val hostAndPort: HostAndPort,
                            frameworkInfo: FrameworkInfo,
                            eventHandler: Scheduler,
                            mesosDriver: SchedulerDriver) extends StreamingClient {

  private val endpoint = s"${hostAndPort.getHost}:${hostAndPort.getPort}"
  private val streamClient = Http.client
    .withStreaming(enabled = true)
    .newService(endpoint)
  private val callClient = Http.client
    .newService(endpoint)

  private val heartBeatCounter = new AtomicInteger(0)

  private val mesosStreamIdHeader = "Mesos-Stream-Id"
  private val heartBeatScheduler = Executors.newScheduledThreadPool(1)
  private var mesosStreamSubscrption: Option[MesosStreamSubscription] = None
  private val log = Logger.get(getClass)

  private var frameworkId: Option[FrameworkID] = {
    if(frameworkInfo.id.nonEmpty) {
      log.info("Reusing frameworkInfo passed in from driver.")
      frameworkInfo.id
    } else {
      None
    }
  }
  private val clientExitPromise = Promise[ClientStatus]()

  def subscribe(): Future[ClientStatus] = {
    val subscription = Subscribe(frameworkInfo)
    val callRequest: Call = Call(`type` = Some(SUBSCRIBE),
      subscribe = Some(subscription)).copy(frameworkId = frameworkId)

    val request = SchedulerCallRequest(Buf.ByteArray(callRequest.toByteArray: _*),
      endpoint)

    val streamSubscription = doRequest(request)

    streamSubscription.onSuccess { sub =>
      mesosStreamSubscrption = Some(sub)
      heartBeatScheduler.scheduleAtFixedRate(new Runnable {
        def run(): Unit = {
          val beats = heartBeatCounter.getAndSet(0)
          if(beats < 1) {
            log.error(s"Received only $beats heartbeats in the last minute. Killing connection to master.")
            shutdown(HeartBeatFailure)
          }
        }
      }, 30, 30, TimeUnit.SECONDS)
    }.onFailure { t =>
      log.error(t, s"Failed to obtain mesos stream subscription $t. Aborting client.")
      shutdown(SubscriptionNotFound)
    }
    clientExitPromise
  }

  def shutdown(driverStatus: ClientStatus): Future[ClientStatus] = {
    callClient.close().flatMap { _ =>
      streamClient.close()
    }.transform { e =>
      e.onFailure { ex =>
        log.error(ex, "Encountered error while shutting down client. Closing executors")
      }
      heartBeatScheduler.shutdownNow()
      clientExitPromise.setValue(driverStatus)
      Future(driverStatus)
    }
  }

  def call(call: Call): Future[ClientStatus] = {
    if(mesosStreamSubscrption.isEmpty) {
      clientExitPromise.setValue(SubscriptionNotFound)
      Future(SubscriptionNotFound)
    } else if(frameworkId.isEmpty) {
      log.error("FrameworkID is empty after registration.")
      clientExitPromise.setValue(SubscriptionNotFound)
      Future(SubscriptionNotFound)
    } else {
      val populatedCall = call.copy(frameworkId = frameworkId)
      val request = SchedulerCallRequest(Buf.ByteArray(populatedCall.toByteArray: _*), endpoint)
      mesosStreamSubscrption.foreach(s => request.headerMap.add(mesosStreamIdHeader, s.mesosStreamId))
      callClient(request).map { response =>
        log.debug(s"Received response with status ${response.status}")
        response.status match {
          case ClientError(status) => {
            log.error("Malformed request sent by the scheduler. Terminating scheduler")
            throw new RuntimeException(status.reason)
          }
          case ServerError(status) => {
            log.warning(s"Receiving 5xx error from master $status")
            ClientRunning
          }
          case Successful(status) => ClientRunning
        }
      }.rescue { case e: Exception =>  shutdown(RuntimeError) }
    }
  }

  private def doRequest(request: Request): Future[MesosStreamSubscription] = {
    streamClient(request).map {
      case response if response.status != FinagleStatus.Ok =>
        throw new RuntimeException(s"Scheduler returned ${response.status} during registration.")
      case response =>
        handleResponse(response)
        val streamId = response.headerMap.get(mesosStreamIdHeader)
        MesosStreamSubscription(streamId.getOrElse(
          throw new RuntimeException("Mesos streamdId missing from scheduler response.")))
    }
  }

  private def handleResponse(response: Response) = {
    fromReader(response.reader).foreach { buf =>
      Try {
        val byteBuf = Buf.ByteBuffer.Owned.extract(buf)
        val array = removeLF(byteBuf.array())
        val event = Event.parseFrom(array)
        handleEvent(event)
      }
    }
  }

  def monitorHeartbeat(): Unit = {
    log.info("Received heart beat from mesos master")
    heartBeatCounter.incrementAndGet()
  }

  private def handleEvent(event: Event): Unit = {
    event.`type` match {
      case Some(Event.Type.SUBSCRIBED) => {
        if(frameworkId.isEmpty) {
          frameworkId = Some(event.subscribed.get.frameworkId)
        } else {
          assert(frameworkId.contains(event.subscribed.get.frameworkId),
            "FrameworkId returned by master is different than what was sent in subscribed")
        }
        eventHandler.registered(mesosDriver, event.subscribed.get)
      }
      case Some(Event.Type.OFFERS) =>
        eventHandler.resourceOffers(mesosDriver, event.offers.get)
      case Some(Event.Type.RESCIND) =>
        eventHandler.offerRescinded(mesosDriver, event.rescind.get.offerId)
      case Some(Event.Type.UPDATE) =>
        eventHandler.statusUpdate(mesosDriver, event.update.get.status)
      case Some(Event.Type.MESSAGE) =>
        eventHandler.frameworkMessage(mesosDriver, event.message.get)
      case Some(Event.Type.FAILURE) =>
        if(event.failure.get.executorId.isDefined) {
          eventHandler.executorLost(mesosDriver, event.failure.get.executorId.get, event.failure.get.agentId.get)
        } else {
          eventHandler.agentLost(mesosDriver, event.failure.get.agentId.get)
        }
      case Some(Event.Type.ERROR) =>
        eventHandler.error(mesosDriver, event.error.get.message)
      case Some(Event.Type.HEARTBEAT) =>
        monitorHeartbeat()
      case Some(_) =>
        log.error("Received unknown type not known from mesos !")
        throw new IllegalArgumentException("Received unknown event type")
      case None =>
        log.error("Event type not specified in response")
        throw new IllegalArgumentException("Event type not specified in response.  ")
    }
  }

  private def removeLF(bytes: Array[Byte]): Array[Byte] = {
    bytes.dropWhile(_ != 10.toByte).tail
  }

  private def readLength(reader: Reader): Future[Buf] = {
    reader.read(1).flatMap {
      case Some(buf) =>
        if (buf.equals(Buf.ByteArray(10.toByte))) {
          Future(Buf.Empty)
        } else {
          readLength(reader).flatMap { next =>
            Future(buf.concat(next))
          }
        }
      case None => Future(Buf.Empty)
    }
  }

  private def readBytes(reader: Reader): Future[Option[Buf]] = {
    readLength(reader).flatMap {
      case Buf.Empty => Future(None)
      case Buf.UsAscii(len) =>
        // The mesos RecordIO format is not accurate and contains the length of the byte stream and a new line operator
        // So I need to read 1 + len(size) bytes.
        reader.read(len.toInt + len.size + 1)
    }
  }

  private def fromReader(reader: Reader): AsyncStream[Buf] =
    AsyncStream.fromFuture(readBytes(reader)).flatMap {
      case None => {
        AsyncStream.empty
      }
      case Some(a) => {
        a +:: fromReader(reader)
      }
    }
}

