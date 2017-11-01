package ee.cone.c4ui

import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.{Context, WithPK, c4component, listed}
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._
import ee.cone.c4gate.CurrentSessionKey
import ee.cone.c4vdom.Types.ViewRes

@c4component @listed @assemble case class PublicViewAssemble(views: List[ByLocationHashView]) extends Assemble {
  type LocationHash = String
  def joinByLocationHash(
    key: SrcId,
    fromAliens: Values[FromAlienTask]
  ): Values[(LocationHash,FromAlienTask)] = for {
    fromAlien ← fromAliens
  } yield fromAlien.locationHash → fromAlien

  def joinPublicView(
    key: SrcId,
    firstborns: Values[Firstborn]
  ): Values[(SrcId,ByLocationHashView)] = for {
    _ ← firstborns
    view ← views
  } yield WithPK(view)

  def join(
    key: SrcId,
    publicViews: Values[ByLocationHashView],
    @by[LocationHash] tasks: Values[FromAlienTask]
  ): Values[(SrcId,View)] = for {
    publicView ← publicViews
    task ← tasks
  } yield WithPK(AssignedPublicView(task.branchKey,task,publicView))
}

case class AssignedPublicView(branchKey: SrcId, task: FromAlienTask, currentView: View) extends View {
  def view: Context ⇒ ViewRes = Function.chain(Seq(
    CurrentBranchKey.set(branchKey),
    CurrentSessionKey.set(task.fromAlienState.sessionKey)
  )).andThen(currentView.view)
}
