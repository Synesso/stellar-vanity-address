
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Balance, Flow, GraphDSL, Merge, Source}
import akka.stream.{ActorMaterializer, FlowShape}
import stellar.sdk.KeyPair

object Main extends App {

  implicit val sys: ActorSystem = ActorSystem("vanity-address")
  implicit val mat: ActorMaterializer = ActorMaterializer()

  def find(s: Seq[String]): Option[KeyPair] = {
    val kp = KeyPair.random
    val accId = kp.accountId
    if (s.exists(accId.endsWith)) Some(kp) else None
  }

  val findFlow: Flow[Seq[String], KeyPair, NotUsed] =
    Flow[Seq[String]].mapConcat[KeyPair](find(_).to[collection.immutable.Iterable])

    Source.fromIterator[Seq[String]](() => Iterator.continually(args))
      .via(balancer(findFlow, Runtime.getRuntime.availableProcessors()))
      .runForeach(kp => println(kp.accountId + ":" + kp.secretSeed.mkString))


  def balancer[In, Out](worker: Flow[In, Out, Any], workerCount: Int): Flow[In, Out, NotUsed] = {
    import akka.stream.scaladsl.GraphDSL.Implicits._

    Flow.fromGraph(GraphDSL.create() { implicit b =>
      val balancer = b.add(Balance[In](workerCount, waitForAllDownstreams = true))
      val merge = b.add(Merge[Out](workerCount))

      for (_ <- 1 to workerCount) {
        // for each worker, add an edge from the balancer to the worker, then wire it to the merge element
        balancer ~> worker.async ~> merge
      }

      FlowShape(balancer.in, merge.out)
    })
  }

}