package ee.cone.c4proto

import ee.cone.c4proto.Types._
import PCProtocol._

@protocol object PCProtocol extends Protocol {
  @Id(0x0003) case class RawChildNode(@Id(0x0003) srcId: String, @Id(0x0005) parentSrcId: String, @Id(0x0004) caption: String)
  @Id(0x0001) case class RawParentNode(@Id(0x0003) srcId: String, @Id(0x0004) caption: String)
}

case object ChildNodeByParent extends IndexWorldKey[SrcId,RawChildNode]
case class ParentNodeWithChildren(caption: String, children: List[RawChildNode])

class ChildNodeByParentJoin extends Join2(
  BySrcId(classOf[RawChildNode]), VoidBy[SrcId](), ChildNodeByParent
) {
  def join(rawChildNode: Values[RawChildNode], void: Values[Unit]): Values[(SrcId,RawChildNode)] =
    rawChildNode.map(child ⇒ child.parentSrcId → child)
  def sort(nodes: Iterable[RawChildNode]): List[RawChildNode] =
    nodes.toList.sortBy(_.srcId)
}
class ParentNodeWithChildrenJoin extends Join2(
  ChildNodeByParent, BySrcId(classOf[RawParentNode]), BySrcId(classOf[ParentNodeWithChildren])
) {
  def join(
    childNodeByParent: Values[RawChildNode],
    rawParentNode: Values[RawParentNode]
  ): Values[(SrcId,ParentNodeWithChildren)] = {
    rawParentNode.map(parent ⇒
      parent.srcId → ParentNodeWithChildren(parent.caption, childNodeByParent)
    )
  }
  def sort(nodes: Iterable[ParentNodeWithChildren]): List[ParentNodeWithChildren] =
    if(nodes.size <= 1) nodes.toList else throw new Exception("PK")
}




  //lazy val qRecords = new QRecords(findAdapter)


object AssemblerTestApp extends App {
  val indexFactory = new IndexFactoryImpl
  import indexFactory._
  val handlerLists = CoHandlerLists(
    CoHandler(ProtocolKey)(QProtocol) ::
    CoHandler(ProtocolKey)(PCProtocol) ::
    createJoinMapIndex(new ChildNodeByParentJoin) ::
    createJoinMapIndex(new ParentNodeWithChildrenJoin) ::
    Nil
  )
  var recs: List[QRecord] = Nil
  val qRecords = QRecords(handlerLists){ (k:Array[Byte],v:Array[Byte]) ⇒
    recs = new QRecord {
      def key:Array[Byte] = k
      def value:Array[Byte] = v
      def offset = recs.headOption.map(_.offset).getOrElse(0)
    } :: recs
  }
  val reducer = Reducer(handlerLists)

  qRecords.sendUpdate("1", RawParentNode("1","P-1"))
  Seq("2","3").foreach(srcId⇒qRecords.sendUpdate(srcId, RawChildNode(srcId,"1",s"C-$srcId")))
  val diff = qRecords.toTree(recs.reverse)
  println(diff)
  val world = reducer.reduce(Map.empty,diff)
  println(world)

}