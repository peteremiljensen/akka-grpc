package example.myapp.opposite

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._

import akka.NotUsed
import akka.stream._, scaladsl._

import example.myapp.opposite.grpc._

class OppositeServiceImpl(implicit mat: Materializer, ec: ExecutionContext) extends OppositeService {

  def serverStreaming(in: ClientMessage): Source[ServerMessage, NotUsed] =
    Source(0 to 5)
      .throttle(1, 0.5 seconds, 1, ThrottleMode.shaping)
      .map(x => ServerMessage(x.toString))

  def clientStreaming(in: Source[ClientMessage, NotUsed]): Future[ServerMessage] =
    in
      .runForeach(println)
      .map(_ => ServerMessage("done"))
}
