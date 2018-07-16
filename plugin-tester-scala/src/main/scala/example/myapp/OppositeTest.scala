package example.myapp

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.Failure
import scala.util.Success

import akka.Done
import akka.NotUsed
import akka.actor.ActorSystem
import akka.grpc.GrpcClientSettings
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source

import scala.concurrent.ExecutionContext
import akka.Done
import akka.actor.ActorSystem
import akka.grpc.scaladsl.ServiceHandler
import akka.http.scaladsl.Http
import akka.http.scaladsl.HttpsConnectionContext
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.stream.ActorMaterializer
import akka.stream.Materializer
import com.typesafe.config.ConfigFactory

import akka.NotUsed
import akka.stream._, scaladsl._

import example.myapp.opposite.grpc._

object OppositeTest {

  val CLIENT_STREAMS = 5

  def main(args: Array[String]): Unit = {

    val conf = ConfigFactory.parseString("akka.http.server.preview.enable-http2 = on")
      .withFallback(ConfigFactory.defaultApplication())
    implicit val sys: ActorSystem = ActorSystem("client", conf)
    implicit val mat: Materializer = ActorMaterializer()
    implicit val ec: ExecutionContext = sys.dispatcher

    CombinedServer.createOppositeServer().map { _ =>

      val client = new OppositeServiceClient(
        GrpcClientSettings("127.0.0.1", 8081)
      )

      val killSwitch = KillSwitches.shared("clientStreams")

      def createClientStream() = {
        val bufferSize = 1000
        val overflowStrategy = akka.stream.OverflowStrategy.dropTail
        val queuePromise = Promise[SourceQueue[ClientMessage]]()

        val clientStream =
          Source.queue[ClientMessage](bufferSize, overflowStrategy)
            .via(killSwitch.flow)
            .mapMaterializedValue { x =>
              queuePromise.success(x)
              NotUsed
            }
        client.clientStreaming(clientStream)

        queuePromise.future
      }

      val clientStreams =
        Future.sequence((1 to CLIENT_STREAMS).map { _ =>
          createClientStream()
        })

      clientStreams.flatMap { _ =>
        println("Starting client streams for 30 seconds")

        // Schedule client stream kill in 30 seconds
        sys.scheduler.scheduleOnce(30 seconds) {
          println("Killing client streams")
          killSwitch.shutdown()
        }

        // Create the single opposite server stream
        client
          .serverStreaming(ClientMessage(""))
          .mapMaterializedValue { _ =>
            println("Starting server stream")
          }
          .runForeach(println)
      }
    }
      .flatten
      .onComplete { _ =>
        System.exit(0)
      }
  }
}
