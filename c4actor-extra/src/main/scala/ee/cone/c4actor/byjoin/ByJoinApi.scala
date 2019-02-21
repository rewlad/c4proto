package ee.cone.c4actor.byjoin

import ee.cone.c4actor.ProdLens
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.{EachSubAssemble, ValuesSubAssemble}

import scala.reflect.ClassTag

trait ByJoinApp {
  def byJoinFactory: ByJoinFactory
}

trait ByPKJoin[From <: Product, Value] {
  def toEach[To <: Product](implicit ct: ClassTag[To]): EachSubAssemble[To]
  def toValues[To <: Product](implicit ct: ClassTag[To]): ValuesSubAssemble[To]
}

trait ByJoinFactory {
  def byPK[From <: Product](lens: ProdLens[From, List[SrcId]])(implicit ct: ClassTag[From]): ByPKJoin[From, List[SrcId]]
}