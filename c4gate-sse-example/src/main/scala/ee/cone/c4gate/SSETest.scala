package ee.cone.c4gate

import java.time.Instant

import Function.chain
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4ui.{AlienExchangeApp, FromAlienTaskAssemble}


class TestSSEApp extends ServerApp
  with EnvConfigApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with BranchApp
  with AlienExchangeApp
  with UMLClientsApp
  with ManagementApp
{
  override def assembles: List[Assemble] =
    new FromAlienTaskAssemble("localhost", "/sse.html") ::
    new TestSSEAssemble ::
    super.assembles
    //println(s"visit http://localhost:${config.get("C4HTTP_PORT")}/sse.html")
}

@assemble class TestSSEAssemble extends Assemble {
  def joinView(
    key: SrcId,
    tasks: Values[BranchTask]
  ): Values[(SrcId,BranchHandler)] = {
    println(s"joinView ${tasks}")
    for(task ← tasks) yield task.branchKey → TestSSEHandler(task.branchKey, task)
  }
}

case class TestSSEHandler(branchKey: SrcId, task: BranchTask) extends BranchHandler {
  def exchange: BranchMessage ⇒ World ⇒ World = message ⇒ local ⇒ {
    val now = Instant.now
    val (keepTo,freshTo,ackAll) = task.sending(local)
    val send = chain(List(keepTo,freshTo).flatten.map(_("show",s"${now.getEpochSecond}")))
    println(s"TestSSEHandler $keepTo $freshTo")
    SleepUntilKey.set(now.plusSeconds(1)).andThen(send).andThen(ackAll)(local)
  }
  def seeds: World ⇒ List[BranchProtocol.BranchResult] = _ ⇒ Nil
}