package ee.cone.c4gate

import ee.cone.c4ui.CanvasContent
import java.net.URL

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.{Assemble, Single, assemble, by}
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4gate.TestCanvasProtocol.TestCanvasState
import ee.cone.c4gate.TestFilterProtocol.Content
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4ui._
import ee.cone.c4vdom.Types.ViewRes

object TestCanvas extends Main((new TestCanvasApp).execution.run)

class TestCanvasApp extends ServerApp
  with EnvConfigApp
  with KafkaProducerApp with KafkaConsumerApp
  with SerialObserversApp
  with UIApp
  with TestTagsApp
  with CanvasApp
{
  override def protocols: List[Protocol] = TestCanvasProtocol :: super.protocols
  override def assembles: List[Assemble] =
    new TestCanvasAssemble ::
      new FromAlienTaskAssemble("localhost", "/react-app.html") ::
      super.assembles
}

case object CanvasTaskX extends TextInputLens[TestCanvasState](_.x,v⇒_.copy(x=v))
case object CanvasTaskY extends TextInputLens[TestCanvasState](_.y,v⇒_.copy(y=v))

@protocol object TestCanvasProtocol extends Protocol {
  @Id(0x0008) case class TestCanvasState(
    @Id(0x0009) sessionKey: String,
    @Id(0x000A) x: String,
    @Id(0x000B) y: String
  )
}

@assemble class TestCanvasAssemble extends Assemble {
  def joinView(
    key: SrcId,
    tasks: Values[FromAlienTask]
  ): Values[(SrcId,View)] =
    for(
      task ← tasks;
      view ← Option(task.locationHash).collect{
        case "rectangle" ⇒ TestCanvasView(task.branchKey,task.fromAlienState.sessionKey)
      }
    ) yield task.branchKey → view

  def joinCanvas(
    key: SrcId,
    branchTasks: Values[BranchTask]
  ): Values[(SrcId,CanvasHandler)] =
    for (
      branchTask ← branchTasks;
      state ← Option(branchTask.product).collect { case s: TestCanvasState ⇒ s };
    ) yield branchTask.branchKey → TestCanvasHandler(branchTask.branchKey, state.sessionKey)


}

case class TestCanvasHandler(branchKey: SrcId, sessionKey: SrcId) extends CanvasHandler {
  def messageHandler: (String ⇒ String) ⇒ World ⇒ World = ???
  def view: World ⇒ CanvasContent = local ⇒ {
    val res =
      s"""
         |
         |
         |
       """.stripMargin
    CanvasContentImpl(res,System.currentTimeMillis+1000)
  }
}

case class TestCanvasView(branchKey: SrcId, sessionKey: SrcId) extends View {
  def view: World ⇒ ViewRes = local ⇒ {
    val world = TxKey.of(local).world
    val canvasTasks = By.srcId(classOf[TestCanvasState]).of(world)
    val branchOperations = BranchOperationsKey.of(local).get
    val tags = TagsKey.of(local).get
    val tTags = TestTagsKey.of(local).get
    val canvasTask = Single(canvasTasks.getOrElse(sessionKey,List(TestCanvasState(sessionKey,"",""))))
    //
    val inputX = tTags.toInput("x", CanvasTaskX)
    val inputY = tTags.toInput("y", CanvasTaskY)
    val canvasSeed = (t:TestCanvasState) ⇒ tags.seed(branchOperations.toSeed(t))
    List(inputX(canvasTask), inputY(canvasTask), canvasSeed(canvasTask))
  }
}

case class CanvasContentImpl(value: String, until: Long) extends CanvasContent

