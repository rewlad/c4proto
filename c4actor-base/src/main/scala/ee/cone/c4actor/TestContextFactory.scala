package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4proto.ToByteString
import okio.ByteString

import scala.collection.immutable.Map

class ContextFactory(richRawWorldFactory: RichRawWorldFactory, reducer: RichRawWorldReducer, toUpdate: ToUpdate) {
  def updated(updates: List[Update]): Context = {
    val eWorld = richRawWorldFactory.create()
    val (bytes, headers) = toUpdate.toBytes(updates)
    val firstUpdate = SimpleRawEvent(eWorld.offset, ToByteString(bytes), headers)
    val world = reducer.reduce(List(firstUpdate))(eWorld)
    new Context(world.injected, world.assembled, Map.empty)
  }
}

/*
object NoSnapshotMaker extends SnapshotMaker {
  def make(task: SnapshotTask): () ⇒ List[RawSnapshot] = throw new Exception
}

object NoRawSnapshotLoader extends RawSnapshotLoader {
  def list(subDirStr: String): List[RawSnapshot] = Nil
  def load(snapshot: RawSnapshot): ByteString = throw new Exception
}*/
