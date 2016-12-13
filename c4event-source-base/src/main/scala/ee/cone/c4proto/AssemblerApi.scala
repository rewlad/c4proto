package ee.cone.c4proto

import Types._

import scala.collection.immutable.Map

object Single {
  def apply[C](l: List[C]): C =
    if(l.tail.nonEmpty) throw new Exception("single expected") else l.head
}

object By {
  case class It[K,V](typeChar: Char, className: String) extends WorldKey[Index[K,V]](Map.empty)
  def srcId[V](cl: Class[V]): WorldKey[Index[SrcId,V]] = It[SrcId,V]('S', cl.getName)
  def unit[V](cl: Class[V]): WorldKey[Index[Unit,V]] = It[Unit,V]('U', cl.getName)
}

trait IndexFactory {
  def createJoinMapIndex[R<:Object,TK,RK](join: Join[R,TK,RK]):
    WorldPartExpression
      with DataDependencyFrom[Index[TK, Object]]
      with DataDependencyTo[Index[RK, R]]
}

abstract class WorldKey[Item](default: Item) {
  def of(world: World): Item = world.getOrElse(this, default).asInstanceOf[Item]
}
/*
abstract class IndexWorldKey[K,V](empty: V) extends WorldKey[Map[K,Values[V]]] {
  def of(world: Map[WorldKey[_],Map[K,Values[V]]]): V = world.getOrElse(this,Map.empty)
}*/
object Types {
  type Values[V] = List[V]
  type Index[K,V] = Map[K,Values[V]]
  type SrcId = String
  type MultiSet[T] = Map[T,Int]
  type World = Map[WorldKey[_],Object]
}

trait DataDependencyFrom[From] {
  def inputWorldKeys: Seq[WorldKey[From]]
}

trait DataDependencyTo[To] {
  def outputWorldKey: WorldKey[To]
}

trait DataDependenciesApp {
  def dataDependencies: List[DataDependencyTo[_]] = Nil
}

trait Join[Result,JoinKey,MapKey]
  extends DataDependencyFrom[Index[JoinKey,Object]]
  with DataDependencyTo[Index[MapKey,Result]]
{
  def joins(in: Seq[Values[Object]]): Iterable[(MapKey,Result)]
  def sort(values: Iterable[Result]): List[Result]
}

////
// moment -> mod/index -> key/srcId -> value -> count

trait WorldPartExpression /*[From,To] extends DataDependencyFrom[From] with DataDependencyTo[To]*/ {
  def transform(transition: WorldTransition): WorldTransition
}
case class WorldTransition(
  prev: World,
  diff: Map[WorldKey[_],Map[Object,Boolean]],
  current: World
)

trait TreeAssembler {
  def replace(prev: World, replaced: Map[WorldKey[_],Index[Object,Object]]): World
}

class OriginalWorldPart[A](val outputWorldKey: WorldKey[A]) extends DataDependencyTo[A]

////

case object ErrorsKey extends WorldKey[Index[SrcId,String]](Map.empty)

trait Reducer {
  def reduceRecover(world: World, recs: List[QRecord]): World
  def reduceCheck(world: World, rec: QRecord): (World, List[QRecord])
}

trait ReducerFactory {
  def create(actorName: ActorName, messageMappers: List[MessageMapper[_]]): Reducer
}

trait ActorFactory {
  def create(actorName: ActorName, messageMappers: List[MessageMapper[_]]): Executable with WorldProvider
}