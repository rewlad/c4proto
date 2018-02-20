package ee.cone.c4actor.dependancy

import ee.cone.c4actor.CtxType.Request
import ee.cone.c4actor.{AssemblesApp, ProtocolsApp, WithPK}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dependancy.ByPKRequestProtocol.ByPKRequest
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by, was}
import ee.cone.c4proto.{Id, Protocol, protocol}


case object ByPKRequestHandler extends RequestHandler[ByPKRequest] {
  override def canHandle = classOf[ByPKRequest]

  override def handle: Request => Dep[_] = request ⇒ new RequestDep(request)
}

trait ByPKRequestHandlerApp extends AssemblesApp with RequestHandlerRegistryApp with ProtocolsApp {
  def handledClasses: List[Class[_]] = Nil

  //override def handlers: List[RequestHandler[_]] = ByPKRequestHandler :: super.handlers

  override def assembles: List[Assemble] = handledClasses.map(className ⇒ new ByPKGenericAssemble(className)) ::: super.assembles

  override def protocols: List[Protocol] = ByPKRequestProtocol :: super.protocols
}

@assemble class ByPKGenericAssemble[A](handledClass: Class[A]) extends Assemble {
  type ToResponse = SrcId
  type ByPkItemSrcId = SrcId

  def RequestWithSrcToItemSrcId(
    key: SrcId,
    @was requests: Values[RequestWithSrcId]
  ): Values[(ByPkItemSrcId, RequestWithSrcId)] =
    for (
      rq ← requests
      if rq.request.isInstanceOf[ByPKRequest]
    ) yield {
      val byPkRq = rq.request.asInstanceOf[ByPKRequest]
      (byPkRq.itemSrcId, rq)
    }

  def RequestToResponse(
    key: SrcId,
    @by[ByPkItemSrcId] requests: Values[RequestWithSrcId],
    items: Values[A]
  ): Values[(ToResponse, Response)] =
    (for (
      rq ← requests
      if rq.request.isInstanceOf[ByPKRequest]
    ) yield {
      val response = Response(rq, Option(items.headOption))
      WithPK(response) :: (for (id ← rq.parentSrcIds) yield (id, response))
    }).flatten
}

@protocol object ByPKRequestProtocol extends Protocol {

  @Id(0x0fa6) case class ByPKRequest(
    @Id(0x0fa7) className: String,
    @Id(0x0fa8) itemSrcId: String
  )

}

//TODO ByFK try